package com.wallpaperhub.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.wallpaperhub.playback.telemetry.PlaybackTrace
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.preview.model.CropGeometry
import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.preview.model.MediaTransformPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenGL ES 2.0 video/scene renderer.
 *
 * GL resources are owned by the wallpaper render thread. Media3 commands live on one
 * process-lifetime application looper; individual sources never terminate that looper. Durable
 * playback confirmation is emitted only after the target owns currentSource and a successful EGL
 * swap has completed.
 */
class GLVideoRenderer(
    outputSurface: Surface,
    private var viewWidth: Int,
    private var viewHeight: Int,
    initialVideoWidth: Int,
    initialVideoHeight: Int,
) {
    private val rendererId = WallpaperDebugLog.nextId("renderer")
    private val createdAtMs = SystemClock.elapsedRealtime()
    private var lastDrawAtMs = 0L
    private var released = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var chosenConfig: EGLConfig? = null

    private var textureId = 0
    private var imageTextureId = 0
    private var rootSurfaceTexture: SurfaceTexture? = null
    val surfaceTexture: SurfaceTexture
        get() = checkNotNull(rootSurfaceTexture) { "Renderer SurfaceTexture is not available" }

    private var videoWidth = initialVideoWidth
    private var videoHeight = initialVideoHeight
    private var cropOffsetX = 0f
    private var cropOffsetY = 0f
    private var cropScale = 1f
    private var cropScaleMode = MediaScaleMode.FILL

    var currentSource: RenderSource? = null
        private set
    var nextSource: RenderSource? = null
        private set

    private var firstFrameListener: (() -> Unit)? = null
    private var firstFrameFailureListener: ((String) -> Unit)? = null
    private var firstFrameSource: RenderSource? = null
    private var firstFrameRequestId: String? = null
    private var firstFrameGeneration = 0L
    private var firstFrameTimeoutRunnable: Runnable? = null
    private var firstFrameReported = true
    private val firstFrameHandler = Handler(Looper.myLooper() ?: Looper.getMainLooper())

    private val fboIds = IntArray(2)
    private val fboTextures = IntArray(2)
    private var fboWidth = 0
    private var fboHeight = 0

    private var transitionProgram = 0
    private var transitionAPositionLoc = 0
    private var transitionATexCoordLoc = 0
    private var transitionSamplerALoc = 0
    private var transitionSamplerBLoc = 1
    private var transitionProgressLoc = 0
    private var transitionTypeLoc = 0
    private var transitionType = 0

    var isTransitioning = false
        private set
    private var directVideoHandoff = false
    val isAwaitingFirstFrame: Boolean
        get() = !firstFrameReported
    val isWaitingForVideoHandoff: Boolean
        get() = directVideoHandoff
    private var transitionStartTime = 0L
    private val transitionDuration = 750L

    private val transitionVertexBuffer: FloatBuffer = run {
        val data = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data).position(0) }
    }

    init {
        WallpaperDebugLog.i(
            "renderer",
            "create_start",
            "renderer" to rendererId,
            "viewport" to "${viewWidth}x$viewHeight",
            "threadHasLooper" to (Looper.myLooper() != null),
        )
        try {
            initEGL(outputSurface)
            textureId = createExternalOESTexture()
            val root = SurfaceTexture(textureId)
            rootSurfaceTexture = root

            val oesSource = OesVideoRenderSource(textureId, root)
            root.setOnFrameAvailableListener({
                if (!released) oesSource.markFrameAvailable()
            }, Handler(Looper.myLooper() ?: Looper.getMainLooper()))
            oesSource.updateViewportSize(viewWidth, viewHeight)
            oesSource.updateSize(initialVideoWidth, initialVideoHeight)
            currentSource = oesSource
            oesSource.onInit()

            initTransitionProgram()
            WallpaperDebugLog.i(
                "renderer",
                "create_complete",
                "renderer" to rendererId,
                "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
                "initialSource" to sourceLabel(currentSource),
            )
        } catch (e: Throwable) {
            WallpaperDebugLog.e(
                "renderer",
                "create_failed",
                e,
                "renderer" to rendererId,
                "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
            )
            release()
            throw e
        }
    }

    fun transitionTo(newSource: RenderSource) {
        if (released) {
            newSource.onRelease()
            return
        }
        val previous = currentSource
        val directVideoSwitch = RenderTransitionPolicy.useOffscreenTransition(
            currentIsVideo = currentSource is VideoDecoderRenderSource,
            nextIsVideo = newSource is VideoDecoderRenderSource,
        ).not()
        WallpaperDebugLog.i(
            "renderer",
            "transition_request",
            "renderer" to rendererId,
            "current" to sourceLabel(previous),
            "existingNext" to sourceLabel(nextSource),
            "target" to sourceLabel(newSource),
            "directVideo" to directVideoSwitch,
        )

        if (
            directVideoSwitch &&
            RenderTransitionPolicy.shouldPreserveCurrentFrame(
                currentIsVideo = currentSource is VideoDecoderRenderSource,
                nextIsVideo = newSource is VideoDecoderRenderSource,
            ) &&
            currentSource != null
        ) {
            nextSource?.onRelease()
            nextSource = newSource
            newSource.onInit()
            (newSource as? VideoDecoderRenderSource)?.updateViewportSize(viewWidth, viewHeight)
            newSource.updateSize(viewWidth, viewHeight)
            newSource.updateTransform(CropTransform(cropScale, cropOffsetX, cropOffsetY, cropScaleMode))
            directVideoHandoff = true
            isTransitioning = false
            WallpaperDebugLog.i(
                "renderer",
                "transition_wait_video_handoff",
                "renderer" to rendererId,
                "current" to sourceLabel(currentSource),
                "target" to sourceLabel(nextSource),
            )
        } else if (currentSource == null || currentSource is OesVideoRenderSource || directVideoSwitch) {
            nextSource?.onRelease()
            nextSource = null
            currentSource?.onRelease()
            currentSource = newSource
            newSource.onInit()
            (newSource as? VideoDecoderRenderSource)?.updateViewportSize(viewWidth, viewHeight)
            (newSource as? OesVideoRenderSource)?.updateViewportSize(viewWidth, viewHeight)
            newSource.updateSize(viewWidth, viewHeight)
            newSource.updateTransform(CropTransform(cropScale, cropOffsetX, cropOffsetY, cropScaleMode))
            directVideoHandoff = false
            isTransitioning = false
            WallpaperDebugLog.i(
                "renderer",
                "transition_direct_commit",
                "renderer" to rendererId,
                "current" to sourceLabel(currentSource),
            )
        } else {
            nextSource?.onRelease()
            nextSource = newSource
            newSource.onInit()
            (newSource as? VideoDecoderRenderSource)?.updateViewportSize(viewWidth, viewHeight)
            (newSource as? OesVideoRenderSource)?.updateViewportSize(viewWidth, viewHeight)
            newSource.updateSize(viewWidth, viewHeight)
            newSource.updateTransform(CropTransform(cropScale, cropOffsetX, cropOffsetY, cropScaleMode))
            transitionStartTime = SystemClock.elapsedRealtime()
            directVideoHandoff = false
            isTransitioning = true
            WallpaperDebugLog.i(
                "renderer",
                "transition_crossfade_start",
                "renderer" to rendererId,
                "current" to sourceLabel(currentSource),
                "target" to sourceLabel(nextSource),
                "durationMs" to transitionDuration,
            )
        }
    }

    fun awaitFirstFrame(
        source: RenderSource,
        requestId: String,
        timeoutMs: Long = 2_500L,
        listener: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (released) {
            onFailed("renderer_released")
            return
        }
        firstFrameTimeoutRunnable?.let(firstFrameHandler::removeCallbacks)
        val generation = ++firstFrameGeneration
        firstFrameReported = false
        firstFrameSource = source
        firstFrameRequestId = requestId
        firstFrameListener = listener
        firstFrameFailureListener = onFailed
        WallpaperDebugLog.i(
            "renderer",
            "first_frame_wait_armed",
            "renderer" to rendererId,
            "request" to requestId.take(32),
            "source" to sourceLabel(source),
            "timeoutMs" to timeoutMs,
            "generation" to generation,
        )
        val timeout = Runnable {
            if (
                !firstFrameReported &&
                generation == firstFrameGeneration &&
                firstFrameSource === source &&
                firstFrameRequestId == requestId
            ) {
                firstFrameReported = true
                firstFrameSource = null
                firstFrameRequestId = null
                firstFrameTimeoutRunnable = null
                firstFrameListener = null
                firstFrameFailureListener = null
                WallpaperDebugLog.w(
                    "renderer",
                    "first_frame_wait_timeout",
                    "renderer" to rendererId,
                    "request" to requestId.take(32),
                    "source" to sourceLabel(source),
                    "timeoutMs" to timeoutMs,
                    "generation" to generation,
                )
                onFailed("first_frame_timeout")
            }
        }
        firstFrameTimeoutRunnable = timeout
        if (!firstFrameHandler.postDelayed(timeout, timeoutMs)) {
            cancelFirstFrameWait()
            onFailed("first_frame_handler_unavailable")
        }
    }

    private fun cancelFirstFrameWait() {
        firstFrameGeneration++
        firstFrameTimeoutRunnable?.let(firstFrameHandler::removeCallbacks)
        firstFrameTimeoutRunnable = null
        firstFrameReported = true
        firstFrameSource = null
        firstFrameRequestId = null
        firstFrameListener = null
        firstFrameFailureListener = null
    }

    fun setTransitionType(value: String) {
        transitionType = if (value.equals("zoom", ignoreCase = true)) 1 else 0
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        (currentSource as? OesVideoRenderSource)?.updateSize(width, height)
        (nextSource as? OesVideoRenderSource)?.updateSize(width, height)
        (currentSource as? VideoDecoderRenderSource)?.updateSize(width, height)
        (nextSource as? VideoDecoderRenderSource)?.updateSize(width, height)
    }

    fun setDisplayGravity(gravity: VideoWallpaperSettings.DisplayGravity) {
        val (x, y) = VideoWallpaperSettings.gravityToOffset(gravity)
        setCropOffset(x, y)
    }

    fun setCropOffset(x: Float, y: Float) {
        cropOffsetX = x.coerceIn(-1f, 1f)
        cropOffsetY = y.coerceIn(-1f, 1f)
        currentSource?.updateOffset(cropOffsetX, cropOffsetY)
        nextSource?.updateOffset(cropOffsetX, cropOffsetY)
    }

    fun setCropTransform(transform: CropTransform) {
        val clamped = CropGeometry.clamp(transform)
        cropScale = clamped.scale
        cropOffsetX = clamped.offsetX.coerceIn(-1f, 1f)
        cropOffsetY = clamped.offsetY.coerceIn(-1f, 1f)
        cropScaleMode = clamped.scaleMode
        currentSource?.updateTransform(clamped)
        nextSource?.updateTransform(clamped)
    }

    fun drawFrame() = draw()

    fun draw() {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        val startMs = SystemClock.elapsedRealtime()
        val gapMs = if (lastDrawAtMs == 0L) 0L else startMs - lastDrawAtMs
        lastDrawAtMs = startMs
        if (gapMs >= DRAW_GAP_WARNING_MS && (isAwaitingFirstFrame || isWaitingForVideoHandoff || isTransitioning)) {
            WallpaperDebugLog.w(
                "renderer",
                "draw_gap_during_transition",
                "renderer" to rendererId,
                "gapMs" to gapMs,
                "current" to sourceLabel(currentSource),
                "next" to sourceLabel(nextSource),
                "awaitingFirstFrame" to isAwaitingFirstFrame,
                "videoHandoff" to isWaitingForVideoHandoff,
                "transitioning" to isTransitioning,
            )
        }
        PlaybackTrace.section("render.draw") { drawInternal() }
        val durationMs = SystemClock.elapsedRealtime() - startMs
        if (durationMs >= DRAW_DURATION_WARNING_MS) {
            WallpaperDebugLog.w(
                "renderer",
                "slow_draw",
                "renderer" to rendererId,
                "durationMs" to durationMs,
                "current" to sourceLabel(currentSource),
                "next" to sourceLabel(nextSource),
            )
        }
    }

    private fun drawInternal() {
        if (directVideoHandoff) {
            val replacement = nextSource
            if (replacement != null && replacement.hasValidFrame()) {
                WallpaperDebugLog.i(
                    "renderer",
                    "video_handoff_commit",
                    "renderer" to rendererId,
                    "from" to sourceLabel(currentSource),
                    "to" to sourceLabel(replacement),
                )
                currentSource?.onRelease()
                currentSource = replacement
                nextSource = null
                directVideoHandoff = false
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            val active = when {
                currentSource?.hasValidFrame() == true -> currentSource
                replacement?.hasValidFrame() == true -> replacement
                else -> currentSource ?: replacement
            }
            if (active?.hasValidFrame() != true) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            active?.onDrawFrame()
        } else if (isTransitioning) {
            val srcA = currentSource
            val srcB = nextSource
            val srcBReady = srcB?.hasValidFrame() ?: true
            if (!srcBReady) {
                transitionStartTime = SystemClock.elapsedRealtime()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                srcA?.onDrawFrame()
            } else {
                val elapsed = SystemClock.elapsedRealtime() - transitionStartTime
                var progress = elapsed.toFloat() / transitionDuration
                if (progress >= 1f) {
                    progress = 1f
                    isTransitioning = false
                    currentSource?.onRelease()
                    currentSource = nextSource
                    nextSource = null
                }

                if (srcA != null && srcB != null) {
                    initFbos(viewWidth, viewHeight)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
                    GLES20.glViewport(0, 0, viewWidth, viewHeight)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    srcA.onDrawFrame()

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
                    GLES20.glViewport(0, 0, viewWidth, viewHeight)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    srcB.onDrawFrame()

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    drawTransition(fboTextures[0], fboTextures[1], progress)
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, viewWidth, viewHeight)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    srcA?.onDrawFrame() ?: srcB?.onDrawFrame()
                }
            }
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            currentSource?.onDrawFrame()
        }

        val swapSucceeded = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!swapSucceeded) {
            WallpaperDebugLog.e(
                "renderer",
                "egl_swap_failed",
                null,
                "renderer" to rendererId,
                "eglError" to "0x${EGL14.eglGetError().toString(16)}",
            )
        }

        if (!firstFrameReported) {
            val source = firstFrameSource
            if (
                source != null &&
                RenderFrameConfirmationPolicy.canConfirm(
                    swapSucceeded = swapSucceeded,
                    sourceIsCurrent = currentSource === source,
                    sourceHasValidFrame = source.hasValidFrame(),
                    transitionInProgress = isTransitioning,
                    directVideoHandoffInProgress = directVideoHandoff,
                )
            ) {
                firstFrameReported = true
                firstFrameSource = null
                firstFrameTimeoutRunnable?.let(firstFrameHandler::removeCallbacks)
                firstFrameTimeoutRunnable = null
                firstFrameRequestId = null
                val listener = firstFrameListener
                firstFrameListener = null
                firstFrameFailureListener = null
                WallpaperDebugLog.i(
                    "renderer",
                    "first_frame_confirmed_after_takeover_swap",
                    "renderer" to rendererId,
                    "source" to sourceLabel(source),
                )
                listener?.invoke()
            }
        }
    }

    fun drawBitmap(bitmap: Bitmap) {
        val staticSrc = StaticRenderSource(bitmap)
        transitionTo(staticSrc)
        draw()
    }

    fun redrawStatic() = draw()

    fun updateOutputSurface(surface: Surface, width: Int, height: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        WallpaperDebugLog.i(
            "renderer",
            "output_surface_update_start",
            "renderer" to rendererId,
            "viewport" to "${width}x$height",
            "current" to sourceLabel(currentSource),
        )
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) {
            WallpaperDebugLog.w("renderer", "output_surface_update_skipped_no_display", "renderer" to rendererId)
            return
        }

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        viewWidth = width
        viewHeight = height
        val chosenConfigLocal = chosenConfig ?: throw RuntimeException("EGLConfig not initialized")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            chosenConfigLocal,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

        (currentSource as? OesVideoRenderSource)?.updateViewportSize(width, height)
        (nextSource as? OesVideoRenderSource)?.updateViewportSize(width, height)
        (currentSource as? VideoDecoderRenderSource)?.updateViewportSize(width, height)
        (nextSource as? VideoDecoderRenderSource)?.updateViewportSize(width, height)
        currentSource?.updateSize(width, height)
        nextSource?.updateSize(width, height)
        releaseFbos()
        WallpaperDebugLog.i(
            "renderer",
            "output_surface_update_complete",
            "renderer" to rendererId,
            "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun initFbos(width: Int, height: Int) {
        if (width == fboWidth && height == fboHeight) return
        releaseFbos()
        fboWidth = width
        fboHeight = height
        GLES20.glGenFramebuffers(2, fboIds, 0)
        GLES20.glGenTextures(2, fboTextures, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                fboTextures[i],
                0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw RuntimeException("FBO frame buffer setup incomplete: $status")
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun releaseFbos() {
        if (fboWidth > 0) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0)
            GLES20.glDeleteTextures(2, fboTextures, 0)
            fboIds.fill(0)
            fboTextures.fill(0)
            fboWidth = 0
            fboHeight = 0
        }
    }

    private fun initTransitionProgram() {
        val vertexShaderSrc = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fragmentShaderSrc = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTextureA;
            uniform sampler2D uTextureB;
            uniform float uProgress;
            uniform int uTransitionType;
            void main() {
                if (uTransitionType == 0) {
                    vec4 colorA = texture2D(uTextureA, vTexCoord);
                    vec4 colorB = texture2D(uTextureB, vTexCoord);
                    gl_FragColor = mix(colorA, colorB, uProgress);
                } else {
                    float scaleA = 1.0 - 0.12 * uProgress;
                    vec2 uvA = (vTexCoord - 0.5) * scaleA + 0.5;
                    vec4 colorA = texture2D(uTextureA, uvA);
                    float scaleB = 1.0 + 0.12 * (1.0 - uProgress);
                    vec2 uvB = (vTexCoord - 0.5) * scaleB + 0.5;
                    vec4 colorB = texture2D(uTextureB, uvB);
                    gl_FragColor = mix(colorA, colorB, uProgress);
                }
            }
        """.trimIndent()
        transitionProgram = compileProgram(vertexShaderSrc, fragmentShaderSrc)
        transitionAPositionLoc = GLES20.glGetAttribLocation(transitionProgram, "aPosition")
        transitionATexCoordLoc = GLES20.glGetAttribLocation(transitionProgram, "aTexCoord")
        transitionSamplerALoc = GLES20.glGetUniformLocation(transitionProgram, "uTextureA")
        transitionSamplerBLoc = GLES20.glGetUniformLocation(transitionProgram, "uTextureB")
        transitionProgressLoc = GLES20.glGetUniformLocation(transitionProgram, "uProgress")
        transitionTypeLoc = GLES20.glGetUniformLocation(transitionProgram, "uTransitionType")
    }

    private fun drawTransition(texA: Int, texB: Int, progress: Float) {
        GLES20.glUseProgram(transitionProgram)
        transitionVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(transitionAPositionLoc, 2, GLES20.GL_FLOAT, false, 16, transitionVertexBuffer)
        GLES20.glEnableVertexAttribArray(transitionAPositionLoc)
        transitionVertexBuffer.position(2)
        GLES20.glVertexAttribPointer(transitionATexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, transitionVertexBuffer)
        GLES20.glEnableVertexAttribArray(transitionATexCoordLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA)
        GLES20.glUniform1i(transitionSamplerALoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB)
        GLES20.glUniform1i(transitionSamplerBLoc, 1)
        GLES20.glUniform1f(transitionProgressLoc, progress)
        GLES20.glUniform1i(transitionTypeLoc, transitionType)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(transitionAPositionLoc)
        GLES20.glDisableVertexAttribArray(transitionATexCoordLoc)
    }

    private fun sourceLabel(source: RenderSource?): String = when (source) {
        null -> "none"
        is VideoDecoderRenderSource -> "${source.debugId}:${source.debugUri}"
        is OesVideoRenderSource -> "oes@${System.identityHashCode(source).toString(16)}"
        is StaticRenderSource -> "static@${System.identityHashCode(source).toString(16)}"
        else -> "${source.javaClass.simpleName}@${System.identityHashCode(source).toString(16)}"
    }

    fun release() {
        if (released) return
        released = true
        val startedAt = SystemClock.elapsedRealtime()
        WallpaperDebugLog.i(
            "renderer",
            "release_start",
            "renderer" to rendererId,
            "current" to sourceLabel(currentSource),
            "next" to sourceLabel(nextSource),
        )

        cancelFirstFrameWait()
        directVideoHandoff = false
        isTransitioning = false
        try {
            rootSurfaceTexture?.setOnFrameAvailableListener(null)
        } catch (_: Throwable) {
        }

        try {
            releaseFbos()
            currentSource?.onRelease()
            currentSource = null
            nextSource?.onRelease()
            nextSource = null

            try {
                rootSurfaceTexture?.release()
            } catch (_: Throwable) {
            } finally {
                rootSurfaceTexture = null
            }

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }
                if (imageTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(imageTextureId), 0)
                    imageTextureId = 0
                }
                clearProgramCacheForCurrentContext()
                transitionProgram = 0
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Throwable) {
            WallpaperDebugLog.e("renderer", "release_failed", e, "renderer" to rendererId)
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            chosenConfig = null
            EGL14.eglReleaseThread()
        }

        WallpaperDebugLog.i(
            "renderer",
            "release_complete",
            "renderer" to rendererId,
            "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
            "activeDecoders" to activeDecoderSources.get(),
        )
    }

    private fun initEGL(surface: Surface) {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribLists = listOf(
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE,
                ),
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE,
                ),
            )
            var chosenConfigLocal: EGLConfig? = null
            for (attribList in attribLists) {
                if (
                    EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 &&
                    configs[0] != null
                ) {
                    chosenConfigLocal = configs[0]
                    break
                }
            }
            if (chosenConfigLocal == null) throw RuntimeException("eglChooseConfig failed for all fallbacks")
            chosenConfig = chosenConfigLocal
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                chosenConfigLocal,
                EGL14.EGL_NO_CONTEXT,
                ctxAttribs,
                0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                chosenConfigLocal,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        } catch (e: Throwable) {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            throw e
        }
    }

    private fun createExternalOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    class OesVideoRenderSource(
        private val textureId: Int,
        private val surfaceTexture: SurfaceTexture,
    ) : RenderSource {
        @Volatile private var frameAvailable = false
        private var program = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uMvpLoc = 0
        private var uStMatrixLoc = 0
        private val stMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private var viewWidth = 0
        private var viewHeight = 0
        private var videoWidth = 1080
        private var videoHeight = 1920
        private var cropTransform = CropTransform()

        private val vertexBuffer: FloatBuffer = run {
            val data = floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(data).position(0) }
        }

        override fun onInit() {
            val vertexShaderSrc = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uMVP;
                uniform mat4 uSTMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = uMVP * aPosition;
                    vTexCoord = (uSTMatrix * aTexCoord).xy;
                }
            """.trimIndent()
            val fragmentShaderSrc = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                "}"
            program = compileProgram(vertexShaderSrc, fragmentShaderSrc)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
            uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
            updateMvp()
        }

        override fun onDrawFrame() {
            if (program == 0) return
            try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
            } catch (_: Throwable) {
                return
            }
            GLES20.glUseProgram(program)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun markFrameAvailable() { frameAvailable = true }
        override fun hasValidFrame(): Boolean = frameAvailable

        override fun updateSize(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                videoWidth = width
                videoHeight = height
                updateMvp()
            }
        }

        fun updateViewportSize(w: Int, h: Int) {
            viewWidth = w
            viewHeight = h
            updateMvp()
        }

        override fun updateOffset(offsetX: Float, offsetY: Float) {
            cropTransform = CropGeometry.clamp(cropTransform.copy(offsetX = offsetX, offsetY = offsetY))
            updateMvp()
        }

        override fun updateTransform(transform: CropTransform) {
            cropTransform = CropGeometry.clamp(transform)
            updateMvp()
        }

        override fun onRelease() {
            frameAvailable = false
        }

        private fun updateMvp() {
            if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
            val output = MediaTransformPolicy.calculate(
                viewportWidth = viewWidth.toFloat(),
                viewportHeight = viewHeight.toFloat(),
                mediaWidth = videoWidth,
                mediaHeight = videoHeight,
                transform = cropTransform,
            )
            Matrix.setIdentityM(mvpMatrix, 0)
            Matrix.scaleM(mvpMatrix, 0, output.rendererScaleX, output.rendererScaleY, 1f)
            Matrix.translateM(
                mvpMatrix,
                0,
                output.translationX / output.rendererScaleX,
                output.translationY / output.rendererScaleY,
                0f,
            )
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    class VideoDecoderRenderSource(
        private val context: Context,
        private val videoUri: android.net.Uri,
        private val onFrameReady: () -> Unit,
    ) : RenderSource {
        internal val debugId = WallpaperDebugLog.nextId("decoder")
        internal val debugUri = WallpaperDebugLog.uri(videoUri)
        private val createdAtMs = SystemClock.elapsedRealtime()
        private var frameCount = 0L
        private var lastFrameCallbackAtMs = 0L
        @Volatile private var player: ExoPlayer? = null
        @Volatile private var playerHandler: Handler? = null
        @Volatile private var lastPlaybackState: Int? = null
        @Volatile private var lastIsPlaying = false
        @Volatile private var isReleased = false
        @Volatile private var playWhenReady = true
        @Volatile private var frameAvailable = false
        private var surface: Surface? = null
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uMvpLoc = 0
        private var uStMatrixLoc = 0
        private var uSamplerLoc = 0
        private val stMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private var viewWidth = 0
        private var viewHeight = 0
        private var videoWidth = 1080
        private var videoHeight = 1920
        private var cropTransform = CropTransform()
        private var activeCountRegistered = false

        private val vertexBuffer: FloatBuffer = run {
            val data = floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(data).position(0) }
        }

        override fun onInit() {
            WallpaperDebugLog.i(
                "decoder",
                "source_init_start",
                "decoder" to debugId,
                "uri" to debugUri,
                "thread" to Thread.currentThread().name,
            )
            if (!activeCountRegistered) {
                activeCountRegistered = true
                WallpaperDebugLog.i(
                    "decoder",
                    "active_source_increment",
                    "decoder" to debugId,
                    "uri" to debugUri,
                    "activeDecoders" to activeDecoderSources.incrementAndGet(),
                )
            }

            val vertexShaderSrc = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uMVP;
                uniform mat4 uSTMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = uMVP * aPosition;
                    vTexCoord = (uSTMatrix * aTexCoord).xy;
                }
            """.trimIndent()
            val fragmentShaderSrc = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                "}"
            program = compileProgram(vertexShaderSrc, fragmentShaderSrc)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
            uStMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uSamplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val renderLooper = Looper.myLooper() ?: Looper.getMainLooper()
            val renderHandler = Handler(renderLooper)
            val decoderHandler = media3ApplicationHandler
            playerHandler = decoderHandler

            val st = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(videoWidth, videoHeight)
                setOnFrameAvailableListener({
                    // SurfaceTexture.apply changes the implicit receiver; qualify the decoder's
                    // lifecycle flag so API 24/25 never resolves SurfaceTexture.isReleased (API 26).
                    if (this@VideoDecoderRenderSource.isReleased) return@setOnFrameAvailableListener
                    val now = SystemClock.elapsedRealtime()
                    val gapMs = if (lastFrameCallbackAtMs == 0L) 0L else now - lastFrameCallbackAtMs
                    lastFrameCallbackAtMs = now
                    frameCount++
                    if (frameCount == 1L) {
                        WallpaperDebugLog.i(
                            "decoder",
                            "surface_first_frame_callback",
                            "decoder" to debugId,
                            "uri" to debugUri,
                            "elapsedMs" to now - createdAtMs,
                        )
                    } else if (gapMs >= VIDEO_FRAME_GAP_WARNING_MS && playWhenReady) {
                        WallpaperDebugLog.w(
                            "decoder",
                            "video_frame_gap",
                            "decoder" to debugId,
                            "uri" to debugUri,
                            "gapMs" to gapMs,
                            "frameCount" to frameCount,
                            "playerState" to playerStateLabel(lastPlaybackState),
                            "isPlaying" to lastIsPlaying,
                        )
                    }
                    frameAvailable = true
                    onFrameReady()
                }, renderHandler)
            }
            surfaceTexture = st
            val surf = Surface(st)
            surface = surf
            synchronized(this) { isReleased = false }

            val initRunnable = Runnable {
                var unownedPlayer: ExoPlayer? = null
                try {
                    synchronized(this) {
                        if (isReleased) return@synchronized
                        val trackSelector = DefaultTrackSelector(context).apply {
                            setParameters(
                                buildUponParameters().setTrackTypeDisabled(
                                    androidx.media3.common.C.TRACK_TYPE_AUDIO,
                                    true,
                                )
                            )
                        }
                        val loadControl = DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1000, 5000, 250, 500)
                            .build()
                        // Wallpaper playback favors deterministic teardown over MediaCodec async queueing.
                        // On the tested vivo/Android 16 path, asynchronous codec callbacks can outlive
                        // the preview decoder teardown and target a dead MediaCodec EventHandler.
                        val renderersFactory = DefaultRenderersFactory(context)
                            .forceDisableMediaCodecAsynchronousQueueing()
                        val exo = ExoPlayer.Builder(context, renderersFactory)
                            .setTrackSelector(trackSelector)
                            .setLoadControl(loadControl)
                            .setLooper(decoderHandler.looper)
                            .setPlaybackLooper(media3PlaybackHandler.looper)
                            .build()
                        unownedPlayer = exo
                        lastPlaybackState = Player.STATE_IDLE
                        lastIsPlaying = false
                        exo.addListener(object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                WallpaperDebugLog.e(
                                    "decoder",
                                    "player_error",
                                    error,
                                    "decoder" to debugId,
                                    "uri" to debugUri,
                                    "code" to error.errorCodeName,
                                    "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
                                )
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                lastPlaybackState = playbackState
                                WallpaperDebugLog.i(
                                    "decoder",
                                    "playback_state",
                                    "decoder" to debugId,
                                    "uri" to debugUri,
                                    "state" to playerStateLabel(playbackState),
                                    "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
                                )
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                lastIsPlaying = isPlaying
                            }

                            override fun onRenderedFirstFrame() {
                                WallpaperDebugLog.i(
                                    "decoder",
                                    "exo_rendered_first_frame",
                                    "decoder" to debugId,
                                    "uri" to debugUri,
                                    "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
                                )
                            }

                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                if (isReleased) return
                                val w = videoSize.width
                                val h = videoSize.height
                                if (w > 0 && h > 0) {
                                    val posted = renderHandler.post {
                                        if (!isReleased) updateSize(w, h)
                                    }
                                    if (!posted && !isReleased) {
                                        WallpaperDebugLog.w(
                                            "decoder",
                                            "video_size_render_post_failed",
                                            "decoder" to debugId,
                                            "uri" to debugUri,
                                        )
                                    }
                                }
                            }
                        })
                        exo.setVideoSurface(surf)
                        exo.setMediaItem(MediaItem.fromUri(PrivateFileUriHelper.safeUri(context, videoUri)))
                        exo.repeatMode = Player.REPEAT_MODE_ALL
                        exo.volume = 0f
                        exo.playWhenReady = playWhenReady
                        exo.prepare()
                        player = exo
                        unownedPlayer = null
                        WallpaperDebugLog.i(
                            "decoder",
                            "prepare_submitted",
                            "decoder" to debugId,
                            "uri" to debugUri,
                            "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs,
                            "playWhenReady" to playWhenReady,
                        )
                    }
                } catch (e: Throwable) {
                    try {
                        unownedPlayer?.release()
                    } catch (_: Throwable) {
                    }
                    WallpaperDebugLog.e(
                        "decoder",
                        "player_init_failed",
                        e,
                        "decoder" to debugId,
                        "uri" to debugUri,
                    )
                }
            }

            if (!decoderHandler.post(initRunnable)) {
                WallpaperDebugLog.e(
                    "decoder",
                    "player_init_post_failed",
                    null,
                    "decoder" to debugId,
                    "uri" to debugUri,
                )
            } else {
                WallpaperDebugLog.d(
                    "decoder",
                    "player_init_posted",
                    "decoder" to debugId,
                    "uri" to debugUri,
                    "decoderThread" to decoderHandler.looper.thread.name,
                    "playbackThread" to media3PlaybackHandler.looper.thread.name,
                )
            }
            updateMvp()
        }

        override fun onDrawFrame() {
            if (isReleased) return
            val st = surfaceTexture ?: return
            if (program == 0) return
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            } catch (e: Throwable) {
                if (!isReleased) {
                    WallpaperDebugLog.e(
                        "decoder",
                        "update_tex_image_failed",
                        e,
                        "decoder" to debugId,
                        "uri" to debugUri,
                        "textureId" to textureId,
                    )
                }
                return
            }
            GLES20.glUseProgram(program)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glUniform1i(uSamplerLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        override fun hasValidFrame(): Boolean = !isReleased && frameAvailable

        fun pause() {
            playWhenReady = false
            postPlayerCommand("pause") { it.playWhenReady = false }
        }

        fun resume() {
            playWhenReady = true
            postPlayerCommand("resume") { it.playWhenReady = true }
        }

        private fun postPlayerCommand(name: String, action: (ExoPlayer) -> Unit) {
            if (isReleased) return
            val handler = playerHandler ?: return
            val command = Runnable {
                if (isReleased) return@Runnable
                val p = player ?: return@Runnable
                try {
                    action(p)
                } catch (e: Throwable) {
                    WallpaperDebugLog.e(
                        "decoder",
                        "player_command_failed",
                        e,
                        "decoder" to debugId,
                        "uri" to debugUri,
                        "command" to name,
                    )
                }
            }
            if (Looper.myLooper() == handler.looper) {
                command.run()
            } else if (!handler.post(command)) {
                WallpaperDebugLog.w(
                    "decoder",
                    "player_command_post_failed",
                    "decoder" to debugId,
                    "uri" to debugUri,
                    "command" to name,
                )
            }
        }

        override fun updateSize(width: Int, height: Int) {
            if (isReleased) return
            if (width > 0 && height > 0) {
                videoWidth = width
                videoHeight = height
                surfaceTexture?.setDefaultBufferSize(width, height)
                updateMvp()
            }
        }

        fun updateViewportSize(w: Int, h: Int) {
            viewWidth = w
            viewHeight = h
            updateMvp()
        }

        override fun updateOffset(offsetX: Float, offsetY: Float) {
            cropTransform = CropGeometry.clamp(cropTransform.copy(offsetX = offsetX, offsetY = offsetY))
            updateMvp()
        }

        override fun updateTransform(transform: CropTransform) {
            cropTransform = CropGeometry.clamp(transform)
            updateMvp()
        }

        override fun onRelease() {
            val releaseRequestedAt = SystemClock.elapsedRealtime()
            val p: ExoPlayer?
            val handler: Handler?
            val surf: Surface?
            val st: SurfaceTexture?
            synchronized(this) {
                if (isReleased && player == null && surface == null && surfaceTexture == null) {
                    unregisterActiveSourceIfNeeded()
                    return
                }
                isReleased = true
                p = player
                player = null
                handler = playerHandler
                playerHandler = null
                surf = surface
                surface = null
                st = surfaceTexture
                surfaceTexture = null
                frameAvailable = false
                lastPlaybackState = null
                lastIsPlaying = false
                try {
                    st?.setOnFrameAvailableListener(null)
                } catch (_: Throwable) {
                }
            }

            WallpaperDebugLog.i(
                "decoder",
                "release_requested",
                "decoder" to debugId,
                "uri" to debugUri,
                "frameCount" to frameCount,
            )

            var releaseCompleted = p == null
            if (p != null) {
                val releaseHandler = handler ?: media3ApplicationHandler
                val latch = CountDownLatch(1)
                val releaseTask = Runnable {
                    try {
                        p.clearVideoSurface()
                        p.release()
                    } catch (e: Throwable) {
                        WallpaperDebugLog.e(
                            "decoder",
                            "player_release_failed",
                            e,
                            "decoder" to debugId,
                            "uri" to debugUri,
                        )
                    } finally {
                        latch.countDown()
                    }
                }
                if (Looper.myLooper() == releaseHandler.looper) {
                    releaseTask.run()
                    releaseCompleted = true
                } else if (releaseHandler.post(releaseTask)) {
                    releaseCompleted = try {
                        latch.await(PLAYER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                } else {
                    WallpaperDebugLog.e(
                        "decoder",
                        "release_post_failed_shared_looper",
                        null,
                        "decoder" to debugId,
                        "uri" to debugUri,
                    )
                }
                if (!releaseCompleted) {
                    WallpaperDebugLog.w(
                        "decoder",
                        "player_release_timeout",
                        "decoder" to debugId,
                        "uri" to debugUri,
                        "timeoutMs" to PLAYER_RELEASE_TIMEOUT_MS,
                    )
                }
            }

            try {
                surf?.release()
            } catch (_: Throwable) {
            }
            try {
                st?.release()
            } catch (_: Throwable) {
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            val active = unregisterActiveSourceIfNeeded()
            WallpaperDebugLog.i(
                "decoder",
                "release_complete",
                "decoder" to debugId,
                "uri" to debugUri,
                "elapsedMs" to SystemClock.elapsedRealtime() - releaseRequestedAt,
                "playerReleaseCompleted" to releaseCompleted,
                "activeDecoders" to active,
            )
        }

        private fun unregisterActiveSourceIfNeeded(): Int {
            if (!activeCountRegistered) return activeDecoderSources.get()
            activeCountRegistered = false
            return activeDecoderSources.decrementAndGet().coerceAtLeast(0)
        }

        private fun updateMvp() {
            if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
            val output = MediaTransformPolicy.calculate(
                viewportWidth = viewWidth.toFloat(),
                viewportHeight = viewHeight.toFloat(),
                mediaWidth = videoWidth,
                mediaHeight = videoHeight,
                transform = cropTransform,
            )
            Matrix.setIdentityM(mvpMatrix, 0)
            Matrix.scaleM(mvpMatrix, 0, output.rendererScaleX, output.rendererScaleY, 1f)
            Matrix.translateM(
                mvpMatrix,
                0,
                output.translationX / output.rendererScaleX,
                output.translationY / output.rendererScaleY,
                0f,
            )
        }
    }

    class StaticRenderSource(bitmap: Bitmap) : RenderSource {
        private val mediaWidth = bitmap.width
        private val mediaHeight = bitmap.height
        private var uploadBitmap: Bitmap? = bitmap
        private var textureReady = false
        private var textureId = 0
        private var program = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uMvpLoc = 0
        private var uSamplerLoc = 0
        private val mvpMatrix = FloatArray(16)
        private var viewWidth = 0
        private var viewHeight = 0
        private var cropTransform = CropTransform()

        private val vertexBuffer: FloatBuffer = run {
            val data = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
            )
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(data).position(0) }
        }

        override fun onInit() {
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
            program = compileProgram(vertexShaderSrc, fragmentShaderSrc)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
            uSamplerLoc = GLES20.glGetUniformLocation(program, "sTexture")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val bitmap = uploadBitmap
            try {
                if (bitmap != null && !bitmap.isRecycled) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    textureReady = true
                }
            } finally {
                uploadBitmap = null
            }
            updateMvp()
        }

        override fun onDrawFrame() {
            if (textureId == 0 || program == 0 || !textureReady) return
            GLES20.glUseProgram(program)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uSamplerLoc, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        override fun hasValidFrame(): Boolean = textureReady

        override fun updateSize(width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            updateMvp()
        }

        override fun updateOffset(offsetX: Float, offsetY: Float) {
            cropTransform = CropGeometry.clamp(cropTransform.copy(offsetX = offsetX, offsetY = offsetY))
            updateMvp()
        }

        override fun updateTransform(transform: CropTransform) {
            cropTransform = CropGeometry.clamp(transform)
            updateMvp()
        }

        override fun onRelease() {
            uploadBitmap = null
            textureReady = false
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
        }

        private fun updateMvp() {
            if (mediaWidth <= 0 || mediaHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
            val output = MediaTransformPolicy.calculate(
                viewportWidth = viewWidth.toFloat(),
                viewportHeight = viewHeight.toFloat(),
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                transform = cropTransform,
            )
            Matrix.setIdentityM(mvpMatrix, 0)
            Matrix.scaleM(mvpMatrix, 0, output.rendererScaleX, output.rendererScaleY, 1f)
            Matrix.translateM(
                mvpMatrix,
                0,
                output.translationX / output.rendererScaleX,
                output.translationY / output.rendererScaleY,
                0f,
            )
        }
    }

    companion object {
        private const val DRAW_GAP_WARNING_MS = 250L
        private const val DRAW_DURATION_WARNING_MS = 32L
        private const val VIDEO_FRAME_GAP_WARNING_MS = 500L
        private const val PLAYER_RELEASE_TIMEOUT_MS = 2_000L

        /**
         * Media3's application looper must outlive every individual player. Quitting a per-source
         * HandlerThread after release is unsafe because renderer/analytics tail callbacks can still
         * target the application looper. This one thread intentionally lives for the app process.
         */
        private val media3ApplicationThread: HandlerThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HandlerThread("wallpaper-media3-application").apply { start() }
        }
        private val media3ApplicationHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Handler(media3ApplicationThread.looper)
        }

        /**
         * Media3 1.10.x builds video VSync sampling from the playback looper. When no playback
         * looper is supplied, Media3 owns a per-player playback HandlerThread and stops it during
         * release. On the tested vivo / Android 16 path, a late platform VSYNC can then target that
         * thread's Choreographer after the looper has died.
         *
         * Keep playback on a process-lifetime thread owned by WallpaperHub. It must stay separate
         * from the application looper because player.release() is issued on the application looper
         * and synchronously waits for playback-thread release work to complete.
         */
        private val media3PlaybackThread: HandlerThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HandlerThread("wallpaper-media3-playback", Process.THREAD_PRIORITY_AUDIO).apply { start() }
        }
        private val media3PlaybackHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Handler(media3PlaybackThread.looper)
        }

        private val activeDecoderSources = AtomicInteger(0)

        /** Programs are cached only inside one concrete native EGL context. */
        private val programCache = ThreadLocal<MutableMap<Long, MutableMap<String, Int>>>()

        private fun currentContextKey(): Long {
            val context = EGL14.eglGetCurrentContext()
            if (context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("No current EGL context while compiling GL program")
            }
            return context.nativeHandle
        }

        private fun clearProgramCacheForCurrentContext() {
            val context = EGL14.eglGetCurrentContext()
            if (context == EGL14.EGL_NO_CONTEXT) return
            val perThread = programCache.get() ?: return
            val programs = perThread.remove(context.nativeHandle) ?: return
            programs.values.distinct().forEach { program ->
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            if (perThread.isEmpty()) programCache.remove()
        }

        private fun playerStateLabel(state: Int?): String = when (state) {
            null -> "NO_PLAYER"
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }

        fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
            var perThread = programCache.get()
            if (perThread == null) {
                perThread = mutableMapOf()
                programCache.set(perThread)
            }
            val contextCache = perThread.getOrPut(currentContextKey()) { mutableMapOf() }
            val key = vertexSrc + '\u0000' + fragmentSrc
            contextCache[key]?.takeIf { it != 0 }?.let { return it }

            val v = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v)
            GLES20.glAttachShader(program, f)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(v)
                GLES20.glDeleteShader(f)
                throw RuntimeException("GL program linking failed: $log")
            }
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            contextCache[key] = program
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compilation failed (type=$type): $log")
            }
            return shader
        }
    }
}
