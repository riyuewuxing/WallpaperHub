package com.wallpaperhub.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperPageReaderTest {

    @Test
    fun `assembles library larger than cursor window from bounded pages`() = runBlocking {
        val source = (0 until 8_000).toList()
        val requestedLimits = mutableListOf<Int>()
        val requestedOffsets = mutableListOf<Int>()

        val result = WallpaperPageReader.readAll(pageSize = 256) { limit, offset ->
            requestedLimits += limit
            requestedOffsets += offset
            source.drop(offset).take(limit)
        }

        assertEquals(source, result)
        assertTrue(requestedLimits.size > 20)
        assertTrue(requestedLimits.all { it == 256 })
        assertEquals(0, requestedOffsets.first())
        assertEquals(7_936, requestedOffsets.last())
    }

    @Test
    fun `exact page boundary performs final empty sentinel read`() = runBlocking {
        val source = (0 until 512).toList()
        val offsets = mutableListOf<Int>()

        val result = WallpaperPageReader.readAll(pageSize = 256) { limit, offset ->
            offsets += offset
            source.drop(offset).take(limit)
        }

        assertEquals(source, result)
        assertEquals(listOf(0, 256, 512), offsets)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non positive page size`() {
        runBlocking {
            WallpaperPageReader.readAll<Int>(pageSize = 0) { _, _ -> emptyList() }
        }
    }
}
