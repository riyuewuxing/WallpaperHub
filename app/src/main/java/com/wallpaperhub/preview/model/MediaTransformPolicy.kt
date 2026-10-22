package com.wallpaperhub.preview.model

import kotlin.math.max

data class MediaTransformResult(
    val rendererScaleX: Float,
    val rendererScaleY: Float,
    val previewScale: Float,
    /** Translation in OpenGL normalized device coordinates. */
    val translationX: Float,
    val translationY: Float,
)

/** One aspect-ratio and crop contract shared by Compose preview and OpenGL output. */
object MediaTransformPolicy {
    fun calculate(
        viewportWidth: Float,
        viewportHeight: Float,
        mediaWidth: Int?,
        mediaHeight: Int?,
        transform: CropTransform,
    ): MediaTransformResult {
        val safeViewportWidth = viewportWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeViewportHeight = viewportHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        val viewportRatio = safeViewportWidth / safeViewportHeight
        val resolvedMediaWidth = mediaWidth?.takeIf { it > 0 }?.toFloat() ?: safeViewportWidth
        val resolvedMediaHeight = mediaHeight?.takeIf { it > 0 }?.toFloat() ?: safeViewportHeight
        val mediaRatio = resolvedMediaWidth / resolvedMediaHeight
        val clamped = CropGeometry.clamp(transform)

        val wideMedia = mediaRatio >= viewportRatio
        val fillZoom = if (wideMedia) mediaRatio / viewportRatio else viewportRatio / mediaRatio
        val baseScaleX: Float
        val baseScaleY: Float
        val previewScale: Float
        if (clamped.scaleMode == MediaScaleMode.FIT) {
            baseScaleX = if (wideMedia) 1f else mediaRatio / viewportRatio
            baseScaleY = if (wideMedia) viewportRatio / mediaRatio else 1f
            previewScale = 1f
        } else {
            baseScaleX = if (wideMedia) fillZoom else 1f
            baseScaleY = if (wideMedia) 1f else fillZoom
            previewScale = fillZoom * clamped.scale
        }

        val rendererScaleX = baseScaleX * clamped.scale
        val rendererScaleY = baseScaleY * clamped.scale
        return MediaTransformResult(
            rendererScaleX = rendererScaleX,
            rendererScaleY = rendererScaleY,
            previewScale = previewScale,
            translationX = -clamped.offsetX * max(0f, rendererScaleX - 1f),
            translationY = clamped.offsetY * max(0f, rendererScaleY - 1f),
        )
    }
}
