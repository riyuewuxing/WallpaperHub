package com.wallpaperhub.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Lightweight motion layer for White Dwarf.
 *
 * The raster artwork already contains the scene's single hero star. The legacy procedural
 * White-Dwarf renderer painted a second star + halo at ~32%/30%, creating the visible extra disc.
 * This overlay intentionally renders only moving dust/streaks so the artwork owns the subject.
 */
@Composable
internal fun WhiteDwarfAmbientCanvas(
    particleStrength: Float,
    targetFps: Int,
    detailScale: Float,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalWallpaperVisuals.current
    val motionEnabled = particleStrength > 0f
    var seconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(targetFps, motionEnabled) {
        if (!motionEnabled) {
            seconds = 0f
            return@LaunchedEffect
        }
        val fps = targetFps.coerceIn(15, 60)
        val intervalNanos = 1_000_000_000L / fps
        var origin = 0L
        var lastPublished = 0L
        while (true) {
            withFrameNanos { frame ->
                if (origin == 0L) origin = frame
                if (lastPublished == 0L || frame - lastPublished >= intervalNanos) {
                    lastPublished = frame
                    seconds = (frame - origin) / 1_000_000_000f
                }
            }
        }
    }

    Canvas(modifier) {
        val detail = detailScale.coerceIn(0.25f, 1f)
        val strength = particleStrength.coerceIn(0f, 1.5f)
        if (strength <= 0f) return@Canvas
        val count = ((22 + 30 * detail) * strength.coerceAtLeast(0.35f)).toInt().coerceIn(12, 62)

        repeat(count) { index ->
            val seedX = fractWhiteDwarf(index * 0.6180339f + 0.11f)
            val seedY = fractWhiteDwarf(index * 0.381966f + 0.47f)
            val depth = when {
                index < count * 0.60f -> 0
                index < count * 0.88f -> 1
                else -> 2
            }
            val speed = 0.020f + depth * 0.012f
            val x = fractWhiteDwarf(seedX + seconds * speed * 0.42f) * size.width
            val y = fractWhiteDwarf(seedY + seconds * speed * 0.13f) * size.height +
                sin(seconds * (0.72f + depth * 0.18f) + index * 1.37f).toFloat() *
                (3f + depth * 3f).dp.toPx()

            val baseColor = when (index % 3) {
                0 -> Color.White
                1 -> visuals.gradientB
                else -> visuals.gradientC
            }
            val radius = when (depth) {
                0 -> 0.55.dp.toPx()
                1 -> 0.95.dp.toPx()
                else -> 1.45.dp.toPx()
            }
            val alpha = when (depth) {
                0 -> 0.16f
                1 -> 0.25f
                else -> 0.34f
            } * strength.coerceAtMost(1f)

            drawCircle(baseColor.copy(alpha = alpha), radius, Offset(x, y))
            if (depth >= 1) {
                val length = (8f + depth * 7f).dp.toPx()
                drawLine(
                    color = baseColor.copy(alpha = alpha * 0.34f),
                    start = Offset(x - length, y),
                    end = Offset(x, y),
                    strokeWidth = (0.45f + depth * 0.18f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun fractWhiteDwarf(value: Float): Float = value - floor(value)
