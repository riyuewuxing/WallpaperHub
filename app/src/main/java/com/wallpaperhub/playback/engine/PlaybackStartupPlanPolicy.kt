package com.wallpaperhub.playback.engine

/** Process recreation must preserve a valid persisted concrete round instead of regenerating it. */
internal object PlaybackStartupPlanPolicy {
    fun shouldPreserve(
        canonical: List<String>,
        active: List<String>,
        currentUri: String?,
    ): Boolean {
        if (currentUri == null || canonical.isEmpty() || active.isEmpty()) return false
        if (canonical.size != active.size) return false
        if (canonical.toHashSet().size != canonical.size || active.toHashSet().size != active.size) return false
        return currentUri in active && canonical.toHashSet() == active.toHashSet()
    }
}
