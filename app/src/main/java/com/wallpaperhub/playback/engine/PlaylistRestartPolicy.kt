package com.wallpaperhub.playback.engine

import kotlin.random.Random

/** Builds the concrete round shown to the user whenever a wallpaper is applied. */
internal object PlaylistRestartPolicy {
    fun build(
        currentQueue: List<String>,
        selectedUri: String,
        shuffle: Boolean,
        random: Random = Random.Default,
    ): List<String> {
        val uniqueQueue = currentQueue.distinct()
        val selectedIndex = uniqueQueue.indexOf(selectedUri)
        val remaining = if (selectedIndex >= 0) {
            uniqueQueue.drop(selectedIndex + 1) + uniqueQueue.take(selectedIndex)
        } else {
            uniqueQueue
        }
        return listOf(selectedUri) + if (shuffle) remaining.shuffled(random) else remaining
    }
}

/** A slow decoder gets a bounded retry window before the apply is reported as failed. */
internal object PlaybackConfirmationPolicy {
    fun isRecoverable(errorCode: String): Boolean = errorCode == "first_frame_timeout"

    fun shouldRetryFirstFrame(errorCode: String, attempt: Int, maxAttempts: Int): Boolean =
        isRecoverable(errorCode) && attempt < maxAttempts

    fun shouldHoldRotation(pendingCommandId: String?, pendingFrameUri: String? = null): Boolean =
        pendingCommandId != null || pendingFrameUri != null

    /** A command can arrive after a playlist refresh already rendered its target. */
    fun canConfirmExistingTarget(
        commandId: String?,
        commandTargetUri: String?,
        renderedUri: String?,
    ): Boolean = commandId != null && commandTargetUri != null && commandTargetUri == renderedUri

    fun shouldShowAppliedLabel(entityUri: String?, appliedUri: String?): Boolean =
        entityUri != null && entityUri == appliedUri


}
