package com.wallpaperhub.playback.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartupPlanPolicyTest {
    @Test
    fun sameMembershipPreservesPersistedOrder() {
        assertTrue(
            PlaybackStartupPlanPolicy.shouldPreserve(
                canonical = listOf("a", "b", "c", "d"),
                active = listOf("c", "a", "d", "b"),
                currentUri = "d",
            )
        )
    }

    @Test
    fun membershipChangeRequiresRegeneration() {
        assertFalse(
            PlaybackStartupPlanPolicy.shouldPreserve(
                canonical = listOf("a", "b", "c", "d"),
                active = listOf("a", "b", "c"),
                currentUri = "b",
            )
        )
    }

    @Test
    fun missingCurrentRequiresRegeneration() {
        assertFalse(
            PlaybackStartupPlanPolicy.shouldPreserve(
                canonical = listOf("a", "b", "c"),
                active = listOf("c", "a", "b"),
                currentUri = "missing",
            )
        )
    }

    @Test
    fun duplicatePlanIsNeverTrusted() {
        assertFalse(
            PlaybackStartupPlanPolicy.shouldPreserve(
                canonical = listOf("a", "b", "c"),
                active = listOf("a", "b", "b"),
                currentUri = "b",
            )
        )
    }
}
