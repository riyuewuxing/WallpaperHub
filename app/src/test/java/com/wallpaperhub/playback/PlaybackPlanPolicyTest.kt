package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.PlaybackPlanPolicy
import com.wallpaperhub.playback.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackPlanPolicyTest {

    @Test
    fun sequentialNewestFirstAnchorsCurrentThenRestartsAtNewest() {
        val newestFirst = listOf("newest", "newer", "current", "oldest")
        val plan = PlaybackPlanPolicy.materialize(
            canonical = newestFirst,
            currentUri = "current",
            mode = PlaybackMode.SEQUENTIAL,
        )

        assertEquals(listOf("current", "newest", "newer", "oldest"), plan)
    }

    @Test
    fun sequentialOldestFirstAnchorsCurrentThenRestartsAtOldest() {
        val newestFirst = listOf("newest", "newer", "current", "oldest")
        val plan = PlaybackPlanPolicy.materialize(
            canonical = newestFirst,
            currentUri = "current",
            mode = PlaybackMode.SEQUENTIAL,
            reverseSequential = true,
        )

        assertEquals(listOf("current", "oldest", "newer", "newest"), plan)
    }

    @Test
    fun shufflePlanKeepsCurrentFirstAndContainsEveryMemberOnce() {
        val canonical = (0 until 100).map { "w$it" }
        val plan = PlaybackPlanPolicy.materialize(
            canonical = canonical,
            currentUri = "w42",
            mode = PlaybackMode.SHUFFLE,
            random = Random(7),
        )

        assertEquals("w42", plan.first())
        assertEquals(canonical.size, plan.size)
        assertEquals(canonical.toSet(), plan.toSet())
        assertFalse(plan.drop(1).contains("w42"))
    }

    @Test
    fun randomPlanIsAlsoAFullVisiblePermutationAnchoredAtCurrent() {
        val canonical = (0 until 100).map { "w$it" }
        val plan = PlaybackPlanPolicy.materialize(
            canonical = canonical,
            currentUri = "w42",
            mode = PlaybackMode.RANDOM,
            random = Random(17),
        )

        assertEquals("w42", plan.first())
        assertEquals(canonical.size, plan.size)
        assertEquals(canonical.toSet(), plan.toSet())
        assertFalse(plan.drop(1).contains("w42"))
    }

    @Test
    fun tappingRandomAgainCanCreateANewFullPermutationWithoutMovingCurrent() {
        val canonical = (0 until 40).map { "w$it" }
        val first = PlaybackPlanPolicy.materialize(
            canonical,
            "w12",
            PlaybackMode.RANDOM,
            random = Random(1),
        )
        val second = PlaybackPlanPolicy.materialize(
            canonical,
            "w12",
            PlaybackMode.RANDOM,
            random = Random(2),
        )

        assertEquals("w12", first.first())
        assertEquals("w12", second.first())
        assertEquals(canonical.toSet(), first.toSet())
        assertEquals(canonical.toSet(), second.toSet())
        assertNotEquals(first.drop(1), second.drop(1))
    }

    @Test
    fun importRegenerationIncludesEveryNewMemberInTheFreshRandomPlan() {
        val afterImport = PlaybackPlanPolicy.materialize(
            canonical = listOf("new-x", "newest", "current", "old"),
            currentUri = "current",
            mode = PlaybackMode.RANDOM,
            random = Random(8),
        )

        assertEquals("current", afterImport.first())
        assertEquals(setOf("new-x", "newest", "current", "old"), afterImport.toSet())
        assertEquals(4, afterImport.size)
    }

    @Test
    fun importRegenerationIncludesEveryNewMemberInFreshShuffle() {
        val afterImport = PlaybackPlanPolicy.materialize(
            canonical = listOf("new-x", "newest", "current", "old"),
            currentUri = "current",
            mode = PlaybackMode.SHUFFLE,
            random = Random(9),
        )

        assertEquals("current", afterImport.first())
        assertEquals(setOf("new-x", "newest", "current", "old"), afterImport.toSet())
        assertEquals(4, afterImport.size)
    }

    @Test
    fun missingCurrentFallsBackToNewestCanonicalItem() {
        val plan = PlaybackPlanPolicy.materialize(
            canonical = listOf("newest", "middle", "oldest"),
            currentUri = "removed",
            mode = PlaybackMode.SEQUENTIAL,
        )
        assertEquals(listOf("newest", "middle", "oldest"), plan)
    }

    @Test
    fun nextRandomRoundKeepsCurrentFirstAndEveryMemberOnce() {
        val plan = PlaybackPlanPolicy.nextRandomRound(
            members = listOf("a", "b", "c", "d"),
            currentUri = "d",
            random = Random(5),
        )
        assertEquals("d", plan.first())
        assertEquals(setOf("a", "b", "c", "d"), plan.toSet())
        assertFalse(plan.drop(1).contains("d"))
    }

    @Test
    fun nextShuffleRoundKeepsCurrentFirstAndEveryMemberOnce() {
        val plan = PlaybackPlanPolicy.nextShuffleRound(
            members = listOf("a", "b", "c", "d"),
            currentUri = "d",
            random = Random(5),
        )
        assertEquals("d", plan.first())
        assertEquals(setOf("a", "b", "c", "d"), plan.toSet())
        assertFalse(plan.drop(1).contains("d"))
    }

    @Test
    fun removeFromActivePreservesSurvivorOrder() {
        val after = PlaybackPlanPolicy.removeFromActive(
            listOf("current", "a", "b", "c"),
            setOf("b"),
        )
        assertEquals(listOf("current", "a", "c"), after)
    }

    @Test
    fun sequentialSuccessorHelperStillFindsCircularSuccessor() {
        val next = PlaybackPlanPolicy.sequentialSuccessorAfterRemoval(
            oldCanonical = listOf("a", "b", "c", "d"),
            removedCurrentUri = "c",
            remaining = setOf("a", "b", "d"),
        )
        assertEquals("d", next)
    }

    @Test
    fun randomizedPlanNeverDuplicatesMembersAtLargeScale() {
        val canonical = (0 until 10_000).map { "wallpaper-$it" }
        val plan = PlaybackPlanPolicy.materialize(
            canonical = canonical,
            currentUri = "wallpaper-7342",
            mode = PlaybackMode.RANDOM,
            random = Random(77),
        )

        assertEquals(10_000, plan.size)
        assertEquals(10_000, plan.toSet().size)
        assertEquals("wallpaper-7342", plan.first())
        assertTrue(plan.containsAll(canonical))
    }
}
