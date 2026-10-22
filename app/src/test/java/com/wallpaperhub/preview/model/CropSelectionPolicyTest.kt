package com.wallpaperhub.preview.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CropSelectionPolicyTest {
    @Test
    fun centeredWideFillMapsToMiddleHalf() {
        val rect = CropSelectionPolicy.fromTransform(
            MediaTransformResult(
                rendererScaleX = 2f,
                rendererScaleY = 1f,
                previewScale = 2f,
                translationX = 0f,
                translationY = 0f,
            )
        )

        assertEquals(0.25f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.5f, rect.width, 0.0001f)
        assertEquals(1f, rect.height, 0.0001f)
    }

    @Test
    fun positiveHorizontalOffsetSelectsRightEdge() {
        val rect = CropSelectionPolicy.fromTransform(
            MediaTransformResult(
                rendererScaleX = 2f,
                rendererScaleY = 1f,
                previewScale = 2f,
                translationX = -1f,
                translationY = 0f,
            )
        )

        assertEquals(0.5f, rect.left, 0.0001f)
        assertEquals(0.5f, rect.width, 0.0001f)
    }

    @Test
    fun positiveVerticalOffsetSelectsBottomEdge() {
        val rect = CropSelectionPolicy.fromTransform(
            MediaTransformResult(
                rendererScaleX = 1f,
                rendererScaleY = 2f,
                previewScale = 2f,
                translationX = 0f,
                translationY = 1f,
            )
        )

        assertEquals(0.5f, rect.top, 0.0001f)
        assertEquals(0.5f, rect.height, 0.0001f)
    }

    @Test
    fun fitModeStyleScalesExposeWholeSource() {
        val rect = CropSelectionPolicy.fromTransform(
            MediaTransformResult(
                rendererScaleX = 0.6f,
                rendererScaleY = 1f,
                previewScale = 1f,
                translationX = 0f,
                translationY = 0f,
            )
        )

        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.width, 0.0001f)
        assertEquals(1f, rect.height, 0.0001f)
    }

    @Test
    fun inverseCenterMappingReachesBothEdges() {
        assertEquals(-1f, CropSelectionPolicy.offsetForCenter(0.25f, 2f), 0.0001f)
        assertEquals(0f, CropSelectionPolicy.offsetForCenter(0.5f, 2f), 0.0001f)
        assertEquals(1f, CropSelectionPolicy.offsetForCenter(0.75f, 2f), 0.0001f)
    }

    @Test
    fun wideSourceSelectionKeepsWallpaperViewportAspect() {
        val result = MediaTransformPolicy.calculate(
            viewportWidth = 900f,
            viewportHeight = 1950f,
            mediaWidth = 2400,
            mediaHeight = 1000,
            transform = CropTransform(scaleMode = MediaScaleMode.FILL),
        )
        val rect = CropSelectionPolicy.fromTransform(result)
        val selectedAspect = (rect.width * 2400f) / (rect.height * 1000f)

        assertEquals(900f / 1950f, selectedAspect, 0.0001f)
    }

    @Test
    fun tallSourceSelectionKeepsWallpaperViewportAspect() {
        val result = MediaTransformPolicy.calculate(
            viewportWidth = 900f,
            viewportHeight = 1950f,
            mediaWidth = 1000,
            mediaHeight = 3000,
            transform = CropTransform(scaleMode = MediaScaleMode.FILL),
        )
        val rect = CropSelectionPolicy.fromTransform(result)
        val selectedAspect = (rect.width * 1000f) / (rect.height * 3000f)

        assertEquals(900f / 1950f, selectedAspect, 0.0001f)
    }
}
