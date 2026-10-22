package com.wallpaperhub.wallpaper

/**
 * Defines the exact moment at which a prepared source may become durable playback state.
 *
 * A decoder callback or a valid texture alone is not enough. The target must own the renderer's
 * current slot, any handoff/crossfade must be finished, and the frame must have reached a successful
 * EGL swap. This keeps UI/Room rotation state aligned with pixels that actually reached the desktop.
 */
internal object RenderFrameConfirmationPolicy {
    fun canConfirm(
        swapSucceeded: Boolean,
        sourceIsCurrent: Boolean,
        sourceHasValidFrame: Boolean,
        transitionInProgress: Boolean,
        directVideoHandoffInProgress: Boolean,
    ): Boolean = swapSucceeded &&
        sourceIsCurrent &&
        sourceHasValidFrame &&
        !transitionInProgress &&
        !directVideoHandoffInProgress
}
