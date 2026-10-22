package com.wallpaperhub.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import android.app.WallpaperColors
import android.app.WallpaperManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.data.repo.PlaybackRepository
import com.wallpaperhub.playback.model.PlaybackCommand
import com.wallpaperhub.playback.model.PlaybackCommandType
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.model.PlaybackMode as QueuePlaybackMode
import com.wallpaperhub.playback.engine.PlaybackConfirmationPolicy
import com.wallpaperhub.playback.engine.QueueController
import com.wallpaperhub.playback.engine.RotationTimerState
import com.wallpaperhub.playback.engine.ServiceApplyRecoveryPolicy
import com.wallpaperhub.playback.engine.SystemPreviewPolicy
import com.wallpaperhub.playback.engine.WallpaperVisibilityPolicy
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.playback.telemetry.PlaybackTrace
import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

/**
 * 视频与多类型动态壁纸服务。
 *
 * 作用：
 *   1. 自建渲染线程建立 EGL 环境，配合 GLVideoRenderer 进行渲染与过渡切换；
 *   2. 支持视频、静态图片、Shader 以及 3D 视差壁纸；
 *   3. 注册旋转矢量传感器以实现 3D 景深移动（仅在可见时注册）；
 *   4. 支持 Android Monet 引擎动态提取壁纸主色调反馈给系统；
 *   5. 监听省电模式广播，自动挂起高性能解码与高帧率刷新以节省电量。
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreate() {
        super.onCreate()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, android.hardware.SensorEventListener {
        private val engineId = WallpaperDebugLog.nextId("engine")
        private val engineCreatedAtMs = SystemClock.elapsedRealtime()
        private var player: ExoPlayer? = null
        private var playerListener: Player.Listener? = null
        @Volatile private var visible = false

        private var renderer: GLVideoRenderer? = null
        private var videoSurface: Surface? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var playlistQueue: List<PlaylistEntry> = emptyList()
        private var currentIndex = 0
        private var settings = VideoWallpaperSettings.read(this@VideoWallpaperService)
        private var rotateRunnable: Runnable? = null
        private var hiddenCatchUpRunnable: Runnable? = null
        private var hiddenVideoPauseRunnable: Runnable? = null
        private val rotationTimer = RotationTimerState()
        private var suppressNextMediaTransition = false
        private var playGeneration = 0
        private var queueController: QueueController? = null
        @Volatile private var lastPlaylistRevision = 0L
        private var loadJob: kotlinx.coroutines.Job? = null
        private val videoMetaCache = mutableMapOf<String, VideoMeta>()

        private var consecutiveFailures = 0
        private var lastTransitionTime = 0L
        private val renderThread = HandlerThread("video-wallpaper-gl").apply { start() }
        private val renderHandler = Handler(renderThread.looper)
        @Volatile private var engineDestroyed = false
        private var renderHeartbeatExpectedAtMs = 0L
        private val renderHeartbeat = object : Runnable {
            override fun run() {
                if (engineDestroyed || !WallpaperDebugLog.enabled) return
                val now = SystemClock.elapsedRealtime()
                val lagMs = if (renderHeartbeatExpectedAtMs == 0L) 0L else now - renderHeartbeatExpectedAtMs
                if (lagMs >= RENDER_THREAD_LAG_WARNING_MS) {
                    WallpaperDebugLog.w(
                        "engine",
                        "render_thread_lag",
                        "engine" to engineId,
                        "lagMs" to lagMs,
                        "visible" to visible,
                        "screenOn" to isScreenOn,
                        "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                        "pendingFrame" to WallpaperDebugLog.uri(pendingFrameUri),
                        "pendingCommand" to WallpaperDebugLog.command(pendingCommandId),
                    )
                }
                renderHeartbeatExpectedAtMs = now + RENDER_HEARTBEAT_INTERVAL_MS
                renderHandler.postDelayed(this, RENDER_HEARTBEAT_INTERVAL_MS)
            }
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val playbackRepository = PlaybackRepository.getInstance(this@VideoWallpaperService)
        private val staticImagePreloader = StaticImagePreloader(this@VideoWallpaperService)
        private var lastCommandId: String? = null
        private var pendingCommandId: String? = null
        private var pendingCommandUri: String? = null
        private var pendingCommandRevision: Long = 0L
        /** URI whose first visible frame is still pending; rotation must wait for it too. */
        private var pendingFrameUri: String? = null
        private var previewSurfaceChangedAtMs = 0L
        private var isScreenOn = true

        // 动态主题颜色缓冲
        private var cachedWallpaperColors: WallpaperColors? = null
        private val monetColorCache = android.util.LruCache<String, WallpaperColors>(20)

        // 是否处于省电模式状态
        private var isPowerSaveModeActive = false

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        WallpaperDebugLog.i(
                            "engine",
                            "screen_event",
                            "engine" to engineId,
                            "action" to intent.action,
                            "visible" to visible,
                        )
                        renderHandler.post {
                            isScreenOn = true
                            if (!visible) {
                                scheduleHiddenCatchUpLocked("screen_on_hidden")
                                return@post
                            }
                            cancelHiddenCatchUpLocked()
                            if (catchUpRotationBeforeResumeLocked("screen_on")) {
                                return@post
                            }
                            (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
                            (renderer?.nextSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
                            registerSensor()
                            startContinuousRedrawIfNeeded()
                            resumeRotateLocked()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        WallpaperDebugLog.i(
                            "engine",
                            "screen_event",
                            "engine" to engineId,
                            "action" to intent.action,
                            "visible" to visible,
                        )
                        renderHandler.post {
                            isScreenOn = false
                            // Freeze real rendering immediately, but keep the already-earned logical
                            // deadline alive. Some OEMs leave Engine.visible=true across SCREEN_OFF,
                            // therefore hidden catch-up is keyed to actual visibility (visible && screenOn).
                            pauseVideoSourcesLocked()
                            unregisterSensor()
                            android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
                            pauseRotateLocked("screen_off")
                        }
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        val powerSave = pm.isPowerSaveMode
                        renderHandler.post {
                            if (isPowerSaveModeActive != powerSave) {
                                WallpaperDebugLog.i(
                                    "engine",
                                    "power_save_changed",
                                    "engine" to engineId,
                                    "enabled" to powerSave,
                                )
                                isPowerSaveModeActive = powerSave
                                if (powerSave) cancelHiddenCatchUpLocked()
                                applyPowerSaveStateLocked()
                                if (!powerSave && !WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)) {
                                    scheduleHiddenCatchUpLocked("power_save_exit_hidden")
                                }
                            }
                        }
                    }
                }
            }
        }

        private val gestureDetector = GestureDetector(
            this@VideoWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isPreview) return false
                    Log.d(TAG, "Double tap detected via GestureDetector")
                    renderHandler.post {
                        advanceToNextLocked()
                    }
                    return true
                }
            }
        )

        private var lastTapTime = 0L
        private var lastRedrawCallbackAtMs = 0L

        // 处理 Shader/Parallax 动画连续渲染的 Runnable
        private val redrawRunnable = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val callbackAtMs = frameTimeNanos / 1_000_000L
                val gapMs = if (lastRedrawCallbackAtMs == 0L) 0L else callbackAtMs - lastRedrawCallbackAtMs
                lastRedrawCallbackAtMs = callbackAtMs
                if (gapMs >= REDRAW_CALLBACK_GAP_WARNING_MS &&
                    WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)
                ) {
                    WallpaperDebugLog.w(
                        "engine",
                        "redraw_callback_gap",
                        "engine" to engineId,
                        "gapMs" to gapMs,
                        "awaitingFirstFrame" to (renderer?.isAwaitingFirstFrame == true),
                        "videoHandoff" to (renderer?.isWaitingForVideoHandoff == true),
                        "transitioning" to (renderer?.isTransitioning == true),
                    )
                }
                if (WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                        visible = visible,
                        screenOn = isScreenOn,
                        powerSave = isPowerSaveModeActive,
                    )
                ) {
                    val current = renderer?.currentSource
                    val next = renderer?.nextSource
                    val isTransitioning = renderer?.isTransitioning == true

                    val needsRedraw = isTransitioning ||
                            renderer?.isWaitingForVideoHandoff == true ||
                            renderer?.isAwaitingFirstFrame == true ||
                            current is ShaderRenderSource ||
                            next is ShaderRenderSource ||
                            current is MultiLayerParallaxSource ||
                            next is MultiLayerParallaxSource

                    if (needsRedraw) {
                        renderer?.draw()
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
        }

        private fun startContinuousRedrawIfNeeded() {
            android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
            lastRedrawCallbackAtMs = 0L
            if (!WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                    visible = visible,
                    screenOn = isScreenOn,
                    powerSave = isPowerSaveModeActive,
                )
            ) {
                return
            }
            renderer?.draw()
            android.view.Choreographer.getInstance().postFrameCallback(redrawRunnable)
        }

        private fun registerSensor() {
            if (!WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                    visible = visible,
                    screenOn = isScreenOn,
                    powerSave = isPowerSaveModeActive,
                )
            ) return
            val current = renderer?.currentSource
            val next = renderer?.nextSource
            val needsSensor = current is MultiLayerParallaxSource || next is MultiLayerParallaxSource
            if (needsSensor) {
                val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
                val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
                if (sensor != null) {
                    sm.registerListener(this@VideoEngine, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
                }
            }
        }

        private fun unregisterSensor() {
            val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            sm.unregisterListener(this@VideoEngine)
        }

        private fun applyPowerSaveStateLocked() {
            if (isPowerSaveModeActive) {
                Log.d(TAG, "省电模式开启：挂起高刷与视频解码")
                unregisterSensor()
                android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)

                // 挂起解码器
                pauseVideoSourcesLocked()

                // 重绘一次以确保展示静态帧
                renderer?.draw()
            } else {
                Log.d(TAG, "省电模式关闭：恢复正常渲染")
                if (WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)) {
                    resumeVideoSourcesLocked()
                    registerSensor()
                    startContinuousRedrawIfNeeded()
                }
            }
        }

        private fun cancelDeferredVideoPauseLocked() {
            hiddenVideoPauseRunnable?.let { renderHandler.removeCallbacks(it) }
            hiddenVideoPauseRunnable = null
        }

        private fun pauseVideoSourcesLocked() {
            cancelDeferredVideoPauseLocked()
            (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.pause()
            (renderer?.nextSource as? GLVideoRenderer.VideoDecoderRenderSource)?.pause()
        }

        private fun scheduleVideoPauseLocked(reason: String) {
            cancelDeferredVideoPauseLocked()
            if (!WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                    visible = visible,
                    screenOn = isScreenOn,
                    powerSave = isPowerSaveModeActive,
                    isPreview = isPreview,
                )
            ) {
                pauseVideoSourcesLocked()
                return
            }
            lateinit var task: Runnable
            task = Runnable {
                if (hiddenVideoPauseRunnable !== task) return@Runnable
                hiddenVideoPauseRunnable = null
                if (!WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                        visible = visible,
                        screenOn = isScreenOn,
                        powerSave = isPowerSaveModeActive,
                        isPreview = isPreview,
                    )
                ) return@Runnable
                WallpaperDebugLog.d(
                    "engine",
                    "hidden_video_pause_grace_expired",
                    "engine" to engineId,
                    "reason" to reason,
                    "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                    "graceMs" to HIDDEN_VIDEO_PAUSE_GRACE_MS,
                )
                (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.pause()
                (renderer?.nextSource as? GLVideoRenderer.VideoDecoderRenderSource)?.pause()
            }
            hiddenVideoPauseRunnable = task
            WallpaperDebugLog.d(
                "engine",
                "hidden_video_pause_deferred",
                "engine" to engineId,
                "reason" to reason,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "graceMs" to HIDDEN_VIDEO_PAUSE_GRACE_MS,
            )
            renderHandler.postDelayed(task, HIDDEN_VIDEO_PAUSE_GRACE_MS)
        }

        private fun resumeVideoSourcesLocked() {
            cancelDeferredVideoPauseLocked()
            if (WallpaperVisibilityPolicy.shouldPauseVideoDecoder(isScreenOn, isPowerSaveModeActive)) return
            (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
            (renderer?.nextSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
            renderer?.draw()
            // Some OEMs reattach the decoder producer shortly after the wallpaper becomes visible.
            // A short retry window prevents the EGL surface from staying black after returning home.
            listOf(120L, 360L).forEach { delayMs ->
                renderHandler.postDelayed({
                    if (WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn) &&
                        !WallpaperVisibilityPolicy.shouldPauseVideoDecoder(isScreenOn, isPowerSaveModeActive)
                    ) {
                        (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
                        (renderer?.nextSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
                        renderer?.draw()
                    }
                }, delayMs)
            }
        }

        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR) {
                renderHandler.post {
                    renderer?.currentSource?.updateSensor(event.values)
                    renderer?.nextSource?.updateSensor(event.values)
                    val current = renderer?.currentSource
                    val next = renderer?.nextSource
                    if ((current is MultiLayerParallaxSource || next is MultiLayerParallaxSource) &&
                        WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                            visible = visible,
                            screenOn = isScreenOn,
                            powerSave = isPowerSaveModeActive,
                        )
                    ) {
                        renderer?.draw()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return cachedWallpaperColors ?: super.onComputeColors()
            }
            return null
        }

        private fun updateMonetColors(entry: PlaylistEntry) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val uriKey = entry.uri.toString()
                val cached = monetColorCache.get(uriKey)
                if (cached != null) {
                    cachedWallpaperColors = cached
                    notifyColorsChanged()
                    return
                }
                scope.launch {
                    val colors = try {
                        val uriStr = entry.uri.toString()
                        if (uriStr.endsWith(".frag") || uriStr.endsWith(".glsl") || uriStr.contains("shader")) {
                            WallpaperColors.fromBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        } else if (uriStr.endsWith(".json") || uriStr.contains("parallax")) {
                            val targetW = surfaceWidth.coerceAtLeast(108)
                            val targetH = surfaceHeight.coerceAtLeast(192)
                            val fallbackBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(fallbackBmp)
                            canvas.drawColor(android.graphics.Color.parseColor("#0d1b2a"))
                            val c = WallpaperColors.fromBitmap(fallbackBmp)
                            fallbackBmp.recycle()
                            c
                        } else if (entry.type == WallpaperType.STATIC) {
                            val targetW = surfaceWidth.coerceAtLeast(108)
                            val targetH = surfaceHeight.coerceAtLeast(192)
                            decodeBitmap(entry.uri, targetW, targetH)?.let { bmp ->
                                val c = WallpaperColors.fromBitmap(bmp)
                                bmp.recycle()
                                c
                            }
                        } else {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(this@VideoWallpaperService, com.wallpaperhub.wallpaper.PrivateFileUriHelper.safeUri(this@VideoWallpaperService, entry.uri))
                            val frame = retriever.frameAtTime
                            val c = frame?.let { WallpaperColors.fromBitmap(it) }
                            frame?.recycle()
                            retriever.release()
                            c
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (colors != null) {
                        monetColorCache.put(uriKey, colors)
                    }
                    renderHandler.post {
                        cachedWallpaperColors = colors
                        notifyColorsChanged()
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event != null && !isPreview) {
                gestureDetector.onTouchEvent(event)
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (!isPreview && action == WallpaperManager.COMMAND_TAP) {
                val now = SystemClock.elapsedRealtime()
                val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
                if (now - lastTapTime < doubleTapTimeout) {
                    Log.d(TAG, "Double tap detected via onCommand")
                    renderHandler.post {
                        advanceToNextLocked()
                    }
                    lastTapTime = 0L
                } else {
                    lastTapTime = now
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            WallpaperDebugLog.i(
                "engine",
                "create",
                "engine" to engineId,
                "preview" to isPreview,
                "surfaceValid" to surfaceHolder.surface.isValid,
                "serviceTarget" to WallpaperDebugLog.uri(VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)),
            )
            if (WallpaperDebugLog.enabled) {
                renderHeartbeatExpectedAtMs = SystemClock.elapsedRealtime() + RENDER_HEARTBEAT_INTERVAL_MS
                renderHandler.postDelayed(renderHeartbeat, RENDER_HEARTBEAT_INTERVAL_MS)
            }
            setTouchEventsEnabled(!isPreview)
            prefs().registerOnSharedPreferenceChangeListener(this)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            isPowerSaveModeActive = pm.isPowerSaveMode

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this@VideoWallpaperService.registerReceiver(
                    screenReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                this@VideoWallpaperService.registerReceiver(screenReceiver, filter)
            }

            if (!SystemPreviewPolicy.shouldObserveRuntime(isPreview)) {
                WallpaperDebugLog.i(
                    "engine",
                    "system_preview_fast_path_enabled",
                    "engine" to engineId,
                    "target" to WallpaperDebugLog.uri(VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)),
                )
                return
            }

            scope.launch {
                val restoredSession = playbackRepository.getSession()
                WallpaperDebugLog.i(
                    "engine",
                    "restored_session",
                    "engine" to engineId,
                    "desired" to WallpaperDebugLog.uri(restoredSession.desiredUri),
                    "applied" to WallpaperDebugLog.uri(restoredSession.appliedUri),
                    "command" to WallpaperDebugLog.command(restoredSession.commandId),
                    "phase" to restoredSession.phase,
                    "revision" to restoredSession.queueRevision,
                )
                lastCommandId = restoredSession.commandId.takeIf {
                    restoredSession.phase == PlaybackPhase.APPLIED || restoredSession.phase == PlaybackPhase.FAILED
                }
                playbackRepository.observeLatestCommand().collect { command ->
                    if (command != null && command.id != lastCommandId) {
                        WallpaperDebugLog.i(
                            "engine",
                            "command_observed",
                            "engine" to engineId,
                            "command" to WallpaperDebugLog.command(command.id),
                            "type" to command.type,
                            "target" to WallpaperDebugLog.uri(command.targetUri),
                            "revision" to command.playlistRevision,
                            "source" to command.source,
                            "previousHandled" to WallpaperDebugLog.command(lastCommandId),
                        )
                        if (playbackRepository.latestCommand()?.id != command.id) return@collect
                        val session = playbackRepository.getSession()
                        if (session.commandId == command.id &&
                            (session.phase == PlaybackPhase.APPLIED || session.phase == PlaybackPhase.FAILED)
                        ) {
                            WallpaperDebugLog.d(
                                "engine",
                                "command_skip_terminal",
                                "engine" to engineId,
                                "command" to WallpaperDebugLog.command(command.id),
                                "phase" to session.phase,
                            )
                            lastCommandId = command.id
                            return@collect
                        }
                        lastCommandId = command.id
                        playbackRepository.markPhase(
                            command.id,
                            PlaybackPhase.RESOLVING,
                            desiredUri = command.targetUri,
                        )
                        WallpaperDebugLog.i(
                            "engine",
                            "command_marked_resolving",
                            "engine" to engineId,
                            "command" to WallpaperDebugLog.command(command.id),
                            "target" to WallpaperDebugLog.uri(command.targetUri),
                        )
                        renderHandler.post { handlePlaybackCommandLocked(command) }
                    }
                }
            }

            scope.launch {
                lastPlaylistRevision = playbackRepository.playlistRevision()
                playbackRepository.observePlaylistState().collect { state ->
                    if (state == null || state.revision <= lastPlaylistRevision) return@collect
                    WallpaperDebugLog.i(
                        "engine",
                        "playlist_revision_observed",
                        "engine" to engineId,
                        "revision" to state.revision,
                        "previousRevision" to lastPlaylistRevision,
                        "queueSize" to state.queue.size,
                        "current" to WallpaperDebugLog.uri(state.currentUri),
                    )
                    lastPlaylistRevision = state.revision
                    renderHandler.post {
                        if (state.queue.isEmpty()) {
                            playlistQueue = emptyList()
                            queueController?.setQueue(emptyList(), currentUri = null, resetHistory = true)
                            currentIndex = -1
                            scheduleRotateLocked()
                        } else {
                            val current = state.currentUri
                                ?: currentEntryLocked()?.uri?.toString()
                                ?: state.queue.first()
                            loadQueueAndRebuild(Uri.parse(current), includeStartWhenMissing = false)
                        }
                    }
                }
            }

            scope.launch {
                kotlinx.coroutines.delay(500L)
                val session = playbackRepository.getSession()
                val latest = playbackRepository.latestCommand()
                val serviceUri = VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)?.toString()
                val shouldRecover = ServiceApplyRecoveryPolicy.shouldSubmit(
                        desiredUri = session.desiredUri,
                        appliedUri = session.appliedUri,
                        latestCommandTargetUri = latest?.targetUri,
                        serviceUri = serviceUri,
                )
                WallpaperDebugLog.i(
                    "engine",
                    "recovery_evaluated",
                    "engine" to engineId,
                    "desired" to WallpaperDebugLog.uri(session.desiredUri),
                    "applied" to WallpaperDebugLog.uri(session.appliedUri),
                    "sessionCommand" to WallpaperDebugLog.command(session.commandId),
                    "phase" to session.phase,
                    "latestCommand" to WallpaperDebugLog.command(latest?.id),
                    "latestTarget" to WallpaperDebugLog.uri(latest?.targetUri),
                    "serviceTarget" to WallpaperDebugLog.uri(serviceUri),
                    "shouldRecover" to shouldRecover,
                )
                if (shouldRecover) {
                    serviceUri?.let {
                        playbackRepository.submitApply(it, source = "system_confirmation_recovery")
                        WallpaperDebugLog.i(
                            "engine",
                            "recovery_command_submitted",
                            "engine" to engineId,
                            "target" to WallpaperDebugLog.uri(it),
                        )
                    }
                } else if (latest == null && session.appliedUri == null) {
                    serviceUri?.let {
                        playbackRepository.submitApply(it, source = "service_recovery")
                        WallpaperDebugLog.i(
                            "engine",
                            "bootstrap_command_submitted",
                            "engine" to engineId,
                            "target" to WallpaperDebugLog.uri(it),
                        )
                    }
                }
            }
        }

        private fun handlePlaybackCommandLocked(command: PlaybackCommand) {
            WallpaperDebugLog.i(
                "engine",
                "command_handle_start",
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(command.id),
                "type" to command.type,
                "target" to WallpaperDebugLog.uri(command.targetUri),
                "revision" to command.playlistRevision,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "playGeneration" to playGeneration,
            )
            pendingCommandId = command.id
            pendingCommandUri = command.targetUri
            pendingCommandRevision = command.playlistRevision
            scope.launch {
                playbackRepository.markPhase(
                    command.id,
                    PlaybackPhase.PREPARING,
                    desiredUri = command.targetUri,
                )
            }
            when (command.type) {
                PlaybackCommandType.APPLY -> command.targetUri?.let { targetUri ->
                    val recoveryCanReuseCurrent =
                        command.source == "system_confirmation_recovery" &&
                            currentEntryLocked()?.uri?.toString() == targetUri &&
                            pendingFrameUri == null &&
                            renderer?.currentSource != null
                    if (recoveryCanReuseCurrent) {
                        WallpaperDebugLog.i(
                            "engine",
                            "recovery_apply_reuses_confirmed_source",
                            "engine" to engineId,
                            "command" to WallpaperDebugLog.command(command.id),
                            "target" to WallpaperDebugLog.uri(targetUri),
                        )
                    }
                    loadQueueAndRebuild(
                        Uri.parse(targetUri),
                        includeStartWhenMissing = true,
                        forceRestart = !recoveryCanReuseCurrent,
                    )
                } ?: failPlaybackCommand(command, "missing_target", "Apply command has no target URI")
                PlaybackCommandType.NEXT -> advanceToNextLocked(command.id)
                PlaybackCommandType.PREVIOUS -> advanceToPreviousLocked(command.id)
                PlaybackCommandType.RETRY -> command.targetUri?.let {
                    loadQueueAndRebuild(Uri.parse(it), includeStartWhenMissing = true)
                }
                PlaybackCommandType.SET_QUEUE,
                PlaybackCommandType.UPDATE_SETTINGS -> loadQueueAndRebuild(
                    VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)
                        ?: return,
                    includeStartWhenMissing = true,
                )
            }
        }

        private fun failPlaybackCommand(command: PlaybackCommand, code: String, message: String) {
            WallpaperDebugLog.e(
                "engine",
                "command_failed_before_render",
                null,
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(command.id),
                "target" to WallpaperDebugLog.uri(command.targetUri),
                "code" to code,
                "message" to message,
            )
            scope.launch {
                playbackRepository.markPhase(
                    command.id,
                    PlaybackPhase.FAILED,
                    desiredUri = command.targetUri,
                    errorCode = code,
                    errorMessage = message,
                )
            }
        }

        private fun failPlaybackCommandIfPresent(commandId: String?, code: String, message: String) {
            if (commandId == null) return
            WallpaperDebugLog.e(
                "engine",
                "command_failed",
                null,
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(commandId),
                "target" to WallpaperDebugLog.uri(pendingCommandUri),
                "code" to code,
                "message" to message,
            )
            scope.launch {
                playbackRepository.markPhase(
                    commandId = commandId,
                    phase = PlaybackPhase.FAILED,
                    errorCode = code,
                    errorMessage = message,
                )
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            val surface = holder.surface ?: return
            val callbackAt = SystemClock.elapsedRealtime()
            if (isPreview) previewSurfaceChangedAtMs = callbackAt
            WallpaperDebugLog.i(
                "engine",
                "surface_changed_callback",
                "engine" to engineId,
                "preview" to isPreview,
                "size" to "${width}x$height",
                "surfaceValid" to surface.isValid,
                "hasRenderer" to (renderer != null),
                "visible" to visible,
            )
            renderHandler.post {
                val renderStartedAt = SystemClock.elapsedRealtime()
                val queueDelayMs = renderStartedAt - callbackAt
                if (!surface.isValid) {
                    Log.w(TAG, "onSurfaceChanged: Surface is not valid on render thread")
                    WallpaperDebugLog.w(
                        "engine",
                        "surface_invalid_on_render_thread",
                        "engine" to engineId,
                        "queueDelayMs" to queueDelayMs,
                    )
                    return@post
                }
                surfaceWidth = width
                surfaceHeight = height

                val currentRenderer = renderer
                if (currentRenderer != null) {
                    try {
                        currentRenderer.updateOutputSurface(surface, width, height)
                        currentRenderer.draw()
                        resumeVideoSourcesLocked()
                        WallpaperDebugLog.i(
                            "engine",
                            "surface_reused_renderer",
                            "engine" to engineId,
                            "queueDelayMs" to queueDelayMs,
                            "workMs" to SystemClock.elapsedRealtime() - renderStartedAt,
                            "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                        )
                        return@post
                    } catch (e: Throwable) {
                        Log.e(TAG, "updateOutputSurface failed, will recreate renderer", e)
                        WallpaperDebugLog.e(
                            "engine",
                            "surface_reuse_failed",
                            e,
                            "engine" to engineId,
                            "queueDelayMs" to queueDelayMs,
                        )
                    }
                }

                releasePlayerLocked()
                releaseRendererLocked()
                val videoUri = VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)
                if (videoUri == null) {
                    Log.w(TAG, "未找到视频 URI，无法启动动态壁纸")
                    WallpaperDebugLog.e(
                        "engine",
                        "surface_missing_target_uri",
                        null,
                        "engine" to engineId,
                    )
                    return@post
                }

                val r = try {
                    GLVideoRenderer(
                        surface,
                        width,
                        height,
                        1080,
                        1920
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "初始化 GLVideoRenderer 失败", e)
                    WallpaperDebugLog.e(
                        "engine",
                        "renderer_create_failed",
                        e,
                        "engine" to engineId,
                        "queueDelayMs" to queueDelayMs,
                    )
                    return@post
                }
                renderer = r
                r.setDisplayGravity(settings.displayGravity)
                r.setTransitionType(settings.transitionType)
                videoSurface = Surface(r.surfaceTexture)

                WallpaperDebugLog.i(
                    "engine",
                    if (isPreview) "renderer_ready_load_preview_target" else "renderer_ready_load_queue",
                    "engine" to engineId,
                    "preview" to isPreview,
                    "target" to WallpaperDebugLog.uri(videoUri),
                    "queueDelayMs" to queueDelayMs,
                    "workMs" to SystemClock.elapsedRealtime() - renderStartedAt,
                )

                if (SystemPreviewPolicy.useFastPath(isPreview)) {
                    loadPreviewTarget(videoUri)
                } else {
                    loadQueueAndRebuild(videoUri, includeStartWhenMissing = true)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            WallpaperDebugLog.i(
                "engine",
                "surface_destroyed",
                "engine" to engineId,
                "preview" to isPreview,
                "visible" to visible,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
            )
            val posted = renderHandler.post {
                pauseRotateLocked("surface_destroyed")
                cancelHiddenCatchUpLocked()
                unregisterSensor()
                android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
                releasePlayerLocked()
                releaseRendererLocked()
            }
            if (!posted) {
                unregisterSensor()
                releasePlayerLocked()
                releaseRendererLocked()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            WallpaperDebugLog.i(
                "engine",
                "visibility_changed",
                "engine" to engineId,
                "preview" to isPreview,
                "visible" to visible,
                "screenOn" to isScreenOn,
                "powerSave" to isPowerSaveModeActive,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "pendingFrame" to WallpaperDebugLog.uri(pendingFrameUri),
            )
            this.visible = visible
            renderHandler.post {
                if (visible) {
                    if (!isScreenOn) {
                        // Some launchers report visible=true even though SCREEN_OFF means no human can
                        // see the wallpaper. Do not cancel the already-earned hidden deadline here.
                        pauseVideoSourcesLocked()
                        pauseRotateLocked("wallpaper_visible_screen_off")
                        unregisterSensor()
                        android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
                        return@post
                    }
                    cancelHiddenCatchUpLocked()
                    if (catchUpRotationBeforeResumeLocked("wallpaper_visible")) {
                        return@post
                    }
                    resumeVideoSourcesLocked()
                    registerSensor()
                    startContinuousRedrawIfNeeded()
                    resumeRotateLocked()
                } else {
                    // Android explicitly recommends no continuous wallpaper CPU while invisible.
                    // Pause the active decoder for both third-party-app coverage and screen-off. A
                    // one-shot catch-up may briefly warm the replacement later, then it is paused too.
                    scheduleVideoPauseLocked("wallpaper_hidden")
                    pauseRotateLocked("wallpaper_hidden")
                    unregisterSensor()
                    android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
                }
            }
        }

        override fun onDestroy() {
            engineDestroyed = true
            renderHandler.removeCallbacks(renderHeartbeat)
            renderHandler.post { cancelDeferredVideoPauseLocked() }
            WallpaperDebugLog.i(
                "engine",
                "destroy_start",
                "engine" to engineId,
                "preview" to isPreview,
                "lifetimeMs" to SystemClock.elapsedRealtime() - engineCreatedAtMs,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
            )
            prefs().unregisterOnSharedPreferenceChangeListener(this)
            try {
                this@VideoWallpaperService.unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = renderHandler.post {
                try {
                    cancelHiddenCatchUpLocked()
                    pauseRotateLocked("engine_destroy")
                    cancelHiddenCatchUpLocked()
                    unregisterSensor()
                    android.view.Choreographer.getInstance().removeFrameCallback(redrawRunnable)
                    releasePlayerLocked()
                    releaseRendererLocked()
                } finally {
                    latch.countDown()
                }
            }
            if (posted) {
                try {
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            renderThread.quitSafely()
            try {
                renderThread.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            scope.cancel()
            WallpaperDebugLog.i(
                "engine",
                "destroy_complete",
                "engine" to engineId,
                "lifetimeMs" to SystemClock.elapsedRealtime() - engineCreatedAtMs,
            )
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                VideoWallpaperSettings.KEY_VIDEO_URI -> {
                    val videoUri = VideoWallpaperStarter.loadVideoUri(this@VideoWallpaperService)
                    if (videoUri != null) {
                        renderHandler.post {
                            if (currentEntryLocked()?.uri == videoUri) {
                                return@post
                            }
                            if (SystemPreviewPolicy.useFastPath(isPreview)) {
                                loadPreviewTarget(videoUri)
                            } else {
                                loadQueueAndRebuild(videoUri, includeStartWhenMissing = true, forceRestart = true)
                            }
                        }
                    }
                }
                VideoWallpaperSettings.KEY_ROTATE_INTERVAL_SECONDS,
                VideoWallpaperSettings.KEY_RANDOM_ENABLED,
                VideoWallpaperSettings.KEY_PLAYBACK_MODE,
                VideoWallpaperSettings.KEY_DISPLAY_GRAVITY,
                VideoWallpaperSettings.KEY_TRANSITION_TYPE,
                VideoWallpaperSettings.KEY_PARALLAX_STRENGTH -> {
                    val newSettings = VideoWallpaperSettings.read(this@VideoWallpaperService)
                    renderHandler.post {
                        val intervalChanged = key == VideoWallpaperSettings.KEY_ROTATE_INTERVAL_SECONDS
                        settings = newSettings
                        renderer?.setTransitionType(settings.transitionType)
                        (renderer?.currentSource as? MultiLayerParallaxSource)?.setStrength(settings.parallaxStrength)
                        (renderer?.nextSource as? MultiLayerParallaxSource)?.setStrength(settings.parallaxStrength)
                        if (!isPreview) queueController?.setMode(settings.playbackMode.toQueueMode())
                        currentEntryLocked()?.let { entry ->
                            applyOffsetForLocked(entry)
                        }
                        if (!isPreview) {
                            scheduleRotateLocked(resetDeadline = intervalChanged)
                        }
                    }
                }
                else -> {
                    if (key != null && key.startsWith(VideoWallpaperSettings.KEY_CROP_OFFSET_PREFIX)) {
                        renderHandler.post {
                            currentEntryLocked()?.let { entry ->
                                applyOffsetForLocked(entry)
                                renderer?.draw()
                            }
                        }
                    }
                }
            }
        }

        private var isForceRestartPending = false

        private fun inferWallpaperType(uri: Uri): WallpaperType {
            val path = uri.path?.lowercase().orEmpty()
            return if (
                path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                path.endsWith(".webp") || path.endsWith(".bmp") || path.endsWith(".gif")
            ) {
                WallpaperType.STATIC
            } else {
                WallpaperType.VIDEO
            }
        }

        private fun loadPreviewTarget(startUri: Uri) {
            val loadId = WallpaperDebugLog.nextId("previewLoad")
            val requestedAt = SystemClock.elapsedRealtime()
            WallpaperDebugLog.i(
                "engine",
                "system_preview_target_load_requested",
                "engine" to engineId,
                "load" to loadId,
                "target" to WallpaperDebugLog.uri(startUri),
                "sinceSurfaceMs" to (requestedAt - previewSurfaceChangedAtMs).coerceAtLeast(0L),
            )
            loadJob?.cancel()
            val job = scope.launch {
                val dao = AppDatabase.getInstance(this@VideoWallpaperService).wallpaperDao()
                val entity = PlaybackTrace.section("preview.target.resolve") {
                    dao.findByUri(startUri.toString())
                }
                val entry = PlaylistEntry(
                    uri = startUri,
                    type = entity?.type ?: inferWallpaperType(startUri),
                    width = entity?.width,
                    height = entity?.height,
                    duration = entity?.duration,
                    cropScale = entity?.cropScale ?: 1f,
                    cropOffsetX = entity?.cropOffsetX ?: 0f,
                    cropOffsetY = entity?.cropOffsetY ?: 0f,
                    cropScaleMode = entity?.cropScaleMode ?: MediaScaleMode.FILL.persistedValue,
                    cropRevision = entity?.cropRevision ?: 0L,
                )
                WallpaperDebugLog.i(
                    "engine",
                    "system_preview_target_resolved",
                    "engine" to engineId,
                    "load" to loadId,
                    "target" to WallpaperDebugLog.uri(startUri),
                    "dbHit" to (entity != null),
                    "type" to entry.type,
                    "elapsedMs" to SystemClock.elapsedRealtime() - requestedAt,
                )
                renderHandler.post {
                    if (loadJob != coroutineContext[kotlinx.coroutines.Job]) {
                        WallpaperDebugLog.w(
                            "engine",
                            "system_preview_target_discarded_stale",
                            "engine" to engineId,
                            "load" to loadId,
                            "elapsedMs" to SystemClock.elapsedRealtime() - requestedAt,
                        )
                        return@post
                    }
                    playlistQueue = listOf(entry)
                    currentIndex = 0
                    queueController = null
                    isForceRestartPending = false
                    pendingCommandId = null
                    pendingCommandUri = null
                    pendingCommandRevision = 0L
                    consecutiveFailures = 0
                    WallpaperDebugLog.i(
                        "engine",
                        "system_preview_render_start",
                        "engine" to engineId,
                        "load" to loadId,
                        "target" to WallpaperDebugLog.uri(startUri),
                        "elapsedMs" to SystemClock.elapsedRealtime() - requestedAt,
                    )
                    rebuildPlayerLocked(entry, commandId = null)
                }
            }
            loadJob = job
        }

        private fun loadQueueAndRebuild(
            startUri: Uri,
            includeStartWhenMissing: Boolean,
            forceRestart: Boolean = false
        ) {
            val loadId = WallpaperDebugLog.nextId("queueLoad")
            val requestedAt = SystemClock.elapsedRealtime()
            WallpaperDebugLog.i(
                "engine",
                "queue_load_requested",
                "engine" to engineId,
                "load" to loadId,
                "start" to WallpaperDebugLog.uri(startUri),
                "includeMissing" to includeStartWhenMissing,
                "forceRestart" to forceRestart,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "pendingCommand" to WallpaperDebugLog.command(pendingCommandId),
            )
            if (forceRestart) {
                isForceRestartPending = true
            }
            loadJob?.cancel()
            val job = scope.launch {
                val dao = AppDatabase.getInstance(this@VideoWallpaperService).wallpaperDao()
                val playlistItems = PlaybackTrace.section("queue.resolve") {
                    dao.playlistSnapshot().map {
                        PlaylistEntry(
                            uri = Uri.parse(it.uri),
                            type = it.type,
                            width = it.width,
                            height = it.height,
                            duration = it.duration,
                            cropScale = it.cropScale,
                            cropOffsetX = it.cropOffsetX,
                            cropOffsetY = it.cropOffsetY,
                            cropScaleMode = it.cropScaleMode,
                            cropRevision = it.cropRevision,
                        )
                    }
                }
                val startEntityDb = dao.findByUri(startUri.toString())
                val startType = startEntityDb?.type ?: inferWallpaperType(startUri)
                val startEntry = PlaylistEntry(
                    startUri,
                    startType,
                    startEntityDb?.width,
                    startEntityDb?.height,
                    startEntityDb?.duration,
                    startEntityDb?.cropScale ?: 1f,
                    startEntityDb?.cropOffsetX ?: 0f,
                    startEntityDb?.cropOffsetY ?: 0f,
                    startEntityDb?.cropScaleMode ?: MediaScaleMode.FILL.persistedValue,
                    startEntityDb?.cropRevision ?: 0L,
                )

                val queue = when {
                    playlistItems.any { it.uri == startUri } -> playlistItems
                    includeStartWhenMissing -> listOf(startEntry) + playlistItems.filter { it.uri != startUri }
                    playlistItems.isNotEmpty() -> playlistItems
                    else -> listOf(startEntry)
                }
                WallpaperDebugLog.i(
                    "engine",
                    "queue_resolved",
                    "engine" to engineId,
                    "load" to loadId,
                    "start" to WallpaperDebugLog.uri(startUri),
                    "queueSize" to queue.size,
                    "startInDbQueue" to playlistItems.any { it.uri == startUri },
                    "elapsedMs" to SystemClock.elapsedRealtime() - requestedAt,
                )

                scope.launch {
                    staticImagePreloader.preloadWindow(
                        queue.map { StaticImagePreloader.PreloadEntry(it.uri.toString(), it.type, it.width, it.height) },
                        queue.indexOfFirst { it.uri == startUri }.coerceAtLeast(0),
                    )
                }

                renderHandler.post {
                    if (loadJob != coroutineContext[kotlinx.coroutines.Job]) {
                        WallpaperDebugLog.w(
                            "engine",
                            "queue_load_discarded_stale",
                            "engine" to engineId,
                            "load" to loadId,
                            "elapsedMs" to SystemClock.elapsedRealtime() - requestedAt,
                        )
                        return@post
                    }
                    val previousEntry = currentEntryLocked()
                    val previousUri = previousEntry?.uri
                    val newUris = queue.map { it.uri.toString() }

                    playlistQueue = queue.ifEmpty { listOf(startEntry) }
                    val force = isForceRestartPending
                    isForceRestartPending = false
                    val shouldRestart = force || previousUri != startUri
                    WallpaperDebugLog.i(
                        "engine",
                        "queue_apply_on_render_thread",
                        "engine" to engineId,
                        "load" to loadId,
                        "previous" to WallpaperDebugLog.uri(previousUri),
                        "start" to WallpaperDebugLog.uri(startUri),
                        "queueSize" to playlistQueue.size,
                        "force" to force,
                        "shouldRestart" to shouldRestart,
                        "queueDelayMs" to SystemClock.elapsedRealtime() - requestedAt,
                    )

                    val controller = queueController ?: QueueController(
                        initialQueue = newUris,
                        mode = settings.playbackMode.toQueueMode(),
                        currentUri = startUri.toString(),
                    ).also { queueController = it }
                    controller.setMode(settings.playbackMode.toQueueMode())
                    if (force) {
                        controller.restartRound(newUris, startUri.toString())
                    } else {
                        controller.setQueue(
                            newQueue = newUris,
                            currentUri = startUri.toString(),
                            resetHistory = previousUri == null || previousUri.toString() !in newUris,
                        )
                    }
                    currentIndex = controller.index().coerceAtLeast(0)
                    consecutiveFailures = 0
                    rebuildRoundQueueUI(startUri)
                    if (shouldRestart || renderer?.currentSource == null) {
                        rebuildPlayerLocked(
                            playlistQueue[currentIndex]
                        )
                    } else {
                        val entry = playlistQueue[currentIndex]
                        val commandId = pendingCommandId
                        if (PlaybackConfirmationPolicy.canConfirmExistingTarget(
                                commandId = commandId,
                                commandTargetUri = pendingCommandUri,
                                renderedUri = entry.uri.toString(),
                            )
                        ) {
                            WallpaperDebugLog.i(
                                "engine",
                                "confirm_existing_rendered_target",
                                "engine" to engineId,
                                "load" to loadId,
                                "command" to WallpaperDebugLog.command(commandId),
                                "target" to WallpaperDebugLog.uri(entry.uri),
                            )
                            renderer?.currentSource?.let { source ->
                                armPlaybackConfirmation(commandId!!, entry, source)
                                renderer?.draw()
                            }
                        }
                        scheduleRotateLocked(resetDeadline = false)
                    }
                }
            }
            loadJob = job
        }

        private fun currentEntryLocked(): PlaylistEntry? {
            return playlistQueue.getOrNull(currentIndex)
        }

        private fun rebuildPlayerLocked(
            entry: PlaylistEntry,
            commandId: String? = pendingCommandId,
        ) {
            val switchId = WallpaperDebugLog.nextId("switch")
            val switchStartedAt = SystemClock.elapsedRealtime()
            cancelDeferredVideoPauseLocked()
            val hiddenAtSwitch = !WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)
            val suppressTransitionForThisSwitch = suppressNextMediaTransition || hiddenAtSwitch
            suppressNextMediaTransition = false
            fun transitionToSource(source: RenderSource) {
                if (suppressTransitionForThisSwitch) {
                    WallpaperDebugLog.d(
                        "engine",
                        "hidden_transition_suppressed",
                        "engine" to engineId,
                        "switch" to switchId,
                        "target" to WallpaperDebugLog.uri(entry.uri),
                    )
                    RenderTransitionPolicy.withoutOffscreenTransition {
                        renderer?.transitionTo(source)
                    }
                } else {
                    renderer?.transitionTo(source)
                }
            }
            cancelHiddenCatchUpLocked()
            rotationTimer.clear()
            Log.d(TAG, "播放列表切换：type=${entry.type}, uri=${entry.uri}")
            WallpaperDebugLog.i(
                "engine",
                "media_switch_start",
                "engine" to engineId,
                "switch" to switchId,
                "queueCurrent" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "target" to WallpaperDebugLog.uri(entry.uri),
                "type" to entry.type,
                "command" to WallpaperDebugLog.command(commandId),
                "playGenerationBefore" to playGeneration,
                "visible" to visible,
                "screenOn" to isScreenOn,
                "preview" to isPreview,
                "transitionSuppressed" to suppressTransitionForThisSwitch,
                "hiddenAtSwitch" to hiddenAtSwitch,
            )

            if (SystemPreviewPolicy.shouldPublishRuntimePosition(isPreview)) {
                scope.launch(Dispatchers.IO) {
                    VideoWallpaperSettings.saveCurrentWallpaperUri(
                        this@VideoWallpaperService,
                        entry.uri.toString()
                    )
                }
            }
            applyOffsetForLocked(entry)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                SystemPreviewPolicy.shouldExtractWallpaperColors(isPreview)
            ) {
                updateMonetColors(entry)
            }

            releasePlayerLocked()
            val currentGen = playGeneration
            WallpaperDebugLog.d(
                "engine",
                "media_switch_generation_assigned",
                "engine" to engineId,
                "switch" to switchId,
                "playGeneration" to currentGen,
            )

            val uriStr = entry.uri.toString()

            if (uriStr.endsWith(".frag") || uriStr.endsWith(".glsl") || uriStr.contains("shader")) {
                scope.launch {
                    val shaderCode = try {
                        contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                    renderHandler.post {
                        if (currentGen != playGeneration) return@post
                        val source = ShaderRenderSource(shaderCode)
                        transitionToSource(source)
                        WallpaperDebugLog.i(
                            "engine",
                            "shader_source_bound",
                            "engine" to engineId,
                            "switch" to switchId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "elapsedMs" to SystemClock.elapsedRealtime() - switchStartedAt,
                        )
                        armPlaybackConfirmation(commandId, entry, source)
                        renderer?.draw()
                        startContinuousRedrawIfNeeded()
                        scheduleRotateLocked()
                    }
                }
            } else if (uriStr.endsWith(".json") || uriStr.contains("parallax")) {
                scope.launch {
                    val jsonStr = try {
                        contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                    val layers = parseParallaxLayers(this@VideoWallpaperService, jsonStr)
                    renderHandler.post {
                        if (currentGen != playGeneration) return@post
                        val source = MultiLayerParallaxSource(this@VideoWallpaperService, layers)
                        transitionToSource(source)
                        WallpaperDebugLog.i(
                            "engine",
                            "parallax_source_bound",
                            "engine" to engineId,
                            "switch" to switchId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "elapsedMs" to SystemClock.elapsedRealtime() - switchStartedAt,
                        )
                        armPlaybackConfirmation(commandId, entry, source)
                        renderer?.draw()
                        registerSensor()
                        startContinuousRedrawIfNeeded()
                        scheduleRotateLocked()
                    }
                }
            } else if (entry.type == WallpaperType.STATIC) {
                val targetW = surfaceWidth.coerceAtLeast(1080)
                val targetH = surfaceHeight.coerceAtLeast(1920)
                scope.launch {
                    val bitmap = staticImagePreloader.take(entry.uri.toString())
                        ?: decodeBitmap(entry.uri, targetW, targetH)
                    renderHandler.post {
                        if (currentGen != playGeneration) {
                            bitmap?.recycle()
                            return@post
                        }
                        if (bitmap == null) {
                            Log.w(TAG, "无法解码静态图片，跳到下一项：${entry.uri}")
                            consecutiveFailures++
                            WallpaperDebugLog.e(
                                "engine",
                                if (isPreview) "system_preview_static_decode_failed" else "static_decode_failed",
                                null,
                                "engine" to engineId,
                                "switch" to switchId,
                                "target" to WallpaperDebugLog.uri(entry.uri),
                                "elapsedMs" to SystemClock.elapsedRealtime() - switchStartedAt,
                            )
                            if (!isPreview) advanceToNextLocked()
                            return@post
                        }
                        consecutiveFailures = 0
                        val source = GLVideoRenderer.StaticRenderSource(bitmap)
                        transitionToSource(source)
                        WallpaperDebugLog.i(
                            "engine",
                            "static_source_bound",
                            "engine" to engineId,
                            "switch" to switchId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "elapsedMs" to SystemClock.elapsedRealtime() - switchStartedAt,
                        )
                        armPlaybackConfirmation(commandId, entry, source)
                        renderer?.draw()
                        bitmap.recycle()
                        startContinuousRedrawIfNeeded()
                        scheduleRotateLocked()
                    }
                }
            } else {
                scope.launch {
                    renderHandler.post {
                        if (currentGen != playGeneration) {
                            return@post
                        }
                        var generationMismatchLogged = false
                        val source = GLVideoRenderer.VideoDecoderRenderSource(
                            this@VideoWallpaperService,
                            entry.uri
                        ) {
                            if (currentGen == playGeneration && !isPowerSaveModeActive) {
                                renderer?.draw()
                            } else if (!generationMismatchLogged) {
                                generationMismatchLogged = true
                                WallpaperDebugLog.w(
                                    "engine",
                                    "video_frame_draw_suppressed",
                                    "engine" to engineId,
                                    "switch" to switchId,
                                    "target" to WallpaperDebugLog.uri(entry.uri),
                                    "sourceGeneration" to currentGen,
                                    "activeGeneration" to playGeneration,
                                    "powerSave" to isPowerSaveModeActive,
                                )
                            }
                        }
                        transitionToSource(source)
                        WallpaperDebugLog.i(
                            "engine",
                            "video_source_bound",
                            "engine" to engineId,
                            "switch" to switchId,
                            "decoder" to source.debugId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "elapsedMs" to SystemClock.elapsedRealtime() - switchStartedAt,
                            "playGeneration" to currentGen,
                            "preview" to isPreview,
                        )
                        armPlaybackConfirmation(commandId, entry, source)
                        renderer?.draw()
                        startContinuousRedrawIfNeeded()

                        if (isPowerSaveModeActive) {
                            source.pause()
                        }

                        scheduleRotateLocked()
                    }
                }
            }
        }

        private fun armPlaybackConfirmation(
            commandId: String?,
            entry: PlaylistEntry,
            source: RenderSource,
            attempt: Int = 1,
        ) {
            pendingFrameUri = entry.uri.toString()
            WallpaperDebugLog.i(
                "engine",
                "first_frame_confirmation_armed",
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(commandId),
                "target" to WallpaperDebugLog.uri(entry.uri),
                "sourceType" to source.javaClass.simpleName,
                "attempt" to attempt,
                "maxAttempts" to MAX_FIRST_FRAME_ATTEMPTS,
                "preview" to isPreview,
            )
            if (commandId != null) {
                scope.launch {
                    playbackRepository.markPhase(
                        commandId,
                        PlaybackPhase.TRANSITIONING,
                        desiredUri = entry.uri.toString(),
                    )
                }
            }
            val currentRenderer = renderer
            if (currentRenderer == null) {
                pendingFrameUri = null
                failPlaybackCommandIfPresent(commandId, "renderer_unavailable", "Renderer is not available")
                return
            }
            val requestId = commandId ?: if (isPreview) {
                "system-preview:${entry.uri}:$playGeneration"
            } else {
                "runtime:${entry.uri}:$playGeneration"
            }
            currentRenderer.awaitFirstFrame(
                source = source,
                requestId = requestId,
                listener = {
                    WallpaperDebugLog.i(
                        "engine",
                        "first_frame_confirmation_visible",
                        "engine" to engineId,
                        "command" to WallpaperDebugLog.command(commandId),
                        "target" to WallpaperDebugLog.uri(entry.uri),
                        "attempt" to attempt,
                        "preview" to isPreview,
                    )
                    if (!visible || !isScreenOn || isPowerSaveModeActive) {
                        val videoSource = source as? GLVideoRenderer.VideoDecoderRenderSource
                        if (videoSource != null) {
                            if (WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                                    visible = visible,
                                    screenOn = isScreenOn,
                                    powerSave = isPowerSaveModeActive,
                                    isPreview = isPreview,
                                )
                            ) {
                                scheduleVideoPauseLocked("hidden_prepared_source")
                                WallpaperDebugLog.d(
                                    "engine",
                                    "hidden_prepared_source_pause_deferred",
                                    "engine" to engineId,
                                    "target" to WallpaperDebugLog.uri(entry.uri),
                                    "screenOn" to isScreenOn,
                                    "visible" to visible,
                                    "graceMs" to HIDDEN_VIDEO_PAUSE_GRACE_MS,
                                )
                            } else {
                                videoSource.pause()
                                WallpaperDebugLog.d(
                                    "engine",
                                    "hidden_prepared_source_paused",
                                    "engine" to engineId,
                                    "target" to WallpaperDebugLog.uri(entry.uri),
                                    "screenOn" to isScreenOn,
                                    "visible" to visible,
                                    "powerSave" to isPowerSaveModeActive,
                                )
                            }
                        }
                    }
                    if (isPreview && commandId == null) {
                        if (pendingFrameUri == entry.uri.toString()) pendingFrameUri = null
                        val now = SystemClock.elapsedRealtime()
                        WallpaperDebugLog.i(
                            "engine",
                            "system_preview_first_frame_visible",
                            "engine" to engineId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "surfaceToFrameMs" to if (previewSurfaceChangedAtMs > 0L) now - previewSurfaceChangedAtMs else -1L,
                            "engineToFrameMs" to now - engineCreatedAtMs,
                        )
                    } else if (commandId != null) {
                        confirmPlaybackCommand(commandId, entry)
                    } else {
                        commitRuntimePosition(entry)
                    }
                },
                onFailed = { code ->
                    WallpaperDebugLog.w(
                        "engine",
                        "first_frame_confirmation_failed",
                        "engine" to engineId,
                        "command" to WallpaperDebugLog.command(commandId),
                        "target" to WallpaperDebugLog.uri(entry.uri),
                        "attempt" to attempt,
                        "code" to code,
                        "preview" to isPreview,
                    )
                    if (isPreview) {
                        WallpaperDebugLog.w(
                            "engine",
                            "system_preview_first_frame_failed",
                            "engine" to engineId,
                            "target" to WallpaperDebugLog.uri(entry.uri),
                            "attempt" to attempt,
                            "code" to code,
                            "sinceSurfaceMs" to if (previewSurfaceChangedAtMs > 0L) {
                                SystemClock.elapsedRealtime() - previewSurfaceChangedAtMs
                            } else {
                                -1L
                            },
                        )
                    }
                    if (PlaybackConfirmationPolicy.shouldRetryFirstFrame(
                            errorCode = code,
                            attempt = attempt,
                            maxAttempts = MAX_FIRST_FRAME_ATTEMPTS,
                        )
                    ) {
                        Log.w(TAG, "First frame still pending for ${entry.uri}; retry $attempt/$MAX_FIRST_FRAME_ATTEMPTS")
                        renderHandler.post {
                            val currentRenderer = renderer
                            val stillBound = currentRenderer != null &&
                                (currentRenderer.currentSource === source || currentRenderer.nextSource === source) &&
                                pendingFrameUri == entry.uri.toString() &&
                                (commandId == null || commandId == pendingCommandId)
                            if (stillBound) {
                                WallpaperDebugLog.i(
                                    "engine",
                                    "first_frame_confirmation_retry",
                                    "engine" to engineId,
                                    "command" to WallpaperDebugLog.command(commandId),
                                    "target" to WallpaperDebugLog.uri(entry.uri),
                                    "nextAttempt" to attempt + 1,
                                )
                                armPlaybackConfirmation(commandId, entry, source, attempt + 1)
                            }
                        }
                    } else {
                        if (pendingFrameUri == entry.uri.toString()) pendingFrameUri = null
                        failPlaybackCommandIfPresent(commandId, code, "Target source could not be prepared")
                        if (commandId == pendingCommandId) pendingCommandId = null
                        renderHandler.post { scheduleRotateLocked() }
                    }
                },
            )
        }

        private fun commitRuntimePosition(entry: PlaylistEntry) {
            if (!SystemPreviewPolicy.shouldPublishRuntimePosition(isPreview)) return
            if (pendingFrameUri != entry.uri.toString()) return
            WallpaperDebugLog.i(
                "engine",
                "runtime_position_confirmed",
                "engine" to engineId,
                "target" to WallpaperDebugLog.uri(entry.uri),
                "index" to currentIndex,
            )
            pendingFrameUri = null
            val orderedQueue = queueController?.orderedQueue()
                ?: playlistQueue.map { it.uri.toString() }
            val runtimeIndex = orderedQueue.indexOf(entry.uri.toString()).takeIf { it >= 0 } ?: 0
            scope.launch {
                playbackRepository.markRuntimeApplied(
                    uri = entry.uri.toString(),
                    currentIndex = runtimeIndex,
                    nextPreviewUri = orderedQueue.getOrNull(1),
                )
            }
            renderHandler.post { scheduleRotateLocked() }
        }

        private fun confirmPlaybackCommand(commandId: String, entry: PlaylistEntry) {
            if (commandId != pendingCommandId) {
                WallpaperDebugLog.w(
                    "engine",
                    "command_confirmation_discarded_stale",
                    "engine" to engineId,
                    "command" to WallpaperDebugLog.command(commandId),
                    "pendingCommand" to WallpaperDebugLog.command(pendingCommandId),
                    "target" to WallpaperDebugLog.uri(entry.uri),
                )
                return
            }
            val appliedIndex = currentIndex
            val appliedRevision = pendingCommandRevision
            pendingCommandId = null
            if (pendingFrameUri == entry.uri.toString()) pendingFrameUri = null
            WallpaperDebugLog.i(
                "engine",
                "command_confirmed_applied",
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(commandId),
                "target" to WallpaperDebugLog.uri(entry.uri),
                "index" to appliedIndex,
                "revision" to appliedRevision,
            )
            scope.launch {
                playbackRepository.markApplied(
                    commandId,
                    entry.uri.toString(),
                    appliedIndex,
                    appliedRevision,
                )
            }
            renderHandler.post { scheduleRotateLocked() }
        }

        private fun parseParallaxLayers(
            context: Context,
            jsonStr: String?
        ): List<MultiLayerParallaxSource.ParallaxLayer> {
            if (!jsonStr.isNullOrEmpty()) {
                try {
                    val jsonObject = org.json.JSONObject(jsonStr)
                    val array = jsonObject.getJSONArray("layers")
                    val list = mutableListOf<MultiLayerParallaxSource.ParallaxLayer>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val uriStr = item.getString("uri")
                        val zDepth = item.getDouble("zDepth").toFloat()
                        val bitmap = decodeBitmap(Uri.parse(uriStr), 1080, 1920)
                        if (bitmap != null) {
                            list.add(MultiLayerParallaxSource.ParallaxLayer(bitmap, zDepth))
                        }
                    }
                    if (list.isNotEmpty()) {
                        return list
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析视差层 JSON 失败，降级为默认层", e)
                }
            }
            return createFallbackParallaxLayers(context)
        }

        private fun createFallbackParallaxLayers(context: Context): List<MultiLayerParallaxSource.ParallaxLayer> {
            val width = 1080
            val height = 1920

            val bgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas1 = android.graphics.Canvas(bgBitmap)
            val paint1 = android.graphics.Paint()
            paint1.color = android.graphics.Color.parseColor("#0d1b2a")
            canvas1.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint1)
            paint1.color = android.graphics.Color.WHITE
            for (i in 0..80) {
                val rx = Random.nextFloat() * width
                val ry = Random.nextFloat() * height
                canvas1.drawCircle(rx, ry, 2f + Random.nextFloat() * 3f, paint1)
            }

            val midBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas2 = android.graphics.Canvas(midBitmap)
            val paint2 = android.graphics.Paint().apply { isAntiAlias = true }
            paint2.color = android.graphics.Color.parseColor("#415a77")
            val path = android.graphics.Path()
            path.moveTo(0f, height.toFloat())
            path.lineTo(0f, height * 0.65f)
            path.lineTo(width * 0.35f, height * 0.5f)
            path.lineTo(width * 0.68f, height * 0.58f)
            path.lineTo(width.toFloat(), height * 0.42f)
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            canvas2.drawPath(path, paint2)

            val fgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas3 = android.graphics.Canvas(fgBitmap)
            val paint3 = android.graphics.Paint().apply { isAntiAlias = true }
            paint3.color = android.graphics.Color.parseColor("#778da9")
            val path3 = android.graphics.Path()
            path3.moveTo(0f, height.toFloat())
            path3.lineTo(0f, height * 0.82f)
            path3.lineTo(width * 0.42f, height * 0.72f)
            path3.lineTo(width * 0.76f, height * 0.77f)
            path3.lineTo(width.toFloat(), height * 0.68f)
            path3.lineTo(width.toFloat(), height.toFloat())
            path3.close()
            canvas3.drawPath(path3, paint3)

            return listOf(
                MultiLayerParallaxSource.ParallaxLayer(bgBitmap, 0.0f),
                MultiLayerParallaxSource.ParallaxLayer(midBitmap, 0.4f),
                MultiLayerParallaxSource.ParallaxLayer(fgBitmap, 0.9f)
            )
        }

        private fun applyOffsetForLocked(entry: PlaylistEntry) {
            val legacy = VideoWallpaperSettings.loadCropOffset(
                this@VideoWallpaperService,
                entry.uri.toString(),
            )
            val transform = if (entry.cropRevision > 0L) {
                CropTransform(
                    scale = entry.cropScale,
                    offsetX = entry.cropOffsetX,
                    offsetY = entry.cropOffsetY,
                    scaleMode = MediaScaleMode.fromPersisted(entry.cropScaleMode),
                )
            } else {
                val (x, y) = legacy ?: VideoWallpaperSettings.gravityToOffset(settings.displayGravity)
                CropTransform(offsetX = x, offsetY = y)
            }
            renderer?.setCropTransform(transform)
        }

        private fun decodeBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(
                        this@VideoWallpaperService.contentResolver,
                        uri
                    )
                    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val width = info.size.width.coerceAtLeast(1)
                        val height = info.size.height.coerceAtLeast(1)
                        val scale = maxOf(
                            width.toFloat() / reqWidth,
                            height.toFloat() / reqHeight,
                            1f
                        )
                        decoder.setTargetSize(
                            (width / scale).toInt().coerceAtLeast(1),
                            (height / scale).toInt().coerceAtLeast(1)
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ImageDecoder 解码失败，尝试 BitmapFactory：$uri", e)
                }
            }
            val resolver = this@VideoWallpaperService.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            }
            return resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }

        private fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight &&
                    halfWidth / inSampleSize >= reqWidth
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }

        private fun advanceToNextLocked(commandId: String? = pendingCommandId) {
            val now = SystemClock.elapsedRealtime()
            if (commandId == null && now - lastTransitionTime < 500) {
                WallpaperDebugLog.d(
                    "engine",
                    "next_ignored_debounce",
                    "engine" to engineId,
                    "sinceLastMs" to now - lastTransitionTime,
                )
                return
            }
            WallpaperDebugLog.i(
                "engine",
                "next_requested",
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(commandId),
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "queueSize" to playlistQueue.size,
                "index" to currentIndex,
                "pendingFrame" to WallpaperDebugLog.uri(pendingFrameUri),
            )
            lastTransitionTime = now
            if (playlistQueue.size <= 1) {
                if (commandId != null) {
                    val currentSource = renderer?.currentSource
                    currentEntryLocked()?.let { entry ->
                        if (currentSource != null) armPlaybackConfirmation(commandId, entry, currentSource)
                        renderer?.draw()
                    }
                }
                if (currentEntryLocked()?.type == WallpaperType.VIDEO) {
                    (renderer?.currentSource as? GLVideoRenderer.VideoDecoderRenderSource)?.resume()
                }
                scheduleRotateLocked()
                return
            }
            val nextUri = queueController?.next()
            if (nextUri == null) {
                failPlaybackCommandIfPresent(commandId, "empty_queue", "Playback queue is empty")
                return
            }
            currentIndex = queueController?.index() ?: playlistQueue.indexOfFirst { it.uri.toString() == nextUri }
            WallpaperDebugLog.i(
                "engine",
                "next_resolved",
                "engine" to engineId,
                "command" to WallpaperDebugLog.command(commandId),
                "target" to WallpaperDebugLog.uri(nextUri),
                "index" to currentIndex,
            )
            rebuildRoundQueueUI(Uri.parse(nextUri))
            rebuildPlayerLocked(playlistQueue[currentIndex], commandId = commandId)
        }

        private fun advanceToPreviousLocked(commandId: String? = pendingCommandId) {
            val now = SystemClock.elapsedRealtime()
            if (commandId == null && now - lastTransitionTime < 500) {
                return
            }
            lastTransitionTime = now
            val previousUri = queueController?.previous()
            if (previousUri == null) {
                failPlaybackCommandIfPresent(commandId, "empty_queue", "Playback queue is empty")
                scheduleRotateLocked(resetDeadline = false)
                return
            }
            currentIndex = queueController?.index() ?: playlistQueue.indexOfFirst { it.uri.toString() == previousUri }
            rebuildRoundQueueUI(Uri.parse(previousUri))
            rebuildPlayerLocked(playlistQueue[currentIndex], commandId = commandId)
        }

        private fun scheduleRotateLocked(resetDeadline: Boolean = true) {
            rotateRunnable?.let { renderHandler.removeCallbacks(it) }
            rotateRunnable = null
            cancelHiddenCatchUpLocked()

            if (playlistQueue.size <= 1) {
                rotationTimer.clear()
                return
            }
            // Rotation must remain blocked until the renderer proves that the requested source
            // owns the desktop. awaitFirstFrame owns timeout/retry/final-failure semantics; a second
            // timer here used to race that state machine and incorrectly skip the current item.
            if (PlaybackConfirmationPolicy.shouldHoldRotation(pendingCommandId, pendingFrameUri)) {
                rotationTimer.clear()
                WallpaperDebugLog.i(
                    "engine",
                    "rotation_held_for_first_frame",
                    "engine" to engineId,
                    "pendingCommand" to WallpaperDebugLog.command(pendingCommandId),
                    "pendingFrame" to WallpaperDebugLog.uri(pendingFrameUri),
                )
                return
            }

            val seconds = when {
                settings.rotateIntervalSeconds > 0 -> settings.rotateIntervalSeconds
                currentEntryLocked()?.type == WallpaperType.STATIC -> DEFAULT_STATIC_SECONDS
                else -> 0
            }
            if (seconds <= 0) {
                rotationTimer.clear()
                return
            }

            val now = SystemClock.elapsedRealtime()
            val intervalMs = seconds * 1000L
            if (resetDeadline) {
                rotationTimer.reset(intervalMs)
            }
            if (!WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)) {
                rotationTimer.pause(now)
                scheduleHiddenCatchUpLocked("rotation_waiting_visibility")
                WallpaperDebugLog.d(
                    "engine",
                    "rotation_waiting_visibility",
                    "engine" to engineId,
                    "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                    "remainingMs" to rotationTimer.remaining(now),
                    "intervalMs" to intervalMs,
                    "visible" to visible,
                    "screenOn" to isScreenOn,
                )
                return
            }

            val delayMs = rotationTimer.resumeDelay(now, intervalMs)
            val task = Runnable {
                rotationTimer.fired()
                WallpaperDebugLog.i(
                    "engine",
                    "rotation_timer_fired",
                    "engine" to engineId,
                    "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                    "intervalMs" to intervalMs,
                )
                advanceToNextLocked()
            }
            rotateRunnable = task
            WallpaperDebugLog.d(
                "engine",
                "rotation_scheduled",
                "engine" to engineId,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "delayMs" to delayMs,
                "intervalMs" to intervalMs,
            )
            renderHandler.postDelayed(task, delayMs)
        }

        private fun pauseRotateLocked(reason: String) {
            rotateRunnable?.let { renderHandler.removeCallbacks(it) }
            rotateRunnable = null
            val now = SystemClock.elapsedRealtime()
            rotationTimer.pause(now)
            scheduleHiddenCatchUpLocked(reason)
            WallpaperDebugLog.d(
                "engine",
                "rotation_paused",
                "engine" to engineId,
                "reason" to reason,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "remainingMs" to rotationTimer.remaining(now),
                "intervalMs" to rotationTimer.interval(),
                "visible" to visible,
                "screenOn" to isScreenOn,
            )
        }

        private fun cancelHiddenCatchUpLocked() {
            hiddenCatchUpRunnable?.let { renderHandler.removeCallbacks(it) }
            hiddenCatchUpRunnable = null
        }

        /**
         * Keep exactly one already-earned deadline alive while the wallpaper is not actually visible.
         * On some OEMs Engine.visible stays true across SCREEN_OFF, so hidden means !(visible && screenOn).
         * No new deadline is armed for the replacement while hidden, keeping this bounded to one catch-up.
         */
        private fun scheduleHiddenCatchUpLocked(reason: String) {
            cancelHiddenCatchUpLocked()
            if (!WallpaperVisibilityPolicy.canRunHiddenCatchUp(
                    visible = visible,
                    screenOn = isScreenOn,
                    powerSave = isPowerSaveModeActive,
                    isPreview = isPreview,
                    engineDestroyed = engineDestroyed,
                )
            ) return
            if (playlistQueue.size <= 1) return
            if (PlaybackConfirmationPolicy.shouldHoldRotation(pendingCommandId, pendingFrameUri)) return
            val now = SystemClock.elapsedRealtime()
            val delayMs = rotationTimer.hiddenCatchUpDelay(now) ?: return
            lateinit var task: Runnable
            task = Runnable {
                if (hiddenCatchUpRunnable !== task) return@Runnable
                hiddenCatchUpRunnable = null
                if (!WallpaperVisibilityPolicy.canRunHiddenCatchUp(
                        visible = visible,
                        screenOn = isScreenOn,
                        powerSave = isPowerSaveModeActive,
                        isPreview = isPreview,
                        engineDestroyed = engineDestroyed,
                    )
                ) return@Runnable
                if (playlistQueue.size <= 1) return@Runnable
                if (PlaybackConfirmationPolicy.shouldHoldRotation(pendingCommandId, pendingFrameUri)) return@Runnable
                val firedAt = SystemClock.elapsedRealtime()
                if (!rotationTimer.isOverdue(firedAt)) {
                    scheduleHiddenCatchUpLocked("deadline_recheck")
                    return@Runnable
                }
                WallpaperDebugLog.i(
                    "engine",
                    "rotation_hidden_deadline_catch_up",
                    "engine" to engineId,
                    "reason" to reason,
                    "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                    "intervalMs" to rotationTimer.interval(),
                    "visible" to visible,
                    "screenOn" to isScreenOn,
                )
                rotationTimer.fired()
                suppressNextMediaTransition = true
                advanceToNextLocked()
            }
            hiddenCatchUpRunnable = task
            WallpaperDebugLog.d(
                "engine",
                "rotation_hidden_deadline_scheduled",
                "engine" to engineId,
                "reason" to reason,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "delayMs" to delayMs,
                "intervalMs" to rotationTimer.interval(),
                "visible" to visible,
                "screenOn" to isScreenOn,
            )
            renderHandler.postDelayed(task, delayMs)
        }

        private fun catchUpRotationBeforeResumeLocked(reason: String): Boolean {
            if (!WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn) || playlistQueue.size <= 1) return false
            if (PlaybackConfirmationPolicy.shouldHoldRotation(pendingCommandId, pendingFrameUri)) return false
            val now = SystemClock.elapsedRealtime()
            if (!rotationTimer.isOverdue(now)) return false
            WallpaperDebugLog.i(
                "engine",
                "rotation_catch_up_before_resume",
                "engine" to engineId,
                "reason" to reason,
                "current" to WallpaperDebugLog.uri(currentEntryLocked()?.uri),
                "intervalMs" to rotationTimer.interval(),
            )
            rotationTimer.fired()
            advanceToNextLocked()
            return true
        }

        private fun resumeRotateLocked() {
            if (!WallpaperVisibilityPolicy.isActuallyVisible(visible, isScreenOn)) return
            cancelHiddenCatchUpLocked()
            scheduleRotateLocked(resetDeadline = false)
        }

        private fun releasePlayerLocked() {
            val previousGeneration = playGeneration
            playGeneration++
            WallpaperDebugLog.d(
                "engine",
                "legacy_player_release",
                "engine" to engineId,
                "hadLegacyPlayer" to (player != null),
                "generationBefore" to previousGeneration,
                "generationAfter" to playGeneration,
                "rendererCurrentType" to renderer?.currentSource?.javaClass?.simpleName,
                "rendererNextType" to renderer?.nextSource?.javaClass?.simpleName,
            )
            rotateRunnable?.let { renderHandler.removeCallbacks(it) }
            rotateRunnable = null
            val p = player ?: return
            player = null

            val cleanupRunnable = Runnable {
                try {
                    playerListener?.let { p.removeListener(it) }
                    playerListener = null
                    p.clearVideoSurface()
                    p.release()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error releasing ExoPlayer", e)
                }
            }
            if (Thread.currentThread() == renderThread) {
                cleanupRunnable.run()
            } else {
                renderHandler.post(cleanupRunnable)
            }
        }

        private fun releaseRendererLocked() {
            val startedAt = SystemClock.elapsedRealtime()
            WallpaperDebugLog.i(
                "engine",
                "renderer_release_start",
                "engine" to engineId,
                "onRenderThread" to (Thread.currentThread() == renderThread),
            )
            videoSurface?.release()
            videoSurface = null
            val r = renderer
            if (r == null) {
                WallpaperDebugLog.d(
                    "engine",
                    "renderer_release_skipped_none",
                    "engine" to engineId,
                )
                return
            }
            renderer = null

            if (Thread.currentThread() == renderThread) {
                r.release()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                val posted = renderHandler.post {
                    try {
                        r.release()
                    } finally {
                        latch.countDown()
                    }
                }
                if (posted) {
                    try {
                        latch.await()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } else {
                    r.release()
                }
            }
            WallpaperDebugLog.i(
                "engine",
                "renderer_release_complete",
                "engine" to engineId,
                "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
            )
        }

        private fun prefs(): SharedPreferences =
            VideoWallpaperSettings.prefs(this@VideoWallpaperService)

        private fun rebuildRoundQueueUI(startUri: Uri) {
            if (!SystemPreviewPolicy.shouldPublishRuntimePosition(isPreview)) return
            val queue = playlistQueue
            if (queue.isEmpty()) return
            val orderedQueue = queueController?.orderedQueue()
                ?: queue.map { it.uri.toString() }
            val runtimeIndex = orderedQueue.indexOf(startUri.toString()).takeIf { it >= 0 } ?: 0
            scope.launch {
                playbackRepository.updateRequestedPosition(
                    currentUri = startUri.toString(),
                    currentIndex = runtimeIndex,
                    nextPreviewUri = orderedQueue.getOrNull(1),
                )
            }
        }
    }

    private fun VideoWallpaperSettings.PlaybackMode.toQueueMode(): QueuePlaybackMode = when (this) {
        VideoWallpaperSettings.PlaybackMode.SEQUENTIAL -> QueuePlaybackMode.SEQUENTIAL
        VideoWallpaperSettings.PlaybackMode.RANDOM -> QueuePlaybackMode.SHUFFLE
        VideoWallpaperSettings.PlaybackMode.SHUFFLE -> QueuePlaybackMode.SHUFFLE
    }

    private data class PlaylistEntry(
        val uri: Uri,
        val type: WallpaperType,
        val width: Int? = null,
        val height: Int? = null,
        val duration: Long? = null,
        val cropScale: Float = 1f,
        val cropOffsetX: Float = 0f,
        val cropOffsetY: Float = 0f,
        val cropScaleMode: String = MediaScaleMode.FILL.persistedValue,
        val cropRevision: Long = 0L,
    )

    private data class VideoMeta(
        val width: Int,
        val height: Int
    )

    companion object {
        private const val TAG = "VideoWallpaperService"
        private const val DEFAULT_STATIC_SECONDS = 15
        private const val MAX_PLAYBACK_HISTORY = 200
        private const val MAX_FIRST_FRAME_ATTEMPTS = 3
        private const val RENDER_HEARTBEAT_INTERVAL_MS = 500L
        private const val RENDER_THREAD_LAG_WARNING_MS = 200L
        private const val REDRAW_CALLBACK_GAP_WARNING_MS = 250L
        private const val HIDDEN_VIDEO_PAUSE_GRACE_MS = 5_000L
    }
}
