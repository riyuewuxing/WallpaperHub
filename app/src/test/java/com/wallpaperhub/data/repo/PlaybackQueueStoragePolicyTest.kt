package com.wallpaperhub.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaybackQueueStoragePolicyTest {
    @Test
    fun largeQueuesAreNeverSerializedIntoLegacySingleRowColumns() {
        val queue = List(7_280) { index ->
            "file:///storage/emulated/0/Android/data/com.wallpaperhub/files/wallpapers/$index.jpg"
        }

        assertEquals("[]", PlaybackQueueStoragePolicy.legacyQueueJson(queue))
        assertEquals("[]", PlaybackQueueStoragePolicy.legacyShuffleRoundJson(queue.drop(1)))
    }

    @Test
    fun fingerprintIsStableButChangesWhenQueueOrderChanges() {
        val queue = listOf("file:///a.jpg", "file:///b.jpg", "file:///c.jpg")

        assertEquals(
            PlaybackQueueStoragePolicy.fingerprint(queue),
            PlaybackQueueStoragePolicy.fingerprint(queue.toList()),
        )
        assertNotEquals(
            PlaybackQueueStoragePolicy.fingerprint(queue),
            PlaybackQueueStoragePolicy.fingerprint(queue.reversed()),
        )
    }
}
