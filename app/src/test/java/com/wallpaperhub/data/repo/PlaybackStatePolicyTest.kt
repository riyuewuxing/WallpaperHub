package com.wallpaperhub.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wallpaperhub.playback.model.PlaybackPhase

class PlaybackStatePolicyTest {
    @Test
    fun unchangedQueueKeepsRevisionWhenOnlyCurrentPositionChanges() {
        assertEquals(7L, PlaybackStatePolicy.nextPlaylistRevision(7L, listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun changedQueueAdvancesRevision() {
        assertEquals(8L, PlaybackStatePolicy.nextPlaylistRevision(7L, listOf("a", "b"), listOf("b", "a")))
    }

    @Test
    fun compactFingerprintKeepsRevisionStableWithoutPersistingTheQueue() {
        assertEquals(7L, PlaybackStatePolicy.nextPlaylistRevision(7L, "same", "same"))
        assertEquals(8L, PlaybackStatePolicy.nextPlaylistRevision(7L, "before", "after"))
    }

    @Test
    fun aCommandCannotUseAnOlderRevisionThanThePlaylist() {
        assertEquals(9L, PlaybackStatePolicy.effectiveCommandRevision(3L, 9L))
    }

    @Test
    fun staleAppliedRevisionIsRejectedButSameRevisionIsAccepted() {
        assertFalse(PlaybackStatePolicy.acceptsAppliedRevision(5L, 4L))
        assertTrue(PlaybackStatePolicy.acceptsAppliedRevision(5L, 5L))
    }

    @Test
    fun lateProgressUpdateCannotOverwriteTerminalAppliedState() {
        assertFalse(
            PlaybackStatePolicy.acceptsPhaseUpdate(
                currentCommandId = "apply-1",
                currentPhase = PlaybackPhase.APPLIED,
                commandId = "apply-1",
                nextPhase = PlaybackPhase.TRANSITIONING,
            ),
        )
        assertTrue(
            PlaybackStatePolicy.acceptsPhaseUpdate(
                currentCommandId = "apply-1",
                currentPhase = PlaybackPhase.TRANSITIONING,
                commandId = "apply-1",
                nextPhase = PlaybackPhase.APPLIED,
            ),
        )
    }
}
