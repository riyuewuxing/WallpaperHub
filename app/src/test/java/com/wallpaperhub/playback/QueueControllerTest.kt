package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.QueueController
import com.wallpaperhub.playback.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueControllerTest {

    private val queue = listOf("wallpaper-a", "wallpaper-b", "wallpaper-c")

    @Test
    fun sequentialNavigationConsumesPhysicalPlan() {
        val controller = QueueController(queue, PlaybackMode.SEQUENTIAL, currentUri = "wallpaper-b")

        assertEquals("wallpaper-c", controller.next())
        assertEquals("wallpaper-b", controller.previous())
        assertEquals("wallpaper-a", controller.previous())
    }

    @Test
    fun missingCurrentUriRecoversToFirstPlanItem() {
        val controller = QueueController(queue, PlaybackMode.SEQUENTIAL, currentUri = "removed")

        assertEquals("wallpaper-a", controller.current())
        assertEquals("wallpaper-b", controller.next())
    }

    @Test
    fun materializedShuffleFollowsPersistedPhysicalOrder() {
        val materialized = listOf("wallpaper-c", "wallpaper-a", "wallpaper-b")
        val controller = QueueController(materialized, PlaybackMode.SHUFFLE, currentUri = "wallpaper-c")

        assertEquals("wallpaper-a", controller.peekNext())
        assertEquals("wallpaper-a", controller.next())
        assertEquals("wallpaper-b", controller.next())
        assertTrue(controller.willCompleteShuffleRoundOnNext())
    }

    @Test
    fun materializedRandomAlsoFollowsPersistedFullOrder() {
        val materialized = listOf("current", "random-c", "random-a", "random-b")
        val controller = QueueController(
            materialized,
            PlaybackMode.RANDOM,
            currentUri = "current",
            // Kept only to prove old constructor call sites remain source-compatible. It must not
            // create a second hidden random order inside the renderer.
            random = Random(7),
        )

        assertEquals("random-c", controller.peekNext())
        assertEquals("random-c", controller.next())
        assertEquals("random-a", controller.next())
        assertEquals("random-b", controller.next())
        assertTrue(controller.willCompleteRandomRoundOnNext())
    }

    @Test
    fun previousUsesHistoryBeforeFallingBackToCircularOrder() {
        val controller = QueueController(queue, PlaybackMode.SEQUENTIAL, currentUri = "wallpaper-a")

        controller.next()
        controller.next()

        assertEquals("wallpaper-b", controller.previous())
        assertEquals("wallpaper-a", controller.previous())
    }

    @Test
    fun replacingQueuePreservesRequestedCurrent() {
        val controller = QueueController(queue, PlaybackMode.SEQUENTIAL, currentUri = "wallpaper-b")
        controller.next()

        controller.setQueue(listOf("wallpaper-c", "wallpaper-d"), currentUri = "wallpaper-c")

        assertEquals("wallpaper-c", controller.current())
        assertEquals("wallpaper-d", controller.next())
        assertEquals("wallpaper-c", controller.previous())
    }

    @Test
    fun restartingARoundFollowsTheNewlyMaterializedSequence() {
        val controller = QueueController(queue, PlaybackMode.SHUFFLE, currentUri = "wallpaper-a")

        controller.restartRound(
            newQueue = listOf("wallpaper-c", "wallpaper-a", "wallpaper-b"),
            currentUri = "wallpaper-c",
        )

        assertEquals("wallpaper-c", controller.current())
        assertEquals("wallpaper-a", controller.next())
        assertEquals("wallpaper-b", controller.next())
    }

    @Test
    fun modeSwitchDoesNotInventANewOrderInsideController() {
        val controller = QueueController(
            initialQueue = listOf("current", "next", "later"),
            mode = PlaybackMode.SHUFFLE,
            currentUri = "current",
        )

        controller.setMode(PlaybackMode.RANDOM)

        assertEquals("next", controller.next())
        assertEquals("later", controller.next())
    }

    @Test
    fun largeMaterializedRoundRemainsLinearAndUnchanged() {
        val largeQueue = (0 until 10_000).map { "wallpaper-$it" }
        val selected = "wallpaper-7342"
        val materialized = listOf(selected) + largeQueue.filter { it != selected }
        val controller = QueueController(
            initialQueue = largeQueue,
            mode = PlaybackMode.SHUFFLE,
            currentUri = "wallpaper-1",
        )

        controller.restartRound(materialized, currentUri = selected)
        val ordered = controller.orderedQueue()

        assertEquals(10_000, ordered.size)
        assertEquals(10_000, ordered.toSet().size)
        assertEquals(selected, ordered.first())
        assertEquals(materialized, ordered)
    }

    @Test
    fun emptyQueueHasStableState() {
        val controller = QueueController(emptyList(), PlaybackMode.RANDOM, random = Random(1))

        assertEquals(null, controller.current())
        assertEquals(null, controller.next())
        assertEquals(null, controller.previous())
        assertEquals(-1, controller.index())
    }
}
