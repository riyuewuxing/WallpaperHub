package com.wallpaperhub.playback.engine

/**
 * Centralizes the difference between the framework's wallpaper visibility flag and what the user can
 * actually see. Some OEMs keep Engine.onVisibilityChanged(true) across SCREEN_OFF; screen state must
 * therefore participate in every lifecycle decision that is meant to describe real user visibility.
 */
internal object WallpaperVisibilityPolicy {
    fun isActuallyVisible(visible: Boolean, screenOn: Boolean): Boolean =
        visible && screenOn

    fun shouldPauseVideoDecoder(screenOn: Boolean, powerSave: Boolean): Boolean =
        !screenOn || powerSave

    /**
     * Keep the runtime decoder warm across short screen-on app/system overlays. This avoids
     * a pause/resume codec bubble when returning home, while previews, screen-off and power
     * save still pause immediately. The service bounds the grace period with a timer.
     */
    fun shouldDeferVideoDecoderPause(
        visible: Boolean,
        screenOn: Boolean,
        powerSave: Boolean,
        isPreview: Boolean,
    ): Boolean =
        !isPreview && screenOn && !powerSave && !visible

    fun shouldRunContinuousRendering(
        visible: Boolean,
        screenOn: Boolean,
        powerSave: Boolean,
    ): Boolean =
        isActuallyVisible(visible, screenOn) && !powerSave

    fun canRunHiddenCatchUp(
        visible: Boolean,
        screenOn: Boolean,
        powerSave: Boolean,
        isPreview: Boolean,
        engineDestroyed: Boolean,
    ): Boolean =
        !engineDestroyed &&
            !isPreview &&
            !powerSave &&
            !isActuallyVisible(visible, screenOn)
}
