package com.wallpaperhub.playback.engine

import android.content.Context
import com.wallpaperhub.data.repo.PlaybackRepository
import com.wallpaperhub.data.repo.PlaybackRuntimePolicy
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runtime bridge from renderer-confirmed visibility to durable cursor metadata.
 *
 * Normal first-frame confirmations update current/next only. A SHUFFLE plan is a stable, visible
 * permutation and repeats that same permutation at its end. RANDOM is the deliberately different
 * strategy: once its complete visible permutation is consumed, PlaybackOrderCoordinator creates a
 * fresh complete randomized round. Explicit mode taps/imports may still regenerate either strategy.
 *
 * Defensive path: automatic rotation first publishes desired/currentIndex. If the renderer has
 * already moved but its confirmation callback is lost, the session otherwise remains forever in the
 * shape phase=APPLIED + desired!=applied. After a delay longer than the service first-frame escape
 * window, that still-stable mismatch is atomically repaired. Failed media changes desired when the
 * service escapes, so collectLatest cancels the stale recovery before it can publish a false current.
 */
object PlaybackPlanRuntimeCoordinator {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val playbackRepository = PlaybackRepository.getInstance(appContext)

        scope.launch {
            playbackRepository.observeSession()
                .map { it.appliedUri }
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { currentUri ->
                    prepareCurrent(
                        appContext = appContext,
                        currentUri = currentUri,
                        eventPrefix = "visible_current",
                    )
                }
        }

