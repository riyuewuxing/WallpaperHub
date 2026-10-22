package com.wallpaperhub.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.preview.model.ApplyLaunchStep
import com.wallpaperhub.preview.model.CropGeometry
import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.preview.model.PreviewApplyPolicy
import com.wallpaperhub.preview.model.PreviewApplyState
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import com.wallpaperhub.wallpaper.VideoWallpaperStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WallpaperPreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val wallpaperHubApp = app as WallpaperHubApp
    private val repo = wallpaperHubApp.repository
    private val playbackRepo = wallpaperHubApp.playbackRepository

    private val _entity = MutableStateFlow<WallpaperEntity?>(null)
    val entity: StateFlow<WallpaperEntity?> = _entity

    private val _initialOffset = MutableStateFlow(0f to 0f)
    val initialOffset: StateFlow<Pair<Float, Float>> = _initialOffset

    private val _initialScale = MutableStateFlow(1f)
    val initialScale: StateFlow<Float> = _initialScale

    private val _applyState = MutableStateFlow(PreviewApplyState.IDLE)
    val applyState: StateFlow<PreviewApplyState> = _applyState

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var verificationJob: Job? = null

    fun load(id: String) {
        WallpaperDebugLog.d("preview_vm", "load_start", "wallpaperId" to id.take(8))
        viewModelScope.launch(Dispatchers.IO) {
            val item = repo.findById(id) ?: return@launch
            val saved = if (item.cropRevision > 0L) {
                CropTransform(
                    scale = item.cropScale,
                    offsetX = item.cropOffsetX,
                    offsetY = item.cropOffsetY,
                    scaleMode = MediaScaleMode.fromPersisted(item.cropScaleMode),
                )
            } else {
                VideoWallpaperSettings.loadCropOffset(getApplication(), item.uri)?.let { (x, y) ->
                    CropTransform(offsetX = x, offsetY = y)
                }
            }
            val initial = CropGeometry.clamp(saved ?: CropTransform())
            _initialScale.value = initial.scale
            _initialOffset.value = initial.offsetX to initial.offsetY
            _entity.value = item
            WallpaperDebugLog.i(
                "preview_vm",
                "load_complete",
                "wallpaperId" to item.id.take(8),
                "uri" to WallpaperDebugLog.uri(item.uri),
                "type" to item.type,
                "cropRevision" to item.cropRevision,
                "scaleMode" to initial.scaleMode.persistedValue,
            )
        }
    }

    fun saveCrop(
        offsetX: Float,
        offsetY: Float,
        scale: Float = 1f,
        scaleMode: MediaScaleMode = MediaScaleMode.FILL,
    ) {
        val item = _entity.value ?: return
        _applyState.value = PreviewApplyState.SAVING
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistTransform(item, CropTransform(scale, offsetX, offsetY, scaleMode))
            }.onSuccess {
                _applyState.value = PreviewApplyState.SAVED
                _events.send("构图已保存")
            }.onFailure { error ->
                reportError("保存失败", error)
            }
        }
    }

    fun saveAndApply(
        offsetX: Float,
        offsetY: Float,
        scale: Float = 1f,
        scaleMode: MediaScaleMode = MediaScaleMode.FILL,
    ) {
        val item = _entity.value ?: return
        if (_applyState.value.isBusy()) {
            WallpaperDebugLog.w(
                "preview_vm",
                "apply_click_ignored_busy",
                "state" to _applyState.value,
                "uri" to WallpaperDebugLog.uri(item.uri),
            )
            return
        }
        val requestStartedAt = SystemClock.elapsedRealtime()
        val serviceActiveAtClick = VideoWallpaperSettings.isWallpaperServiceActive(getApplication())
        WallpaperDebugLog.i(
            "preview_vm",
            "apply_click",
            "uri" to WallpaperDebugLog.uri(item.uri),
            "serviceActive" to serviceActiveAtClick,
            "scale" to scale,
            "offsetX" to offsetX,
            "offsetY" to offsetY,
            "scaleMode" to scaleMode.persistedValue,
        )
        _applyState.value = PreviewApplyState.OPENING_SYSTEM_CONFIRMATION
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Capture the authoritative visible target before the playlist is rewritten. This
                // distinguishes a true new-target apply from a same-URI reapply whose crop changed.
                val appliedBeforeRestart = playbackRepo.getSession().appliedUri
                val updated = persistTransform(
                    item,
                    CropTransform(scale, offsetX, offsetY, scaleMode),
                )
                val forceChooser = !VideoWallpaperSettings.isWallpaperServiceActive(getApplication())

                // Playback-plan state must be authoritative before Android/OriginOS can create any
                // preview or production wallpaper engine for the selected URI. Deferring the 7k+
                // queue rebuild until after first-frame confirmation leaves a race where the new
                // wallpaper is briefly resolved inside the previous SHUFFLE plan at its stale
                // position. Always anchor the selected wallpaper at slot 0 first.
                restartPlaylistFromSelected(
                    selected = updated,
                    event = if (forceChooser) {
                        "playlist_restarted_before_system_preview"
                    } else {
                        "playlist_restarted_before_active_apply"
                    },
                    startedAt = requestStartedAt,
                )

                val coalesceActiveApply =
                    !forceChooser && appliedBeforeRestart != updated.uri
                WallpaperDebugLog.i(
                    "preview_vm",
                    "launch_apply_route",
                    "uri" to WallpaperDebugLog.uri(updated.uri),
                    "forceChooser" to forceChooser,
                    "appliedBeforeRestart" to WallpaperDebugLog.uri(appliedBeforeRestart),
                    "coalesceActiveApply" to coalesceActiveApply,
                )
                VideoWallpaperStarter.openSystemLiveWallpaperPage(
                    getApplication(),
                    Uri.parse(updated.uri),
                    forceOpenChooser = forceChooser,
                    coalesceActiveApply = coalesceActiveApply,
                )
            }.onSuccess { result ->
                _applyState.value = PreviewApplyPolicy.afterLaunch(result.step)
                WallpaperDebugLog.i(
                    "preview_vm",
                    "apply_launch_result",
                    "step" to result.step,
                    "state" to _applyState.value,
                    "uri" to WallpaperDebugLog.uri(item.uri),
                    "elapsedMs" to SystemClock.elapsedRealtime() - requestStartedAt,
                )
                _events.send(result.message)
                if (result.step == ApplyLaunchStep.COMMAND_SUBMITTED) {
                    beginVerification(item.uri)
                }
            }.onFailure { error ->
                reportError("应用失败", error)
            }
        }
    }

    /** Called after returning from the system wallpaper confirmation UI. */
    fun verifyApplyResult() {
        val currentState = _applyState.value
        if (currentState != PreviewApplyState.WAITING_FOR_SYSTEM_CONFIRMATION) {
            WallpaperDebugLog.d(
                "preview_vm",
                "verify_return_skipped",
                "state" to currentState,
            )
            return
        }
        val item = _entity.value ?: run {
            WallpaperDebugLog.w("preview_vm", "verify_return_missing_entity")
            return
        }
        val verificationStartedAt = SystemClock.elapsedRealtime()
        WallpaperDebugLog.i(
            "preview_vm",
            "verify_return_start",
            "uri" to WallpaperDebugLog.uri(item.uri),
            "serviceActiveNow" to VideoWallpaperSettings.isWallpaperServiceActive(getApplication()),
        )
        // Set this before launching the IO work so a second ON_RESUME callback cannot
        // start a duplicate verification/submission race on OriginOS.
        _applyState.value = PreviewApplyState.VERIFYING_APPLIED_WALLPAPER
        viewModelScope.launch(Dispatchers.IO) {
            // OriginOS can report the old wallpaper for a short period after the system
            // confirmation activity returns. Give the engine time to bind before deciding that
            // the user cancelled the operation.
            val serviceActive = awaitWallpaperServiceActive()
            _applyState.value = PreviewApplyPolicy.afterSystemReturn(serviceActive)
            WallpaperDebugLog.i(
                "preview_vm",
                "service_active_check_complete",
                "active" to serviceActive,
                "state" to _applyState.value,
                "elapsedMs" to SystemClock.elapsedRealtime() - verificationStartedAt,
            )
            if (!serviceActive) {
                _events.send("未检测到壁纸设置结果；如果已取消，可重新点击应用")
                return@launch
            }
            runCatching {
                // The playback plan was already rebuilt before opening the system confirmation
                // UI. Returning from OriginOS only needs to settle/submit the runtime command;
                // never materialize the queue a second time here.
                kotlinx.coroutines.delay(SERVICE_COMMAND_SETTLE_DELAY_MS)
                val session = playbackRepo.getSession()
                val latest = playbackRepo.latestCommand()
                val alreadyRequested =
                    session.phase != PlaybackPhase.FAILED &&
                        (latest?.targetUri == item.uri ||
                            (session.desiredUri == item.uri && session.phase != PlaybackPhase.IDLE))
                WallpaperDebugLog.i(
                    "preview_vm",
                    "return_session_snapshot",
                    "target" to WallpaperDebugLog.uri(item.uri),
                    "desired" to WallpaperDebugLog.uri(session.desiredUri),
                    "applied" to WallpaperDebugLog.uri(session.appliedUri),
                    "phase" to session.phase,
                    "sessionCommand" to WallpaperDebugLog.command(session.commandId),
                    "latestCommand" to WallpaperDebugLog.command(latest?.id),
                    "latestTarget" to WallpaperDebugLog.uri(latest?.targetUri),
                    "alreadyRequested" to alreadyRequested,
                )
                if (!alreadyRequested) {
                    playbackRepo.submitApply(item.uri, source = "system_confirmation_return")
                    WallpaperDebugLog.i(
                        "preview_vm",
                        "return_command_submitted",
                        "uri" to WallpaperDebugLog.uri(item.uri),
                    )
                }
            }.onSuccess {
                _applyState.value = PreviewApplyPolicy.afterLaunch(ApplyLaunchStep.COMMAND_SUBMITTED)
                beginVerification(item.uri)
            }.onFailure { error ->
                reportError("启动轮播失败", error)
            }
        }
    }

    private suspend fun awaitWallpaperServiceActive(): Boolean {
        repeat(SERVICE_ACTIVE_RETRY_COUNT) { attempt ->
            if (VideoWallpaperSettings.isWallpaperServiceActive(getApplication())) {
                WallpaperDebugLog.d(
                    "preview_vm",
                    "service_active_detected",
                    "attempt" to attempt,
                )
                return true
            }
            if (attempt == 0 || (attempt + 1) % 4 == 0) {
                WallpaperDebugLog.d(
                    "preview_vm",
                    "service_active_wait",
                    "attempt" to attempt + 1,
                    "max" to SERVICE_ACTIVE_RETRY_COUNT,
                )
            }
            delay(SERVICE_ACTIVE_RETRY_DELAY_MS)
        }
        return VideoWallpaperSettings.isWallpaperServiceActive(getApplication())
    }

    private suspend fun restartPlaylistFromSelected(
        selected: WallpaperEntity,
        event: String,
        startedAt: Long,
    ) {
        val settings = VideoWallpaperSettings.read(getApplication())
        val restartedQueue = repo.restartPlaylistFrom(
            selected = selected,
            shuffle = settings.playbackMode != VideoWallpaperSettings.PlaybackMode.SEQUENTIAL,
        )
        WallpaperDebugLog.i(
            "preview_vm",
            event,
            "uri" to WallpaperDebugLog.uri(selected.uri),
            "queueSize" to restartedQueue.size,
            "first" to WallpaperDebugLog.uri(restartedQueue.firstOrNull()?.uri),
            "selectedIsFirst" to (restartedQueue.firstOrNull()?.uri == selected.uri),
            "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
        )
        check(restartedQueue.firstOrNull()?.uri == selected.uri) {
            "Carousel restart did not place the selected wallpaper first"
        }
    }

    private suspend fun persistTransform(
        item: WallpaperEntity,
        requested: CropTransform,
    ): WallpaperEntity {
        val transform = CropGeometry.clamp(requested)
        repo.updateCropTransform(
            entity = item,
            scale = transform.scale,
            offsetX = transform.offsetX,
            offsetY = transform.offsetY,
            scaleMode = transform.scaleMode,
        )
        return item.copy(
            cropScale = transform.scale,
            cropOffsetX = transform.offsetX,
            cropOffsetY = transform.offsetY,
            cropScaleMode = transform.scaleMode.persistedValue,
            cropRevision = item.cropRevision + 1L,
        ).also { _entity.value = it }
    }

    private fun beginVerification(targetUri: String) {
        verificationJob?.cancel()
        verificationJob = viewModelScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            _applyState.value = PreviewApplyState.VERIFYING_APPLIED_WALLPAPER
            WallpaperDebugLog.i(
                "preview_vm",
                "session_verification_start",
                "target" to WallpaperDebugLog.uri(targetUri),
                "timeoutMs" to APPLY_VERIFICATION_TIMEOUT_MS,
            )
            val terminal = withTimeoutOrNull(APPLY_VERIFICATION_TIMEOUT_MS) {
                playbackRepo.observeSession()
                    .mapNotNull { session ->
                        WallpaperDebugLog.d(
                            "preview_vm",
                            "session_update",
                            "target" to WallpaperDebugLog.uri(targetUri),
                            "desired" to WallpaperDebugLog.uri(session.desiredUri),
                            "applied" to WallpaperDebugLog.uri(session.appliedUri),
                            "command" to WallpaperDebugLog.command(session.commandId),
                            "phase" to session.phase,
                            "errorCode" to session.errorCode,
                            "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
                        )
                        PreviewApplyPolicy.fromSession(targetUri, session)
                            ?.takeIf { it == PreviewApplyState.APPLIED || it == PreviewApplyState.ERROR }
                            ?.let { it to session.errorMessage }
                    }
                    .first()
            }
            when (terminal?.first) {
                PreviewApplyState.APPLIED -> {
                    _applyState.value = PreviewApplyState.APPLIED
                    WallpaperDebugLog.i(
                        "preview_vm",
                        "session_verification_applied",
                        "target" to WallpaperDebugLog.uri(targetUri),
                        "carouselReady" to true,
                        "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
                    )
                    _events.send("新轮播已从所选壁纸开始")
                }
                PreviewApplyState.ERROR -> {
                    _applyState.value = PreviewApplyState.ERROR
                    WallpaperDebugLog.e(
                        "preview_vm",
                        "session_verification_failed",
                        null,
                        "target" to WallpaperDebugLog.uri(targetUri),
                        "message" to terminal.second,
                        "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
                    )
                    _events.send(terminal.second ?: "壁纸渲染失败，请重试")
                }
                else -> {
                    _applyState.value = PreviewApplyState.ERROR
                    WallpaperDebugLog.e(
                        "preview_vm",
                        "session_verification_timeout",
                        null,
                        "target" to WallpaperDebugLog.uri(targetUri),
                        "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
                    )
                    _events.send("轮播启动超时，请重试")
                }
            }
        }
    }

    private suspend fun reportError(prefix: String, error: Throwable) {
        WallpaperDebugLog.e(
            "preview_vm",
            "operation_error",
            error,
            "operation" to prefix,
            "state" to _applyState.value,
            "uri" to WallpaperDebugLog.uri(_entity.value?.uri),
        )
        withContext(Dispatchers.Main) {
            _applyState.value = PreviewApplyState.ERROR
        }
        _events.send("$prefix：${error.localizedMessage ?: "请重试"}")
    }

    private fun PreviewApplyState.isBusy(): Boolean = this in setOf(
        PreviewApplyState.SAVING,
        PreviewApplyState.OPENING_SYSTEM_CONFIRMATION,
        PreviewApplyState.VERIFYING_APPLIED_WALLPAPER,
    )

    companion object {
        private const val APPLY_VERIFICATION_TIMEOUT_MS = 20_000L
        private const val SERVICE_ACTIVE_RETRY_COUNT = 40
        private const val SERVICE_ACTIVE_RETRY_DELAY_MS = 250L
        private const val SERVICE_COMMAND_SETTLE_DELAY_MS = 700L
    }
}
