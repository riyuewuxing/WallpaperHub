package com.wallpaperhub.preview

import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.preview.model.MediaTransformPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTransformPolicyTest {

    @Test
    fun fillUsesTheSameWideMediaCropForPreviewAndRenderer() {
        val result = MediaTransformPolicy.calculate(
            viewportWidth = 500f,
            viewportHeight = 1000f,
            mediaWidth = 2000,
            mediaHeight = 1000,
            transform = CropTransform(scaleMode = MediaScaleMode.FILL),
        )

        assertEquals(4f, result.rendererScaleX, 0.0001f)
        assertEquals(1f, result.rendererScaleY, 0.0001f)
        assertEquals(4f, result.previewScale, 0.0001f)
    }

    @Test
    fun fitPreservesTheWholeWideMediaWithoutPreviewZoom() {
        val result = MediaTransformPolicy.calculate(
            viewportWidth = 500f,
            viewportHeight = 1000f,
            mediaWidth = 2000,
            mediaHeight = 1000,
            transform = CropTransform(scaleMode = MediaScaleMode.FIT),
        )

        assertEquals(1f, result.rendererScaleX, 0.0001f)
        assertEquals(0.25f, result.rendererScaleY, 0.0001f)
        assertEquals(1f, result.previewScale, 0.0001f)
    }

    @Test
    fun userZoomAndPanAreAppliedToRendererAndPreviewContract() {
        val result = MediaTransformPolicy.calculate(
            viewportWidth = 500f,
            viewportHeight = 1000f,
            mediaWidth = 2000,
            mediaHeight = 1000,
            transform = CropTransform(
                scale = 2f,
                offsetX = 0.5f,
                offsetY = -1f,
                scaleMode = MediaScaleMode.FILL,
            ),
        )

        assertEquals(8f, result.rendererScaleX, 0.0001f)
        assertEquals(2f, result.rendererScaleY, 0.0001f)
        assertEquals(8f, result.previewScale, 0.0001f)
        assertEquals(-3.5f, result.translationX, 0.0001f)
        assertEquals(-1f, result.translationY, 0.0001f)
    }

    @Test
    fun persistedFitRestoresAsFit() {
        assertEquals(MediaScaleMode.FIT, MediaScaleMode.fromPersisted("fit"))
        assertEquals(MediaScaleMode.FIT, MediaScaleMode.fromPersisted("FIT"))
    }

    @Test
    fun unknownPersistedModeFallsBackToFill() {
        assertEquals(MediaScaleMode.FILL, MediaScaleMode.fromPersisted(null))
        assertEquals(MediaScaleMode.FILL, MediaScaleMode.fromPersisted("legacy"))
    }
}
