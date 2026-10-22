package com.wallpaperhub.preview

import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.model.PlaybackSession
import com.wallpaperhub.preview.model.ApplyLaunchStep
import com.wallpaperhub.preview.model.PreviewApplyPolicy
import com.wallpaperhub.preview.model.PreviewApplyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewApplyPolicyTest {

    @Test
    fun openingSystemConfirmationNeverClaimsWallpaperWasApplied() {
        assertEquals(
            PreviewApplyState.WAITING_FOR_SYSTEM_CONFIRMATION,
            PreviewApplyPolicy.afterLaunch(ApplyLaunchStep.SYSTEM_CONFIRMATION_REQUIRED),
        )
    }

    @Test
    fun activeServiceCommandWaitsForRendererConfirmation() {
        assertEquals(
            PreviewApplyState.VERIFYING_APPLIED_WALLPAPER,
            PreviewApplyPolicy.afterLaunch(ApplyLaunchStep.COMMAND_SUBMITTED),
        )
    }

    @Test
    fun onlyMatchingEngineAppliedSessionBecomesApplied() {
        val target = "file:///target.mp4"

        assertEquals(
            PreviewApplyState.APPLIED,
            PreviewApplyPolicy.fromSession(
                target,
                PlaybackSession(
                    desiredUri = target,
                    appliedUri = target,
                    phase = PlaybackPhase.APPLIED,
                ),
            ),
        )
        assertNull(
            PreviewApplyPolicy.fromSession(
                target,
                PlaybackSession(
                    desiredUri = target,
                    appliedUri = "file:///old.mp4",
                    phase = PlaybackPhase.APPLIED,
                ),
            ),
        )
    }

    @Test
    fun matchingEngineFailureBecomesError() {
        val target = "file:///target.mp4"

        assertEquals(
            PreviewApplyState.ERROR,
            PreviewApplyPolicy.fromSession(
                target,
                PlaybackSession(
                    desiredUri = target,
                    phase = PlaybackPhase.FAILED,
                ),
            ),
        )
    }

    @Test
    fun returningWithoutAnActiveWallpaperServiceIsTreatedAsCancellation() {
        assertEquals(
            PreviewApplyState.ERROR,
            PreviewApplyPolicy.afterSystemReturn(serviceActive = false),
        )
        assertEquals(
            PreviewApplyState.VERIFYING_APPLIED_WALLPAPER,
            PreviewApplyPolicy.afterSystemReturn(serviceActive = true),
        )
    }
}
