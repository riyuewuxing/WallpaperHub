package com.wallpaperhub.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTransitionPolicyTest {
    @Test
    fun videoToVideoSwitchDoesNotAllocateAFullScreenDoubleFbo() {
        assertFalse(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = true, nextIsVideo = true))
        assertTrue(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = false, nextIsVideo = true))
    }

    @Test
    fun explicitHiddenCatchUpSuppressionBypassesCrossfadeOnlyInsideScope() {
        assertTrue(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = false, nextIsVideo = false))

        RenderTransitionPolicy.withoutOffscreenTransition {
            assertFalse(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = false, nextIsVideo = false))
            assertFalse(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = false, nextIsVideo = true))
            assertFalse(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = true, nextIsVideo = false))
        }

        assertTrue(RenderTransitionPolicy.useOffscreenTransition(currentIsVideo = false, nextIsVideo = false))
    }

    @Test
    fun videoToVideoSwitchKeepsTheCurrentFrameUntilTheNextFrameIsReady() {
        assertTrue(RenderTransitionPolicy.shouldPreserveCurrentFrame(currentIsVideo = true, nextIsVideo = true))
        assertFalse(RenderTransitionPolicy.shouldPreserveCurrentFrame(currentIsVideo = false, nextIsVideo = true))
    }
}
