package com.wallpaperhub.data.repo

import com.wallpaperhub.playback.model.PlaybackPhase

/** Pure rules for keeping the durable playlist revision monotonic. */
internal object PlaybackStatePolicy {
    fun nextPlaylistRevision(previousRevision: Long?, previousQueue: List<String>?, newQueue: List<String>): Long {
        if (previousRevision == null) return 1L
        return if (previousQueue == newQueue) previousRevision else previousRevision + 1L
    }

    fun nextPlaylistRevision(
        previousRevision: Long?,
        previousFingerprint: String?,
        newFingerprint: String,
    ): Long {
        if (previousRevision == null) return 1L
        return if (previousFingerprint == newFingerprint) previousRevision else previousRevision + 1L
    }

    fun effectiveCommandRevision(requestedRevision: Long, currentRevision: Long): Long =
        maxOf(requestedRevision, currentRevision)

    fun acceptsAppliedRevision(currentRevision: Long, appliedRevision: Long): Boolean =
        appliedRevision >= currentRevision

    fun acceptsPhaseUpdate(
        currentCommandId: String?,
        currentPhase: PlaybackPhase,
        commandId: String,
        nextPhase: PlaybackPhase,
    ): Boolean {
        if (currentCommandId != null && currentCommandId != commandId) return false
        if (currentCommandId == commandId &&
            currentPhase in setOf(PlaybackPhase.APPLIED, PlaybackPhase.FAILED) &&
            nextPhase != currentPhase
        ) {
            return false
        }
        return true
    }
}
