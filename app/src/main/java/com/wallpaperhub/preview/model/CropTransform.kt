package com.wallpaperhub.preview.model

/** Crop composition shared by the Compose preview and the GL renderer. */
data class CropTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleMode: MediaScaleMode = MediaScaleMode.FILL,
)
