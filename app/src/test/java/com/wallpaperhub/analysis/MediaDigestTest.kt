package com.wallpaperhub.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDigestTest {
    @Test
    fun sha256_matchesKnownVector() {
        val value = MediaDigest.sha256("abc".byteInputStream())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", value)
    }

    @Test
    fun sequenceSimilarity_isOneForSameSequence() {
        val values = listOf(0x0123456789abcdefL, 0x0f0f0f0f0f0f0f0fL)
        assertEquals(1.0, MediaDigest.sequenceSimilarity(values, values)!!, 0.000001)
    }

    @Test
    fun emptySequences_haveNoSimilarity() {
        assertNull(MediaDigest.sequenceSimilarity(emptyList(), emptyList()))
    }
}
