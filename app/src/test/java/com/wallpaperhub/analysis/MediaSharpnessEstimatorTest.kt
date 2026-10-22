package com.wallpaperhub.analysis

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSharpnessEstimatorTest {
    @Test
    fun flatOrMinimalSample_isUnknownRatherThanBlurred() {
        val width = 64
        val height = 64
        val luma = IntArray(width * height) { 128 }
        assertNull(MediaSharpnessEstimator.estimate(luma, width, height))
    }

    @Test
    fun crispEdge_scoresHigh() {
        val width = 64
        val height = 64
        val luma = IntArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 0 else 255
        }
        val score = MediaSharpnessEstimator.estimate(luma, width, height)
        assertTrue(score != null && score > 0.65)
    }

    @Test
    fun broadSoftEdge_scoresLowWhenStructureExists() {
        val width = 64
        val height = 64
        val start = width / 2 - 10
        val end = width / 2 + 10
        val luma = IntArray(width * height) { index ->
            val x = index % width
            when {
                x < start -> 0
                x > end -> 255
                else -> ((x - start) * 255 / (end - start))
            }
        }
        val score = MediaSharpnessEstimator.estimate(luma, width, height)
        assertTrue(score != null && score < 0.20)
    }
}
