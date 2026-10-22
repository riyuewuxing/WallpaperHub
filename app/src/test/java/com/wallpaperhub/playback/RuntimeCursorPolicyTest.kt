package com.wallpaperhub.playback

import com.wallpaperhub.data.repo.PlaybackRuntimePolicy
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.ui.flow.CarouselRailPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCursorPolicyTest {

    @Test
    fun appliedSessionWithDifferentDesiredKeepsConfirmedAppliedAsEffectiveCursor() {
        val effective = PlaybackRuntimePolicy.effectiveCursorUri(
            appliedUri = "old",
            desiredUri = "next",
            phase = PlaybackPhase.APPLIED,
        )

        assertEquals("old", effective)
        assertTrue(
            PlaybackRuntimePolicy.canRecoverUncommittedRuntimeCursor(
                appliedUri = "old",
                desiredUri = "next",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun activeUserCommandDoesNotBecomeRecoveryCandidate() {
        val effective = PlaybackRuntimePolicy.effectiveCursorUri(
            appliedUri = "old",
            desiredUri = "command-target",
            phase = PlaybackPhase.TRANSITIONING,
        )

        assertEquals("old", effective)
        assertFalse(
            PlaybackRuntimePolicy.canRecoverUncommittedRuntimeCursor(
                appliedUri = "old",
                desiredUri = "command-target",
                phase = PlaybackPhase.TRANSITIONING,
            )
        )
    }

    @Test
    fun carouselFocusKeepsConfirmedDesktopDuringAutomaticRuntimeHandoff() {
        val queue = listOf("old", "next", "later")

        val focus = CarouselRailPolicy.focusUri(
            queueUris = queue,
            appliedUri = "old",
            desiredUri = "next",
            phase = PlaybackPhase.APPLIED,
        )

        assertEquals("old", focus)
    }

    @Test
    fun carouselFocusUsesDesiredDuringExplicitTransitionPhase() {
        val queue = listOf("old", "target", "later")

        val focus = CarouselRailPolicy.focusUri(
            queueUris = queue,
            appliedUri = "old",
            desiredUri = "target",
            phase = PlaybackPhase.TRANSITIONING,
        )

        assertEquals("target", focus)
    }

    @Test
    fun carouselFocusKeepsConfirmedAppliedWhenNoRuntimeMismatchExists() {
        val queue = listOf("current", "next")

        val focus = CarouselRailPolicy.focusUri(
            queueUris = queue,
            appliedUri = "current",
            desiredUri = "current",
            phase = PlaybackPhase.APPLIED,
        )

        assertEquals("current", focus)
    }
}