        scope.launch {
            playbackRepository.observeSession().collectLatest { session ->
                val pendingUri = PlaybackRuntimePolicy.pendingRuntimeUri(
                    appliedUri = session.appliedUri,
                    desiredUri = session.desiredUri,
                    phase = session.phase,
                ) ?: return@collectLatest

                WallpaperDebugLog.w(
                    "playback_plan",
                    "runtime_confirmation_mismatch_observed",
                    "applied" to WallpaperDebugLog.uri(session.appliedUri),
                    "desired" to WallpaperDebugLog.uri(pendingUri),
                    "index" to session.currentIndex,
                    "revision" to session.queueRevision,
                    "recoveryDelayMs" to RUNTIME_CONFIRMATION_RECOVERY_MS,
                )
                delay(RUNTIME_CONFIRMATION_RECOVERY_MS)

                val latest = playbackRepository.getSession()
                val stillRecoverable = PlaybackRuntimePolicy.canRecoverUncommittedRuntimeCursor(
                    appliedUri = latest.appliedUri,
                    desiredUri = latest.desiredUri,
                    phase = latest.phase,
                ) && latest.desiredUri == pendingUri
                if (!stillRecoverable) {
                    WallpaperDebugLog.d(
                        "playback_plan",
                        "runtime_confirmation_recovery_cancelled",
                        "candidate" to WallpaperDebugLog.uri(pendingUri),
                        "latestApplied" to WallpaperDebugLog.uri(latest.appliedUri),
                        "latestDesired" to WallpaperDebugLog.uri(latest.desiredUri),
                        "phase" to latest.phase,
                    )
                    return@collectLatest
                }

                WallpaperDebugLog.w(
                    "playback_plan",
                    "runtime_confirmation_recovery_fired",
                    "candidate" to WallpaperDebugLog.uri(pendingUri),
                    "previousApplied" to WallpaperDebugLog.uri(latest.appliedUri),
                    "index" to latest.currentIndex,
                    "revision" to latest.queueRevision,
                )
                runCatching {
                    recoverRuntimeCurrent(appContext, playbackRepository, pendingUri)
                }.onSuccess { result ->
                    WallpaperDebugLog.i(
                        "playback_plan",
                        "runtime_confirmation_recovery_complete",
                        "current" to WallpaperDebugLog.uri(result.currentUri),
                        "first" to WallpaperDebugLog.uri(result.firstUri),
                        "queueSize" to result.queueSize,
                        "revision" to result.revision,
                    )
                }.onFailure { error ->
                    WallpaperDebugLog.e(
                        "playback_plan",
                        "runtime_confirmation_recovery_failed",
                        error,
                        "candidate" to WallpaperDebugLog.uri(pendingUri),
                    )
                }
            }
        }
    }

    private suspend fun prepareCurrent(
        appContext: Context,
        currentUri: String,
        eventPrefix: String,
    ) {
        WallpaperDebugLog.i(
            "playback_plan",
            "${eventPrefix}_observed",
            "current" to WallpaperDebugLog.uri(currentUri),
        )
        runCatching {
            prepareVisibleCurrent(appContext, PlaybackRepository.getInstance(appContext), currentUri)
        }.onSuccess { result ->
            val index = result.queue.indexOf(currentUri)
            val next = when {
                result.queue.size <= 1 -> result.queue.firstOrNull()
                index >= 0 -> result.queue[(index + 1) % result.queue.size]
                else -> null
            }
            WallpaperDebugLog.i(
                "playback_plan",
                "${eventPrefix}_prepared",
                "current" to WallpaperDebugLog.uri(currentUri),
                "mode" to result.mode,
                "queueSize" to result.queueSize,
                "next" to WallpaperDebugLog.uri(next),
                "revision" to result.revision,
            )
        }.onFailure { error ->
            WallpaperDebugLog.e(
                "playback_plan",
                "${eventPrefix}_prepare_failed",
                error,
                "current" to WallpaperDebugLog.uri(currentUri),
            )
        }
    }

    /**
     * Media3's repeat-all shuffle semantics repeat one already-computed shuffle order. Mirror that
     * product contract here: reaching the last SHUFFLE item updates only the cursor/next pointer and
     * loops to item 0. RANDOM deliberately falls through to the coordinator, which generates a fresh
     * complete randomized round at its end.
     */
    private suspend fun prepareVisibleCurrent(
        appContext: Context,
        playbackRepository: PlaybackRepository,
        currentUri: String,
    ): PlaybackOrderCoordinator.Result {
        val settings = VideoWallpaperSettings.read(appContext)
        val state = playbackRepository.getPlaylistState()
        if (settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SHUFFLE && state != null) {
            val index = state.queue.indexOf(currentUri)
            if (state.queue.size > 1 && index == state.queue.lastIndex) {
                val next = state.queue.first()
                playbackRepository.updatePlaybackPosition(currentUri, index, next)
                WallpaperDebugLog.i(
                    "playback_plan",
                    "shuffle_round_repeats_existing_plan",
                    "current" to WallpaperDebugLog.uri(currentUri),
                    "next" to WallpaperDebugLog.uri(next),
                    "queueSize" to state.queue.size,
                    "revision" to state.revision,
                )
                return PlaybackOrderCoordinator.Result(
                    mode = settings.playbackMode,
                    currentUri = currentUri,
                    queueSize = state.queue.size,
                    firstUri = state.queue.firstOrNull(),
                    revision = state.revision,
                    queue = state.queue,
                )
            }
        }
        return PlaybackOrderCoordinator.prepareForVisibleCurrent(appContext, currentUri)
    }

    /** Same end-of-round rule as prepareVisibleCurrent, but used by the lost-confirmation watchdog. */
    private suspend fun recoverRuntimeCurrent(
        appContext: Context,
        playbackRepository: PlaybackRepository,
        currentUri: String,
    ): PlaybackOrderCoordinator.Result {
        val settings = VideoWallpaperSettings.read(appContext)
        val state = playbackRepository.getPlaylistState()
        if (settings.playbackMode == VideoWallpaperSettings.PlaybackMode.SHUFFLE && state != null) {
            val index = state.queue.indexOf(currentUri)
            if (state.queue.size > 1 && index == state.queue.lastIndex) {
                val next = state.queue.first()
                playbackRepository.updatePlaybackPosition(currentUri, index, next)
                WallpaperDebugLog.w(
                    "playback_plan",
                    "shuffle_round_recovery_repeats_existing_plan",
                    "current" to WallpaperDebugLog.uri(currentUri),
                    "next" to WallpaperDebugLog.uri(next),
                    "queueSize" to state.queue.size,
                    "revision" to state.revision,
                )
                return PlaybackOrderCoordinator.Result(
                    mode = settings.playbackMode,
                    currentUri = currentUri,
                    queueSize = state.queue.size,
                    firstUri = state.queue.firstOrNull(),
                    revision = state.revision,
                    queue = state.queue,
                )
            }
        }
        return PlaybackOrderCoordinator.recoverUncommittedRuntimeCurrent(appContext, currentUri)
    }

    private const val RUNTIME_CONFIRMATION_RECOVERY_MS = 2_300L
}
