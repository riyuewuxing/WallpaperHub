package com.wallpaperhub.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals

/**
 * Geometry is fixed product identity. Scene Theme owns the canvas; materials must protect content
 * without turning the app back into opaque black rectangles.
 */
object WallpaperTokens {
    val BrandPrimary: Color @Composable get() = LocalWallpaperVisuals.current.accent
    val BrandSecondary: Color @Composable get() = LocalWallpaperVisuals.current.selected

    val GalleryBackground: Color @Composable get() = Color.Transparent

    /** Scene-aware material taxonomy. */
    val MediaGlass: Color
        @Composable get() = LocalWallpaperVisuals.current.surface.copy(
            alpha = LocalWallpaperVisuals.current.surface.alpha.coerceAtMost(0.48f),
        )
    val TextGlass: Color
        @Composable get() = LocalWallpaperVisuals.current.surfaceHigh.copy(
            alpha = LocalWallpaperVisuals.current.surfaceHigh.alpha.coerceAtMost(0.78f),
        )
    val FloatingGlass: Color
        @Composable get() = LocalWallpaperVisuals.current.navigation.copy(
            alpha = LocalWallpaperVisuals.current.navigation.alpha.coerceAtMost(0.76f),
        )

    val GalleryCanvasElevated: Color @Composable get() = MediaGlass
    val GallerySurface: Color @Composable get() = MediaGlass
    val GallerySurfaceHigh: Color @Composable get() = TextGlass
    val GallerySurfaceStrong: Color @Composable get() = TextGlass
    val NavigationChrome: Color @Composable get() = FloatingGlass

    val Success: Color @Composable get() = LocalWallpaperVisuals.current.success
    val Warning: Color @Composable get() = LocalWallpaperVisuals.current.warning
    val Error: Color @Composable get() = LocalWallpaperVisuals.current.error
    val TextMuted: Color @Composable get() = LocalWallpaperVisuals.current.textSecondary
    val TextTertiary: Color @Composable get() = LocalWallpaperVisuals.current.textSecondary.copy(alpha = 0.78f)
    val Divider: Color @Composable get() = LocalWallpaperVisuals.current.stroke.copy(alpha = 0.42f)
    val StrokeSubtle: Color @Composable get() = LocalWallpaperVisuals.current.stroke.copy(alpha = 0.28f)
    val StrokeStrong: Color @Composable get() = LocalWallpaperVisuals.current.stroke.copy(alpha = 0.54f)
    val MediaScrim = Color.Black.copy(alpha = 0.72f)

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp
    val SpaceHuge = 40.dp

    val ShapeXs = RoundedCornerShape(6.dp)
    val ShapeSm = RoundedCornerShape(10.dp)
    val ShapeMd = RoundedCornerShape(14.dp)
    val ShapeLg = RoundedCornerShape(18.dp)
    val ShapeXl = RoundedCornerShape(24.dp)
    val HeroShape = RoundedCornerShape(28.dp)
    val DockShape = RoundedCornerShape(26.dp)
    val SettingRowShape = RoundedCornerShape(14.dp)

    val TileAspectRatio = 9f / 16f
    val MinTileWidth = 120.dp
}

@Composable
fun NowPlayingHero(
    uri: String?,
    title: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        modifier = modifier,
        shape = WallpaperTokens.HeroShape,
        color = WallpaperTokens.TextGlass,
        contentColor = visuals.textPrimary,
        tonalElevation = 0.dp,
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(WallpaperTokens.SpaceLg)) {
            AsyncImage(
                model = uri,
                contentDescription = "当前播放壁纸",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(0.44f).aspectRatio(WallpaperTokens.TileAspectRatio),
            )
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(WallpaperTokens.SpaceSm),
                modifier = Modifier.fillMaxWidth(0.56f),
            ) {
                Text("当前桌面", style = MaterialTheme.typography.labelMedium, color = visuals.accent)
                Text(title, style = MaterialTheme.typography.titleLarge, color = visuals.textPrimary)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = visuals.textSecondary)
            }
        }
    }
}

@Composable
fun WallpaperTile(
    uri: String,
    name: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = WallpaperTokens.ShapeMd,
        color = WallpaperTokens.MediaGlass,
        contentColor = visuals.textPrimary,
        tonalElevation = 0.dp,
    ) {
        androidx.compose.foundation.layout.Column {
            AsyncImage(
                model = uri,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(WallpaperTokens.TileAspectRatio),
            )
            Text(
                name,
                maxLines = 1,
                color = visuals.textPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun ApplyWallpaperButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "预览并应用",
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = WallpaperTokens.SpaceXl, vertical = WallpaperTokens.SpaceMd),
    ) { Text(label) }
}

@Composable
fun PlaybackStatusPill(status: String, modifier: Modifier = Modifier) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = WallpaperTokens.FloatingGlass,
        contentColor = visuals.textPrimary,
        border = androidx.compose.foundation.BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
    ) {
        Text(status, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = visuals.textPrimary)
    }
}
