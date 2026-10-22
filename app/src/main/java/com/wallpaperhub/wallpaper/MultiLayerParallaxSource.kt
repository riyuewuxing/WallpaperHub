package com.wallpaperhub.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 实现多图层 3D 景深视差壁纸渲染源。
 * 利用旋转矢量传感器（Sensor.TYPE_ROTATION_VECTOR）计算陀螺仪偏转，
 * 解析四元数并映射为各图层的平移矩阵偏移，根据图层的 Z 深度绘制多层纹理。
 */
class MultiLayerParallaxSource(
    private val context: Context,
    private val layers: List<ParallaxLayer>,
    initialStrength: Float = 1.0f,
) : RenderSource {

    data class ParallaxLayer(
        val bitmap: Bitmap,
        /** Z-轴深度，决定偏移比例。0.0f 为最远背景层（基本不动），1.0f 为最近前景层（偏移最大） */
        val zDepth: Float
    )

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uSamplerLoc = 0

    private val textureIds = IntArray(layers.size)
    private val mvpMatrix = FloatArray(16)

    private var viewWidth = 0
    private var viewHeight = 0
    private var userOffsetX = 0f
    private var userOffsetY = 0f
    private var scaleX = 1.0f
    private var scaleY = 1.0f
    private var strength = initialStrength.coerceIn(0f, 2f)

    // 传感器计算出的平移偏转量
    private var sensorOffsetX = 0f
    private var sensorOffsetY = 0f

    // 用于平滑过滤的低通滤波系数
    private val alpha = 0.12f

    // 最大平移范围系数（在裁剪后的视差范围内）
    private val maxParallaxTranslation = 0.08f

    private val vertexBuffer: FloatBuffer = run {
        // 标准满屏顶点（四边形）
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data).position(0) }
    }

    override fun onInit() {
        // 初始化 OpenGL 纹理
        GLES20.glGenTextures(layers.size, textureIds, 0)
        for (i in layers.indices) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, layers[i].bitmap, 0)
        }

        // 编译 Shader
        val vertexShaderSrc = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderSrc = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        program = GLVideoRenderer.compileProgram(vertexShaderSrc, fragmentShaderSrc)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
        uSamplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

        Matrix.setIdentityM(mvpMatrix, 0)
        updateMvp()
    }

    override fun onDrawFrame() {
        if (program == 0) return

        // 开启混合，支持透明通道叠加
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        // 从后往前绘制每一层纹理（背景 -> 前景）
        for (i in layers.indices) {
            val layer = layers[i]
            val layerMvp = mvpMatrix.clone()

            // userOffset is normalized in [-1, 1], we map it to [-(scaleX - 1), (scaleX - 1)] / scaleX
            // (since the translation is done after scale, we divide by scaleX to get the correct net NDC translation)
            val userTx = if (scaleX > 0f) -userOffsetX * (scaleX - 1f) / scaleX else 0f
            val userTy = if (scaleY > 0f) userOffsetY * (scaleY - 1f) / scaleY else 0f

            // 计算平移矩阵：基础裁剪位置 + 传感器视差偏移量 * Z轴深度权重 * 平移幅度
            val tx = userTx + sensorOffsetX * layer.zDepth * maxParallaxTranslation * strength
            val ty = userTy + sensorOffsetY * layer.zDepth * maxParallaxTranslation * strength
            Matrix.translateM(layerMvp, 0, tx, ty, 0f)

            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, layerMvp, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
            GLES20.glUniform1i(uSamplerLoc, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun updateSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        updateMvp()
    }

    override fun updateOffset(offsetX: Float, offsetY: Float) {
        userOffsetX = offsetX
        userOffsetY = offsetY
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 2f)
    }

    override fun updateSensor(values: FloatArray) {
        if (values.size < 3) return

        // 旋转矢量传感器提供四元数 [x, y, z, w]
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = if (values.size > 3) values[3] else {
            val len = x * x + y * y + z * z
            if (len < 1.0f) Math.sqrt(1.0 - len).toFloat() else 0.0f
        }

        // 解析四元数到 Euler 角度 (Pitch / Roll)
        // Roll (绕 Y 轴的旋转，即左右偏转)
        val sinrCosp = 2.0f * (w * x + y * z)
        val cosrCosp = 1.0f - 2.0f * (x * x + y * y)
        val roll = atan2(sinrCosp.toDouble(), cosrCosp.toDouble()).toFloat()

        // Pitch (绕 X 轴的旋转，即前后俯仰)
        val sinp = 2.0f * (w * y - z * x)
        val pitch = if (abs(sinp) >= 1.0f) {
            java.lang.Math.copySign(Math.PI / 2.0, sinp.toDouble()).toFloat()
        } else {
            asin(sinp.toDouble()).toFloat()
        }

        // 限制偏转范围，并归一化到 [-1f, 1f]
        val maxAngle = 0.5f // 约为 30 度
        val targetX = (roll / maxAngle).coerceIn(-1f, 1f)
        val targetY = (pitch / maxAngle).coerceIn(-1f, 1f)

        // 应用低通滤波使其运动更平滑，消除物理手抖噪声
        sensorOffsetX = sensorOffsetX * (1f - alpha) + targetX * alpha
        sensorOffsetY = sensorOffsetY * (1f - alpha) + targetY * alpha
    }

    override fun onRelease() {
        // Program is cached, do not delete it.
        if (textureIds.isNotEmpty()) {
            GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
        }
        for (layer in layers) {
            try {
                if (!layer.bitmap.isRecycled) {
                    layer.bitmap.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateMvp() {
        if (viewWidth <= 0 || viewHeight <= 0) return

        // 假如图层尺寸默认按屏幕比例或者用第一张图尺寸
        val layerW = layers.firstOrNull()?.bitmap?.width ?: 1080
        val layerH = layers.firstOrNull()?.bitmap?.height ?: 1920
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val layerRatio = layerW.toFloat() / layerH.toFloat()

        Matrix.setIdentityM(mvpMatrix, 0)

        // 为视差运动留出空白边，缩放图层以覆盖偏移时的黑边
        val safetyScale = 1.15f

        if (layerRatio >= viewRatio) {
            scaleX = (layerRatio / viewRatio) * safetyScale
            scaleY = safetyScale
        } else {
            scaleX = safetyScale
            scaleY = (viewRatio / layerRatio) * safetyScale
        }
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }
}
