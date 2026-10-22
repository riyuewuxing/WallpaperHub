package com.wallpaperhub.playback.engine

/** Decides whether a newly bound wallpaper service must recreate a lost UI apply command. */
internal object ServiceApplyRecoveryPolicy {
    fun shouldSubmit(
        desiredUri: String?,
        appliedUri: String?,
        latestCommandTargetUri: String?,
        serviceUri: String?,
    ): Boolean =
        serviceUri != null &&
            desiredUri == serviceUri &&
            desiredUri != appliedUri &&
            latestCommandTargetUri != desiredUri
}
