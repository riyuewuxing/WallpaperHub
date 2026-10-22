package com.wallpaperhub.data.repo

import com.wallpaperhub.playback.model.PlaybackPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveApplyCommandPolicyTest {
    @Test
    fun differentUiTargetCanWaitForPlaylistDrivenFirstFrame() {
        assertTrue(
            ActiveApplyCommandPolicy.shouldAwaitRuntimeConfirmation(
                source = "ui",
                targetUri = "file:///new.mp4",
                desiredUri = "file:///new.mp4",
                appliedUri = "file:///old.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun sameUriReapplyNeverCoalesces() {
        assertFalse(
            ActiveApplyCommandPolicy.shouldAwaitRuntimeConfirmation(
                source = "ui",
                targetUri = "file:///same.mp4",
                desiredUri = "file:///same.mp4",
                appliedUri = "file:///same.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun nonUiSourcesKeepExistingCommandPath() {
        assertFalse(
            ActiveApplyCommandPolicy.shouldAwaitRuntimeConfirmation(
                source = "system_confirmation_recovery",
                targetUri = "file:///new.mp4",
                desiredUri = "file:///new.mp4",
                appliedUri = "file:///old.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun terminalCommandCanBeCommittedAfterRuntimeConfirmsTarget() {
        assertTrue(
            ActiveApplyCommandPolicy.canCommitTerminalApply(
                source = "ui",
                targetUri = "file:///new.mp4",
                expectedRevision = 301L,
                currentRevision = 301L,
                desiredUri = "file:///new.mp4",
                appliedUri = "file:///new.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun staleRevisionCannotCommitTerminalCommand() {
        assertFalse(
            ActiveApplyCommandPolicy.canCommitTerminalApply(
                source = "ui",
                targetUri = "file:///new.mp4",
                expectedRevision = 302L,
                currentRevision = 301L,
                desiredUri = "file:///new.mp4",
                appliedUri = "file:///new.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }

    @Test
    fun unconfirmedTargetCannotCommitTerminalCommand() {
        assertFalse(
            ActiveApplyCommandPolicy.canCommitTerminalApply(
                source = "ui",
                targetUri = "file:///new.mp4",
                expectedRevision = 301L,
                currentRevision = 301L,
                desiredUri = "file:///new.mp4",
                appliedUri = "file:///old.mp4",
                phase = PlaybackPhase.APPLIED,
            )
        )
    }
}
