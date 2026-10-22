package com.wallpaperhub.data.repo

import com.wallpaperhub.playback.model.PlaybackPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimePolicyTest {
    @Test
    fun runtimePositionCannotReplaceACommandThatIsStillRendering() {
        assertFalse(
            PlaybackRuntimePolicy.canCommitRuntimePosition(
                commandId = "apply-1",
                phase = PlaybackPhase.TRANSITIONING,
            ),
        )
        assertTrue(
            PlaybackRuntimePolicy.canCommitRuntimePosition(
                commandId = null,
                phase = PlaybackPhase.APPLIED,
            ),
        )
    }
}
