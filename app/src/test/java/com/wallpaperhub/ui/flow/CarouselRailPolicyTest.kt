package com.wallpaperhub.ui.flow

import org.junit.Assert.assertEquals
import org.junit.Test
import com.wallpaperhub.playback.model.PlaybackPhase

class CarouselRailPolicyTest {
    @Test
    fun desktopWallpaperWinsOverAStaleStoredIndex() {
        assertEquals(
            2,
            CarouselRailPolicy.initialPage(
                queueUris = listOf("a", "b", "c", "d"),
                appliedUri = "c",
                storedIndex = 0,
            ),
        )
    }

    @Test
    fun movingTheBrowsedWallpaperPreservesEveryItem() {
        assertEquals(
            listOf("a", "c", "b", "d"),
            CarouselRailPolicy.move(listOf("a", "b", "c", "d"), fromIndex = 2, delta = -1),
        )
        assertEquals(
            listOf("a", "b", "c", "d"),
            CarouselRailPolicy.move(listOf("a", "b", "c", "d"), fromIndex = 0, delta = -1),
        )
    }

    @Test
    fun pendingApplyFocusesRequestedItemThenConfirmedStateFollowsLiveDesktop() {
        val queue = listOf("a", "b", "c")
        assertEquals(
            "c",
            CarouselRailPolicy.focusUri(queue, appliedUri = "a", desiredUri = "c", phase = PlaybackPhase.PREPARING),
        )
        assertEquals(
            "b",
            CarouselRailPolicy.focusUri(queue, appliedUri = "b", desiredUri = "c", phase = PlaybackPhase.APPLIED),
        )
    }

    @Test
    fun virtualPagerKeepsBothNeighboursAvailableAtQueueEdges() {
        assertEquals(1, CarouselRailPolicy.pagerPageForQueueIndex(0))
        assertEquals(2, CarouselRailPolicy.queueIndexForPagerPage(0, 3))
        assertEquals(0, CarouselRailPolicy.queueIndexForPagerPage(1, 3))
        assertEquals(0, CarouselRailPolicy.queueIndexForPagerPage(4, 3))
        assertEquals(3, CarouselRailPolicy.settledPagerPage(0, 3))
        assertEquals(1, CarouselRailPolicy.settledPagerPage(4, 3))
    }
}
