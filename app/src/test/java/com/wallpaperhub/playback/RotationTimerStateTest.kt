package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.RotationTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationTimerStateTest {

    @Test
    fun newlyResetHiddenWallpaperDoesNotConsumeIntervalBeforeFirstVisibleResume() {
        val timer = RotationTimerState()
        timer.reset(15_000L)

        timer.pause(1_000L)

        assertNull(timer.hiddenCatchUpDelay(1_000L))
        assertFalse(timer.isOverdue(60_000L))
        assertEquals(15_000L, timer.resumeDelay(60_000L, 15_000L))
    }

    @Test
    fun briefOemVisibilityPulseDoesNotConsumeFreshWallpaperInterval() {
        val timer = RotationTimerState()
        timer.reset(15_000L)

        assertEquals(15_000L, timer.resumeDelay(1_000L, 15_000L))
        // OriginOS can expose the production wallpaper for a few frames while an app/system
        // confirmation transition is still in progress. This must not count as the first desktop use.
        timer.pause(2_100L)

        assertNull(timer.hiddenCatchUpDelay(2_100L))
        assertFalse(timer.isArmed())
        assertFalse(timer.isOverdue(60_000L))
        assertEquals(15_000L, timer.resumeDelay(60_000L, 15_000L))
    }

    @Test
    fun stableFirstDesktopExposureCommitsAbsoluteDeadline() {
        val timer = RotationTimerState()
        timer.reset(15_000L)

        assertEquals(15_000L, timer.resumeDelay(1_000L, 15_000L))
        // More than the 2s OEM-pulse filter means the user genuinely reached the desktop.
        timer.pause(3_500L)

        assertTrue(timer.isArmed())
        assertEquals(12_500L, timer.hiddenCatchUpDelay(3_500L))
        // Deadline remains 16_000 even while another app covers the desktop.
        assertEquals(6_000L, timer.resumeDelay(10_000L, 15_000L))
    }

    @Test
    fun hiddenWallpaperKeepsAlreadyArmedLogicalDeadline() {
        val timer = RotationTimerState()
        timer.reset(15_000L)
        assertEquals(15_000L, timer.resumeDelay(1_000L, 15_000L))

        // Five seconds of real visibility commits the first exposure, then hidden app time counts.
        timer.pause(6_000L)

        assertEquals(10_000L, timer.hiddenCatchUpDelay(6_000L))
        assertEquals(5_000L, timer.resumeDelay(11_000L, 15_000L))
    }

    @Test
    fun overdueHiddenTimeCoalescesToOneImmediateCatchUp() {
        val timer = RotationTimerState()
        timer.reset(15_000L)
        timer.resumeDelay(1_000L, 15_000L)
        // Four seconds is a genuine first desktop exposure, so the absolute deadline survives hiding.
        timer.pause(5_000L)

        assertEquals(0L, timer.hiddenCatchUpDelay(40_000L))
        assertTrue(timer.isOverdue(40_000L))
        assertEquals(0L, timer.resumeDelay(40_000L, 15_000L))

        // The service performs one transition, calls fired(), and the new wallpaper gets a full
        // interval. We deliberately do not replay the multiple 15s windows missed while hidden.
        timer.fired()
        assertNull(timer.hiddenCatchUpDelay(40_000L))
        assertEquals(15_000L, timer.resumeDelay(40_000L, 15_000L))
    }

    @Test
    fun intervalChangeReplacesOldDeadline() {
        val timer = RotationTimerState()
        timer.reset(15_000L)
        timer.resumeDelay(1_000L, 15_000L)

        assertEquals(30_000L, timer.resumeDelay(8_000L, 30_000L))
    }
}
