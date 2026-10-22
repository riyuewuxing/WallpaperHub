package com.wallpaperhub.wallpaper

import android.opengl.GLES20
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 渲染自定义 fragment shader 的渲染源。
 * 注入 u_time / iTime、u_resolution / iResolution 以及 u_offset。
 */
class ShaderRenderSource(
    private val customFragmentShader: String? = null
) : RenderSource {

    private var program = 0
    private var aPositionLoc = 0
    private var uTimeLoc = 0
    private var uTimeLocAlternative = 0
    private var uResolutionLoc = 0
    private var uResolutionLocAlternative = 0
    private var uOffsetLoc = 0

    private var viewWidth = 0
    private var viewHeight = 0
    private var startTime = 0L
    private var userOffsetX = 0f
    private var userOffsetY = 0f

    private val vertexBuffer: FloatBuffer = run {
        // 标准满屏顶点（四边形）
        val data = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data).position(0) }
    }

    override fun onInit() {
        startTime = SystemClock.elapsedRealtime()

        val vertexShaderSrc = """
            attribute vec4 aPosition;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aPosition.xy * 0.5 + 0.5;
            }
        """.trimIndent()

        // 默认炫彩液体噪声 Shader 作为内置演示
        val defaultFragmentShaderSrc = """
            precision mediump float;
            uniform float u_time;
            uniform vec2 u_resolution;
            varying vec2 vTexCoord;
            
            void main() {
                vec2 uv = gl_FragCoord.xy / u_resolution.xy;
                // 炫彩等离子体液体效果
                float t = u_time * 0.4;
                float x = uv.x * 2.0 - 1.0;
                float y = uv.y * 2.0 - 1.0;
                float color = sin(x * 3.0 + t) + sin(y * 2.0 - t) + sin(x + y + t);
                vec3 finalColor = vec3(
                    0.5 + 0.5 * sin(color + t * 0.5 + 0.0),
                    0.5 + 0.5 * sin(color - t * 0.3 + 2.0),
                    0.5 + 0.5 * sin(color + t * 0.2 + 4.0)
                );
                gl_FragColor = vec4(finalColor, 1.0);
            }
        """.trimIndent()

        val fragmentShaderSrc = customFragmentShader ?: defaultFragmentShaderSrc

        program = GLVideoRenderer.compileProgram(vertexShaderSrc, fragmentShaderSrc)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        
        // 查找支持的 Uniform
        uTimeLoc = GLES20.glGetUniformLocation(program, "u_time")
        uTimeLocAlternative = GLES20.glGetUniformLocation(program, "iTime")
        uResolutionLoc = GLES20.glGetUniformLocation(program, "u_resolution")
        uResolutionLocAlternative = GLES20.glGetUniformLocation(program, "iResolution")
        uOffsetLoc = GLES20.glGetUniformLocation(program, "u_offset")
    }

    override fun onDrawFrame() {
        if (program == 0) return

        GLES20.glUseProgram(program)

        // 输入顶点数据
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        // 注入系统变量：时间（以秒为单位）
        val elapsedSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
        if (uTimeLoc >= 0) {
            GLES20.glUniform1f(uTimeLoc, elapsedSeconds)
        }
        if (uTimeLocAlternative >= 0) {
            GLES20.glUniform1f(uTimeLocAlternative, elapsedSeconds)
        }

        // 注入系统变量：屏幕分辨率
        if (uResolutionLoc >= 0) {
            GLES20.glUniform2f(uResolutionLoc, viewWidth.toFloat(), viewHeight.toFloat())
        }
        if (uResolutionLocAlternative >= 0) {
            GLES20.glUniform2f(uResolutionLocAlternative, viewWidth.toFloat(), viewHeight.toFloat())
        }

        // 注入系统变量：用户偏移量
        if (uOffsetLoc >= 0) {
            GLES20.glUniform2f(uOffsetLoc, userOffsetX, userOffsetY)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
    }

    override fun updateSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun updateOffset(offsetX: Float, offsetY: Float) {
        userOffsetX = offsetX
        userOffsetY = offsetY
    }

    override fun onRelease() {
        // Program is cached, do not delete it.
    }
}
