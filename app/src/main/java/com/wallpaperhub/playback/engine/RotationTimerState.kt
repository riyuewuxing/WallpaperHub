package com.wallpaperhub.playback.engine

/**
 * A visibility-aware logical clock for wallpaper rotation.
 *
 * Once a wallpaper has genuinely reached the desktop it owns an absolute elapsedRealtime deadline.
 * Hiding the wallpaper removes the visible scheduled Runnable in the service, but does NOT freeze
 * that deadline. The service may consume that one deadline while hidden so the replacement is ready
 * before the launcher exposes the wallpaper again.
 *
 * OEM launchers may briefly report the production wallpaper visible while an app/system-confirmation
 * transition is still in progress. A freshly reset interval therefore starts provisionally: if that
 * first exposure disappears again within [INITIAL_VISIBILITY_COMMIT_MS], it is discarded and the
 * wallpaper keeps its complete interval for the next real desktop exposure.
 */
class RotationTimerState {
    private var configuredIntervalMs: Long = 0L
    private var deadlineMs: Long = 0L
    private var remainingMs: Long = 0L
    private var provisionalArmStartedAtMs: Long = 0L
    private var freshIntervalNeedsStableVisibility = false

    fun clear() {
        configuredIntervalMs = 0L
        deadlineMs = 0L
        remainingMs = 0L
        provisionalArmStartedAtMs = 0L
        freshIntervalNeedsStableVisibility = false
    }

    /** A user interval change/new wallpaper gives the current item a fresh, not-yet-armed interval. */
    fun reset(intervalMs: Long) {
        configuredIntervalMs = intervalMs.coerceAtLeast(0L)
        deadlineMs = 0L
        remainingMs = configuredIntervalMs
        provisionalArmStartedAtMs = 0L
        freshIntervalNeedsStableVisibility = configuredIntervalMs > 0L
    }

    /**
     * Normally hiding preserves the absolute deadline. The exception is the very first visibility
     * pulse after reset(): vivo/OriginOS and other launchers can expose the production wallpaper for
     * only a few frames while returning between activities. Counting that pulse is what made a newly
     * applied 15-second wallpaper arrive on the real desktop with only 1-2 seconds left.
     */
    fun pause(nowMs: Long) {
        if (deadlineMs <= 0L || !freshIntervalNeedsStableVisibility || provisionalArmStartedAtMs <= 0L) {
            return
        }
        val visibleForMs = (nowMs - provisionalArmStartedAtMs).coerceAtLeast(0L)
        if (visibleForMs < INITIAL_VISIBILITY_COMMIT_MS) {
            deadlineMs = 0L
            remainingMs = configuredIntervalMs
            provisionalArmStartedAtMs = 0L
        } else {
            // This was a real desktop exposure. From now on hidden app time intentionally counts.
            freshIntervalNeedsStableVisibility = false
            provisionalArmStartedAtMs = 0L
        }
    }

    /**
     * Arm/resume and return the delay to schedule. An expired deadline returns 0 so the service can
     * perform one catch-up transition instead of replaying every missed interval.
     */
    fun resumeDelay(nowMs: Long, intervalMs: Long): Long {
        val safeInterval = intervalMs.coerceAtLeast(0L)
        if (safeInterval <= 0L) {
            clear()
            return 0L
        }
        if (configuredIntervalMs != safeInterval) {
            reset(safeInterval)
        }
        if (deadlineMs <= 0L) {
            if (remainingMs <= 0L) remainingMs = safeInterval
            deadlineMs = nowMs + remainingMs
            if (freshIntervalNeedsStableVisibility) {
                provisionalArmStartedAtMs = nowMs
            }
        }
        return (deadlineMs - nowMs).coerceAtLeast(0L)
    }

    /**
     * Returns the already-armed deadline delay for the single hidden catch-up, without arming a fresh
     * wallpaper. A provisional OEM visibility pulse is discarded by [pause] first, so it returns null.
     */
    fun hiddenCatchUpDelay(nowMs: Long): Long? =
        deadlineMs.takeIf { it > 0L }?.let { (it - nowMs).coerceAtLeast(0L) }

    /** A real rotation consumes the deadline; the next wallpaper gets one fresh complete interval. */
    fun fired() {
        deadlineMs = 0L
        remainingMs = configuredIntervalMs
        provisionalArmStartedAtMs = 0L
        freshIntervalNeedsStableVisibility = configuredIntervalMs > 0L
    }

    fun remaining(nowMs: Long): Long = when {
        deadlineMs > 0L -> (deadlineMs - nowMs).coerceAtLeast(0L)
        else -> remainingMs.coerceAtLeast(0L)
    }

    fun interval(): Long = configuredIntervalMs

    fun isArmed(): Boolean = deadlineMs > 0L

    fun isOverdue(nowMs: Long): Boolean = deadlineMs > 0L && nowMs >= deadlineMs

    companion object {
        /** Filters OEM/activity-transition visibility pulses without making normal desktop use feel delayed. */
        internal const val INITIAL_VISIBILITY_COMMIT_MS = 2_000L
    }
}
