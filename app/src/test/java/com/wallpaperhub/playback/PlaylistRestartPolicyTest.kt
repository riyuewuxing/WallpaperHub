package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.PlaylistRestartPolicy
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRestartPolicyTest {
    @Test
    fun sequentialRestartMovesSelectedWallpaperToTheFrontAndWrapsTheExistingOrder() {
        val restarted = PlaylistRestartPolicy.build(
            currentQueue = listOf("a", "b", "c", "d"),
            selectedUri = "c",
            shuffle = false,
        )

        assertEquals(listOf("c", "d", "a", "b"), restarted)
    }

    @Test
    fun wallpaperOutsideTheQueueBecomesTheFirstItemWithoutDroppingAssets() {
        val restarted = PlaylistRestartPolicy.build(
            currentQueue = listOf("a", "b", "c"),
            selectedUri = "new",
            shuffle = false,
        )

        assertEquals(listOf("new", "a", "b", "c"), restarted)
    }

    @Test
    fun shuffleCreatesOneConcreteRoundWithTheSelectedWallpaperFirst() {
        val restarted = PlaylistRestartPolicy.build(
            currentQueue = listOf("a", "b", "c", "d", "e", "f"),
            selectedUri = "d",
            shuffle = true,
            random = Random(17),
        )

        assertEquals("d", restarted.first())
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), restarted.toSet())
        assertEquals(6, restarted.size)
    }
}
