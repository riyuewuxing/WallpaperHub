package com.wallpaperhub.data.repo

import com.wallpaperhub.playback.model.PlaybackPhase

/**
 * Coalesces an active-service UI APPLY with the renderer work already started by the playlist
 * revision that immediately precedes the command.
 *
 * A same-URI reapply is intentionally excluded: it may carry a new crop transform and must still
 * execute a real restart. The fast path is only for a different target that the runtime has since
 * proved visible at the expected (or newer) queue revision.
 */
internal object ActiveApplyCommandPolicy {
    fun shouldAwaitRuntimeConfirmation(
        source: String,
        targetUri: String,
        desiredUri: String?,
        appliedUri: String?,
        phase: PlaybackPhase,
    ): Boolean =
        source == "ui" &&
            desiredUri == targetUri &&
            appliedUri != targetUri &&
            phase == PlaybackPhase.APPLIED

    fun canCommitTerminalApply(
        source: String,
        targetUri: String,
        expectedRevision: Long,
        currentRevision: Long,
        desiredUri: String?,
        appliedUri: String?,
        phase: PlaybackPhase,
    ): Boolean =
        source == "ui" &&
            desiredUri == targetUri &&
            appliedUri == targetUri &&
            phase == PlaybackPhase.APPLIED &&
            currentRevision >= expectedRevision
}
