package com.wallpaperhub.playback.engine

import com.wallpaperhub.playback.model.PlaybackMode
import kotlin.random.Random

/**
 * Runtime cursor over one already-materialized full plan.
 *
 * Strategy generation belongs to PlaybackOrderCoordinator. The wallpaper engine never performs a
 * second hidden shuffle/random operation; every mode advances through the physical plan in order.
 */
class QueueController(
    initialQueue: List<String>,
    private var mode: PlaybackMode,
    currentUri: String? = null,
    @Suppress("UNUSED_PARAMETER") random: Random = Random.Default,
) {
    private var queue: List<String> = initialQueue.distinct()
    private var queueSet: Set<String> = queue.toHashSet()
    private var indexByUri: Map<String, Int> = buildIndex(queue)
    private var currentIndex: Int = resolveIndex(currentUri)
    private val history = ArrayDeque<String>()

    fun current(): String? = queue.getOrNull(currentIndex)

    fun next(): String? {
        if (queue.isEmpty()) return null
        current()?.let { history.addLast(it) }
        currentIndex = if (queue.size == 1) 0 else (currentIndex + 1) % queue.size
        return current()
    }

    fun previous(): String? {
        if (queue.isEmpty()) return null
        val previousUri = history.removeLastOrNull()
        if (previousUri != null && previousUri in queueSet) {
            currentIndex = indexByUri[previousUri] ?: 0
        } else {
            currentIndex = (currentIndex - 1 + queue.size) % queue.size
        }
        return current()
    }

    fun peekNext(): String? {
        if (queue.isEmpty()) return null
        if (queue.size == 1) return queue.first()
        return queue[(currentIndex + 1) % queue.size]
    }

    fun willCompleteShuffleRoundOnNext(): Boolean =
        mode == PlaybackMode.SHUFFLE && queue.size > 1 && currentIndex == queue.lastIndex

    fun willCompleteRandomRoundOnNext(): Boolean =
        mode == PlaybackMode.RANDOM && queue.size > 1 && currentIndex == queue.lastIndex

    fun index(): Int = currentIndex

    fun setQueue(newQueue: List<String>, currentUri: String? = current(), resetHistory: Boolean = false) {
        replaceQueue(newQueue)
        currentIndex = resolveIndex(currentUri)
        if (resetHistory) history.clear()
    }

    fun restartRound(newQueue: List<String>, currentUri: String?) {
        replaceQueue(newQueue)
        currentIndex = resolveIndex(currentUri)
        history.clear()
    }

    fun setMode(newMode: PlaybackMode) {
        if (mode == newMode) return
        mode = newMode
        history.clear()
    }

    fun orderedQueue(): List<String> = queue

    private fun replaceQueue(newQueue: List<String>) {
        queue = newQueue.distinct()
        queueSet = queue.toHashSet()
        indexByUri = buildIndex(queue)
    }

    private fun resolveIndex(uri: String?): Int {
        if (queue.isEmpty()) return -1
        return uri?.let(indexByUri::get) ?: 0
    }

    private fun buildIndex(items: List<String>): Map<String, Int> =
        HashMap<String, Int>(hashCapacity(items.size)).apply {
            items.forEachIndexed { index, uri -> put(uri, index) }
        }

    private fun hashCapacity(size: Int): Int =
        if (size < 3) 4 else (size / 0.75f).toInt() + 1
}
