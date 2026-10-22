package com.wallpaperhub.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Grain/vignette are static for a profile+size and must never be rebuilt on every Scene tick. */
@Composable
internal fun WallpaperStaticAtmosphere(
    profile: ThemeProfile,
    detailScale: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.drawWithCache {
            val detail = detailScale.coerceIn(0.25f, 1f)
            val grainCount = if (profile.grainLevel <= 0 || profile.performanceMode <= 0 || detail < 0.42f) {
                0
            } else {
                ((44 + profile.grainLevel * 20) * detail).toInt().coerceIn(24, 100)
            }
            val grainAlpha = 0.012f + profile.grainLevel * 0.004f
            val points = List(grainCount) { index ->
                val x = fractAtmosphere(index * 0.7331f + 0.11f) * size.width
                val y = fractAtmosphere(index * 0.4177f + 0.37f) * size.height
                Triple(Offset(x, y), if (index % 4 == 0) 0.62.dp.toPx() else 0.38.dp.toPx(), grainAlpha)
            }
            val vignetteBrush = if (profile.vignetteLevel > 0) {
                val alpha = 0.06f + profile.vignetteLevel * 0.028f
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = alpha)),
                    center = Offset(size.width * 0.50f, size.height * 0.46f),
                    radius = max(size.width, size.height) * 0.78f,
                )
            } else null

            onDrawBehind {
                points.forEach { (position, radius, alpha) ->
                    drawCircle(Color.White.copy(alpha = alpha), radius, position)
                }
                vignetteBrush?.let { drawRect(it) }
            }
        },
    ) {}
}

private fun fractAtmosphere(value: Float): Float = value - kotlin.math.floor(value)
