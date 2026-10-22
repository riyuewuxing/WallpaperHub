package com.wallpaperhub.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * Estimates edge sharpness only when the sample contains enough structure to support that claim.
 *
 * A flat gradient, sky, bokeh field or large anime color block intentionally returns null instead
 * of a low score. This prevents "few edges" from being confused with "blurred edges". When enough
 * edges exist, the score compares Laplacian response to local gradient strength; crisp transitions
 * approach 1 while broad/soft transitions approach 0.
 */
object MediaSharpnessEstimator {
    private const val EDGE_GRADIENT_THRESHOLD = 14
    private const val HIGH_CONTRAST_THRESHOLD = 28
    private const val MIN_EDGE_COUNT = 12
    private const val MIN_EDGE_DENSITY = 0.015

    fun estimate(luma: IntArray, width: Int, height: Int): Double? {
        if (width < 5 || height < 5 || luma.size < width * height) return null

        var edgeCount = 0
        var highContrastEdges = 0
        var normalizedLaplacianSum = 0.0
        val interiorPixels = (width - 2) * (height - 2)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val center = luma[index]
                val left = luma[index - 1]
                val right = luma[index + 1]
                val up = luma[index - width]
                val down = luma[index + width]

                val gradient = max(abs(right - left), abs(down - up))
                if (gradient < EDGE_GRADIENT_THRESHOLD) continue

                edgeCount++
                if (gradient >= HIGH_CONTRAST_THRESHOLD) highContrastEdges++
                val laplacian = abs(4 * center - left - right - up - down)
                normalizedLaplacianSum += (laplacian.toDouble() / (gradient + 8.0)).coerceAtMost(2.0)
            }
        }

        if (edgeCount < MIN_EDGE_COUNT) return null
        val density = edgeCount.toDouble() / interiorPixels.coerceAtLeast(1)
        if (density < MIN_EDGE_DENSITY) return null

        val edgeSharpness = normalizedLaplacianSum / edgeCount
        val contrastSupport = highContrastEdges.toDouble() / edgeCount
        val combined = 0.75 * edgeSharpness + 0.25 * contrastSupport
        return (combined / 0.85).coerceIn(0.0, 1.0)
    }
}
