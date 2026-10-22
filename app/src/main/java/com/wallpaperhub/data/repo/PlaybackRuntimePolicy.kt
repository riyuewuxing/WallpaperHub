package com.wallpaperhub.data.repo

import com.wallpaperhub.playback.model.PlaybackPhase

/** Runtime position rules shared by service, plan coordinator and UI recovery. */
internal object PlaybackRuntimePolicy {
    @Suppress("UNUSED_PARAMETER")
    fun canCommitRuntimePosition(commandId: String?, phase: PlaybackPhase): Boolean =
        phase !in setOf(
            PlaybackPhase.REQUESTED,
            PlaybackPhase.RESOLVING,
            PlaybackPhase.PREPARING,
            PlaybackPhase.READY,
            PlaybackPhase.TRANSITIONING,
        )

    /**
     * Automatic rotation first publishes desired/currentIndex and only then confirms applied after
     * a visible frame. A distinct desired URI is therefore only a recovery candidate; it must never
     * be treated as the already-visible desktop wallpaper.
     */
    fun pendingRuntimeUri(
        appliedUri: String?,
        desiredUri: String?,
        phase: PlaybackPhase,
    ): String? = desiredUri?.takeIf {
        phase == PlaybackPhase.APPLIED && it != appliedUri
    }

    /**
     * The confirmed applied URI is the sole authoritative visible cursor. Desired is only a fallback
     * before anything has ever been confirmed. This prevents stale automatic targets from becoming
     * mode-switch anchors or carousel focus after a plan regeneration.
     */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveCursorUri(
        appliedUri: String?,
        desiredUri: String?,
        phase: PlaybackPhase,
    ): String? = appliedUri ?: desiredUri

    /**
     * Recovery is deliberately narrow: only the automatic-runtime mismatch shape is eligible.
     * Active APPLY/NEXT/PREVIOUS command phases can never be promoted by the watchdog.
     */
    fun canRecoverUncommittedRuntimeCursor(
        appliedUri: String?,
        desiredUri: String?,
        phase: PlaybackPhase,
    ): Boolean = pendingRuntimeUri(appliedUri, desiredUri, phase) != null
}
