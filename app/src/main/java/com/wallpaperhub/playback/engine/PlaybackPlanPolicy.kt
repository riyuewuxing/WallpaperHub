package com.wallpaperhub.playback.engine

import com.wallpaperhub.playback.model.PlaybackMode
import kotlin.random.Random

/** Pure policy for turning newest-first membership into one concrete visible playback plan. */
internal object PlaybackPlanPolicy {

    /**
     * Every newly generated plan is anchored at the wallpaper that is actually visible now.
     * [reverseSequential] changes only time ordering; the renderer still consumes a sequential plan.
     */
    fun materialize(
        canonical: List<String>,
        currentUri: String?,
        mode: PlaybackMode,
        random: Random = Random.Default,
        reverseSequential: Boolean = false,
    ): List<String> {
        val newestFirst = canonical.distinct()
        if (newestFirst.isEmpty()) return emptyList()
        val current = currentUri?.takeIf { it in newestFirst } ?: newestFirst.first()
        val newestRemainder = newestFirst.filterNot { it == current }
        return when (mode) {
            PlaybackMode.SEQUENTIAL,
            PlaybackMode.LOOP -> {
                val remainder = if (reverseSequential) {
                    newestFirst.asReversed().filterNot { it == current }
                } else newestRemainder
                listOf(current) + remainder
            }
            PlaybackMode.SHUFFLE,
            PlaybackMode.RANDOM -> listOf(current) + newestRemainder.shuffled(random)
        }
    }

    fun preserveRandomStep(
        activePlan: List<String>,
        currentUri: String?,
        members: List<String>,
        random: Random = Random.Default,
    ): List<String> = materialize(members, currentUri, PlaybackMode.RANDOM, random)

    fun insertIntoShuffleRemainder(
        activeRound: List<String>,
        currentUri: String?,
        additions: List<String>,
        random: Random = Random.Default,
    ): List<String> = materialize(
        canonical = (activeRound + additions).distinct(),
        currentUri = currentUri,
        mode = PlaybackMode.SHUFFLE,
        random = random,
    )

    fun removeFromActive(active: List<String>, removed: Set<String>): List<String> =
        active.filterNot { it in removed }.distinct()

    fun sequentialSuccessorAfterRemoval(
        oldCanonical: List<String>,
        removedCurrentUri: String,
        remaining: Set<String>,
    ): String? = successorFromCircularOrder(oldCanonical, removedCurrentUri, remaining)

    fun shuffleFutureSuccessorAfterRemoval(
        oldRound: List<String>,
        removedCurrentUri: String,
        remaining: Set<String>,
    ): String? {
        if (remaining.isEmpty()) return null
        val currentIndex = oldRound.indexOf(removedCurrentUri)
        if (currentIndex < 0) return null
        for (index in currentIndex + 1 until oldRound.size) {
            oldRound[index].takeIf { it in remaining }?.let { return it }
        }
        return null
    }

    fun shuffleSuccessorAfterRemoval(
        oldRound: List<String>,
        removedCurrentUri: String,
        remaining: Set<String>,
    ): String? = shuffleFutureSuccessorAfterRemoval(oldRound, removedCurrentUri, remaining)

    fun randomSuccessor(
        remaining: List<String>,
        random: Random = Random.Default,
    ): String? = if (remaining.isEmpty()) null else remaining[random.nextInt(remaining.size)]

    fun nextShuffleRound(
        members: List<String>,
        currentUri: String,
        random: Random = Random.Default,
    ): List<String> = materialize(members, currentUri, PlaybackMode.SHUFFLE, random)

    fun nextRandomRound(
        members: List<String>,
        currentUri: String,
        random: Random = Random.Default,
    ): List<String> = materialize(members, currentUri, PlaybackMode.RANDOM, random)

    private fun successorFromCircularOrder(
        oldOrder: List<String>,
        removedCurrentUri: String,
        remaining: Set<String>,
    ): String? {
        if (remaining.isEmpty()) return null
        val currentIndex = oldOrder.indexOf(removedCurrentUri)
        if (currentIndex < 0) return oldOrder.firstOrNull { it in remaining } ?: remaining.first()
        for (offset in 1..oldOrder.size) {
            val candidate = oldOrder[(currentIndex + offset) % oldOrder.size]
            if (candidate in remaining) return candidate
        }
        return remaining.firstOrNull()
    }
}
