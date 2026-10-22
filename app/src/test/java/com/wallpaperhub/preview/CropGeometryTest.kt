package com.wallpaperhub.preview

import com.wallpaperhub.preview.model.CropGeometry
import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {

    @Test
    fun clampKeepsScaleAndOffsetsInsideTheSharedGeometryContract() {
        val result = CropGeometry.clamp(
            CropTransform(
                scale = 8f,
                offsetX = 2f,
                offsetY = -2f,
                scaleMode = MediaScaleMode.FILL,
            )
        )

        assertEquals(4f, result.scale, 0.0001f)
        assertEquals(1f, result.offsetX, 0.0001f)
        assertEquals(-1f, result.offsetY, 0.0001f)
    }

    @Test
    fun fillModeKeepsBaseCropPositionAtUnityScale() {
        val result = CropGeometry.clamp(
            CropTransform(scale = 0.25f, offsetX = 0.75f, offsetY = -0.75f)
        )

        assertEquals(1f, result.scale, 0.0001f)
        assertEquals(0.75f, result.offsetX, 0.0001f)
        assertEquals(-0.75f, result.offsetY, 0.0001f)
    }

    @Test
    fun fitModeAlwaysResetsZoomAndPan() {
        val result = CropGeometry.clamp(
            CropTransform(
                scale = 3f,
                offsetX = 0.75f,
                offsetY = -0.75f,
                scaleMode = MediaScaleMode.FIT,
            )
        )

        assertEquals(
            CropTransform(scaleMode = MediaScaleMode.FIT),
            result,
        )
    }
}
