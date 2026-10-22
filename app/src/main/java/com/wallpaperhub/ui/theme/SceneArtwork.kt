package com.wallpaperhub.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.R

/**
 * Scene artwork is the visual foundation for every Scene Theme.
 *
 * Raster artwork is decoded by Coil instead of painterResource(): Coil performs the decode off the
 * main thread, samples toward the display target and avoids OEM BitmapDrawable assumptions. User
 * wallpaper media is never sampled, recolored or tinted by this layer.
 */
@Composable
internal fun WallpaperSceneArtwork(
    profile: ThemeProfile,
    sceneStrength: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scene = ScenePreset.fromId(profile.sceneId)
    val artworkAlpha = (0.92f + sceneStrength.coerceIn(0f, 1f) * 0.06f).coerceIn(0.92f, 0.98f)
    val request = remember(context, scene) {
        ImageRequest.Builder(context)
            .data(scene.artworkRes())
            .crossfade(360)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = artworkAlpha,
        modifier = modifier.fillMaxSize(),
    )
}

@DrawableRes
internal fun ScenePreset.artworkRes(): Int = when (this) {
    ScenePreset.EVENT_HORIZON -> R.drawable.scene_event_horizon
    ScenePreset.ABYSS -> R.drawable.scene_abyss
    ScenePreset.SOLAR_CROWN -> R.drawable.scene_solar_crown
    ScenePreset.WHITE_DWARF -> R.drawable.scene_white_dwarf
    ScenePreset.DESERT_ECLIPSE -> R.drawable.scene_desert_eclipse
    ScenePreset.NEBULA_RIFT -> R.drawable.scene_nebula_rift
}
