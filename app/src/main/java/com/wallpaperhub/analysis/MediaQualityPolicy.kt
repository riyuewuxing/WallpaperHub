package com.wallpaperhub.analysis

import kotlin.math.max

enum class QualityFlag {
    UPSCALE,
    SEVERE_UPSCALE,
    LOW_DETAIL,
    VERY_LOW_DETAIL,
    LOW_VIDEO_BITRATE,
    VERY_LOW_VIDEO_BITRATE,
}

data class MediaQualityInput(
    val width: Int?,
    val height: Int?,
    val targetWidth: Int,
    val targetHeight: Int,
    /** Edge-sharpness evidence in [0, 1]. Null means the frame lacks enough structure to judge blur. */
    val detailScore: Double? = null,
    val bitrateBitsPerSecond: Long? = null,
    val frameRate: Float? = null,
)

data class MediaQualityAssessment(
    val score: Int,
    val upscaleFactor: Double?,
    val flags: Set<QualityFlag>,
    val suggestedForReview: Boolean,
)

/**
 * Conservative technical screening only. It deliberately avoids declaring subjective quality.
 * Low-detail flags are emitted only when the extractor has enough structured edges to estimate
 * sharpness; minimalist/flat imagery should therefore not be penalized for having few edges.
 */
object MediaQualityPolicy {
    fun assess(input: MediaQualityInput): MediaQualityAssessment {
        var score = 100
        val flags = linkedSetOf<QualityFlag>()
        val upscale = requiredUpscaleFactor(input.width, input.height, input.targetWidth, input.targetHeight)

        when {
            upscale == null -> Unit
            upscale > 2.0 -> { score -= 42; flags += QualityFlag.SEVERE_UPSCALE }
            upscale > 1.6 -> { score -= 30; flags += QualityFlag.SEVERE_UPSCALE }
            upscale > 1.35 -> { score -= 18; flags += QualityFlag.UPSCALE }
            upscale > 1.15 -> { score -= 8; flags += QualityFlag.UPSCALE }
        }

        input.detailScore?.coerceIn(0.0, 1.0)?.let { sharpness ->
            when {
                sharpness < 0.16 -> { score -= 24; flags += QualityFlag.VERY_LOW_DETAIL }
                sharpness < 0.30 -> { score -= 13; flags += QualityFlag.LOW_DETAIL }
            }
        }

        bitsPerPixelPerFrame(input)?.let { bpppf ->
            // Conservative thresholds because codec efficiency is not currently persisted.
            when {
                bpppf < 0.012 -> { score -= 13; flags += QualityFlag.VERY_LOW_VIDEO_BITRATE }
                bpppf < 0.025 -> { score -= 6; flags += QualityFlag.LOW_VIDEO_BITRATE }
            }
        }

        score = score.coerceIn(0, 100)
        val severeSignals = listOf(
            QualityFlag.SEVERE_UPSCALE,
            QualityFlag.VERY_LOW_DETAIL,
            QualityFlag.VERY_LOW_VIDEO_BITRATE,
        ).count { it in flags }
        val suggested = QualityFlag.SEVERE_UPSCALE in flags || severeSignals >= 2 || score <= 60
        return MediaQualityAssessment(score, upscale, flags, suggested)
    }

    fun requiredUpscaleFactor(sourceWidth: Int?, sourceHeight: Int?, targetWidth: Int, targetHeight: Int): Double? {
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) return null
        if (targetWidth <= 0 || targetHeight <= 0) return null
        return max(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
    }

    private fun bitsPerPixelPerFrame(input: MediaQualityInput): Double? {
        val bitrate = input.bitrateBitsPerSecond ?: return null
        val width = input.width ?: return null
        val height = input.height ?: return null
        val fps = input.frameRate ?: return null
        if (bitrate <= 0L || width <= 0 || height <= 0 || fps <= 0f) return null
        return bitrate.toDouble() / (width.toDouble() * height.toDouble() * fps.toDouble())
    }
}
