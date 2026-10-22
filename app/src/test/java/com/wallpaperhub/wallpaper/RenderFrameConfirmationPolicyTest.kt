package com.wallpaperhub.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderFrameConfirmationPolicyTest {

    @Test
    fun `confirms only after target owns current slot and successful swap`() {
        assertTrue(
            RenderFrameConfirmationPolicy.canConfirm(
                swapSucceeded = true,
                sourceIsCurrent = true,
                sourceHasValidFrame = true,
                transitionInProgress = false,
                directVideoHandoffInProgress = false,
            )
        )
    }

    @Test
    fun `does not confirm a ready next source during crossfade`() {
        assertFalse(
            RenderFrameConfirmationPolicy.canConfirm(
                swapSucceeded = true,
                sourceIsCurrent = false,
                sourceHasValidFrame = true,
                transitionInProgress = true,
                directVideoHandoffInProgress = false,
            )
        )
    }

    @Test
    fun `does not confirm before direct video handoff commits`() {
        assertFalse(
            RenderFrameConfirmationPolicy.canConfirm(
                swapSucceeded = true,
                sourceIsCurrent = false,
                sourceHasValidFrame = true,
                transitionInProgress = false,
                directVideoHandoffInProgress = true,
            )
        )
    }

    @Test
    fun `does not confirm when egl swap fails`() {
        assertFalse(
            RenderFrameConfirmationPolicy.canConfirm(
                swapSucceeded = false,
                sourceIsCurrent = true,
                sourceHasValidFrame = true,
                transitionInProgress = false,
                directVideoHandoffInProgress = false,
            )
        )
    }

    @Test
    fun `does not confirm invalid source frame`() {
        assertFalse(
            RenderFrameConfirmationPolicy.canConfirm(
                swapSucceeded = true,
                sourceIsCurrent = true,
                sourceHasValidFrame = false,
                transitionInProgress = false,
                directVideoHandoffInProgress = false,
            )
        )
    }
}
