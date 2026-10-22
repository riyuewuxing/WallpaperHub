package com.wallpaperhub.preview.model

/**
 * Normalized crop rules. Offsets use the full available overflow range:
 * -1 aligns the leading/top edge and 1 aligns the trailing/bottom edge.
 */
object CropGeometry {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val MIN_OFFSET = -1f
    const val MAX_OFFSET = 1f

    fun clamp(
        transform: CropTransform,
        minScale: Float = MIN_SCALE,
        maxScale: Float = MAX_SCALE,
    ): CropTransform {
        if (transform.scaleMode == MediaScaleMode.FIT) {
            return CropTransform(scaleMode = MediaScaleMode.FIT)
        }
        val scale = transform.scale.coerceIn(minScale, maxScale)
        return CropTransform(
            scale = scale,
            offsetX = transform.offsetX.coerceIn(MIN_OFFSET, MAX_OFFSET),
            offsetY = transform.offsetY.coerceIn(MIN_OFFSET, MAX_OFFSET),
            scaleMode = MediaScaleMode.FILL,
        )
    }
}
