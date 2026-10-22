package com.wallpaperhub.preview.model

/**
 * Maps the renderer transform back into source-image coordinates.
 * Fractions are relative to the full source image: 0 = leading/top, 1 = trailing/bottom.
 */
data class CropSelectionRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

object CropSelectionPolicy {
    fun fromTransform(result: MediaTransformResult): CropSelectionRect {
        val scaleX = result.rendererScaleX.coerceAtLeast(0.0001f)
        val scaleY = result.rendererScaleY.coerceAtLeast(0.0001f)
        val width = (1f / scaleX).coerceIn(0f, 1f)
        val height = (1f / scaleY).coerceIn(0f, 1f)

        // Renderer X is left -> right. Renderer Y is OpenGL bottom -> top, while UI source
        // coordinates are top -> bottom, hence the different centerY sign conversion.
        val centerX = ((1f - result.translationX / scaleX) / 2f).coerceIn(0f, 1f)
        val centerY = ((1f + result.translationY / scaleY) / 2f).coerceIn(0f, 1f)

        return CropSelectionRect(
            left = (centerX - width / 2f).coerceIn(0f, 1f - width),
            top = (centerY - height / 2f).coerceIn(0f, 1f - height),
            width = width,
            height = height,
        )
    }

    /** Inverse of the renderer's normalized overflow mapping for one axis. */
    fun offsetForCenter(centerFraction: Float, rendererScale: Float): Float {
        if (rendererScale <= 1f) return 0f
        val centered = centerFraction.coerceIn(0f, 1f) * 2f - 1f
        return (centered * rendererScale / (rendererScale - 1f))
            .coerceIn(CropGeometry.MIN_OFFSET, CropGeometry.MAX_OFFSET)
    }
}
