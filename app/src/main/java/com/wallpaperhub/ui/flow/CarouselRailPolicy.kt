package com.wallpaperhub.ui.flow

import com.wallpaperhub.playback.model.PlaybackPhase

internal object CarouselRailPolicy {
    fun initialPage(queueUris: List<String>, appliedUri: String?, storedIndex: Int): Int {
        if (queueUris.isEmpty()) return 0
        val appliedIndex = queueUris.indexOf(appliedUri)
        return when {
            appliedIndex >= 0 -> appliedIndex
            storedIndex in queueUris.indices -> storedIndex
            else -> 0
        }
    }

    /**
     * Pager includes one virtual copy of the tail and head, so every real item can keep a
     * visible predecessor and successor even when the current desktop item is first or last.
     */
    fun pagerPageForQueueIndex(queueIndex: Int): Int = queueIndex + 1

    fun queueIndexForPagerPage(pagerPage: Int, queueSize: Int): Int {
        if (queueSize <= 0) return 0
        return Math.floorMod(pagerPage - 1, queueSize)
    }

    fun settledPagerPage(pagerPage: Int, queueSize: Int): Int = when {
        queueSize <= 0 -> 0
        pagerPage <= 0 -> queueSize
        pagerPage >= queueSize + 1 -> 1
        else -> pagerPage
    }

    /**
     * The carousel represents what is actually on the desktop, so confirmed applied wins while the
     * session is APPLIED. Desired is only a future target. During an explicit user command phase the
     * requested target may own focus so the UI can show "正在切换" without lying about APPLIED state.
     */
    fun focusUri(
        queueUris: List<String>,
        appliedUri: String?,
        desiredUri: String?,
        phase: PlaybackPhase,
    ): String? {
        fun present(uri: String?) = uri?.takeIf { it in queueUris }
        val applied = present(appliedUri)
        val desired = present(desiredUri)
        return if (phase == PlaybackPhase.APPLIED) {
            applied ?: desired ?: queueUris.firstOrNull()
        } else {
            desired ?: applied ?: queueUris.firstOrNull()
        }
    }

    fun <T> move(items: List<T>, fromIndex: Int, delta: Int): List<T> {
        val targetIndex = fromIndex + delta
        if (fromIndex !in items.indices || targetIndex !in items.indices) return items
        return items.toMutableList().apply {
            add(targetIndex, removeAt(fromIndex))
        }
    }
}
