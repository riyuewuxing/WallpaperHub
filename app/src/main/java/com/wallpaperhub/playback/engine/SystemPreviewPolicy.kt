package com.wallpaperhub.playback.engine

/**
 * Keeps the system live-wallpaper preview isolated from the long-lived carousel runtime.
 *
 * A preview Engine exists only to render the selected target as quickly as possible. It must not
 * subscribe to production playback state, rebuild the full queue, publish runtime position, or
 * spend first-frame time extracting wallpaper colors.
 */
object SystemPreviewPolicy {
    fun useFastPath(isPreview: Boolean): Boolean = isPreview

    fun shouldObserveRuntime(isPreview: Boolean): Boolean = !isPreview

    fun shouldPublishRuntimePosition(isPreview: Boolean): Boolean = !isPreview

    fun shouldExtractWallpaperColors(isPreview: Boolean): Boolean = !isPreview
}
