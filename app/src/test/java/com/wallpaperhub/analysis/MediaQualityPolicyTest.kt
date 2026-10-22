package com.wallpaperhub.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQualityPolicyTest {
    @Test
    fun nativeOrHigherResolution_isNotFlaggedByResolutionAlone() {
        val result = MediaQualityPolicy.assess(
            MediaQualityInput(width = 1440, height = 3200, targetWidth = 1440, targetHeight = 3168, detailScore = 0.7)
        )
        assertTrue(result.score >= 90)
        assertFalse(result.suggestedForReview)
    }

    @Test
    fun severeUpscale_isSurfacedForReview() {
        val result = MediaQualityPolicy.assess(
            MediaQualityInput(width = 540, height = 960, targetWidth = 1440, targetHeight = 3168, detailScore = 0.5)
        )
        assertTrue(QualityFlag.SEVERE_UPSCALE in result.flags)
        assertTrue(result.suggestedForReview)
    }

    @Test
    fun lowSharpnessAndVeryLowBitrate_accumulateEvidence() {
        val result = MediaQualityPolicy.assess(
            MediaQualityInput(
                width = 1080,
                height = 1920,
                targetWidth = 1080,
                targetHeight = 2400,
                detailScore = 0.12,
                bitrateBitsPerSecond = 500_000,
                frameRate = 30f,
            )
        )
        assertTrue(QualityFlag.VERY_LOW_DETAIL in result.flags)
        assertTrue(QualityFlag.VERY_LOW_VIDEO_BITRATE in result.flags)
        assertTrue(result.suggestedForReview)
    }

    @Test
    fun missingSharpnessEvidence_doesNotInventBlurFlag() {
        val result = MediaQualityPolicy.assess(
            MediaQualityInput(
                width = 1440,
                height = 3200,
                targetWidth = 1440,
                targetHeight = 3200,
                detailScore = null,
            )
        )
        assertFalse(QualityFlag.LOW_DETAIL in result.flags)
        assertFalse(QualityFlag.VERY_LOW_DETAIL in result.flags)
    }
}
