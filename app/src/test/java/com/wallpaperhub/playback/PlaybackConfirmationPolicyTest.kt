package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.PlaybackConfirmationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConfirmationPolicyTest {
    @Test
    fun slowFirstFrameTimeoutIsNonFatalButRendererFailuresStillFail() {
        assertTrue(PlaybackConfirmationPolicy.isRecoverable("first_frame_timeout"))
        assertFalse(PlaybackConfirmationPolicy.isRecoverable("renderer_unavailable"))
    }

    @Test
    fun firstFrameTimeoutIsRetriedBeforeItBecomesTerminal() {
        assertTrue(PlaybackConfirmationPolicy.shouldRetryFirstFrame("first_frame_timeout", 1, 3))
        assertTrue(PlaybackConfirmationPolicy.shouldRetryFirstFrame("first_frame_timeout", 2, 3))
        assertFalse(PlaybackConfirmationPolicy.shouldRetryFirstFrame("first_frame_timeout", 3, 3))
        assertFalse(PlaybackConfirmationPolicy.shouldRetryFirstFrame("renderer_unavailable", 1, 3))
    }

    @Test
    fun renderedTargetStillConfirmsWhenPlaylistRefreshWinsTheRace() {
        assertTrue(
            PlaybackConfirmationPolicy.canConfirmExistingTarget(
                commandId = "cmd-1",
                commandTargetUri = "file:///selected.mp4",
                renderedUri = "file:///selected.mp4",
            ),
        )
        assertFalse(
            PlaybackConfirmationPolicy.canConfirmExistingTarget(
                commandId = "cmd-1",
                commandTargetUri = "file:///selected.mp4",
                renderedUri = "file:///older.mp4",
            ),
        )
    }

    @Test
    fun rotationWaitsUntilThePendingApplyIsConfirmed() {
        assertTrue(PlaybackConfirmationPolicy.shouldHoldRotation("cmd-1"))
        assertFalse(PlaybackConfirmationPolicy.shouldHoldRotation(null))
        assertTrue(PlaybackConfirmationPolicy.shouldHoldRotation(null, "file:///next.mp4"))
    }

    @Test
    fun appliedWallpaperRemainsCurrentWhileANewCommandIsTransitioning() {
        assertTrue(
            PlaybackConfirmationPolicy.shouldShowAppliedLabel(
                entityUri = "file:///old.mp4",
                appliedUri = "file:///old.mp4",
            ),
        )
        assertFalse(
            PlaybackConfirmationPolicy.shouldShowAppliedLabel(
                entityUri = "file:///new.mp4",
                appliedUri = "file:///old.mp4",
            ),
        )
    }
}
