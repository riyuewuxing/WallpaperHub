package com.wallpaperhub.ui.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals

data class DockDestination(val route: String, val label: String, val icon: ImageVector)

@Composable
fun WallpaperBottomDock(
    destinations: List<DockDestination>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        shape = WallpaperTokens.DockShape,
        color = WallpaperTokens.FloatingGlass,
        border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.30f)),
        tonalElevation = 0.dp,
        shadowElevation = 7.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                val selected = currentRoute == destination.route
                val content by animateColorAsState(
                    targetValue = if (selected) visuals.selected else visuals.textSecondary,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 540f),
                    label = "dock_content_${destination.route}",
                )
                val iconSize by animateDpAsState(
                    targetValue = if (selected) 23.dp else 21.dp,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                    label = "dock_size_${destination.route}",
                )
                val activeSurface by animateColorAsState(
                    targetValue = if (selected) visuals.secondaryButton.copy(alpha = 0.48f) else Color.Transparent,
                    animationSpec = spring(dampingRatio = 0.90f, stiffness = 620f),
                    label = "dock_surface_${destination.route}",
                )

                Surface(
                    onClick = { onSelect(destination.route) },
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp),
                    shape = WallpaperTokens.ShapeMd,
                    color = activeSurface,
                    contentColor = content,
                    border = if (selected) BorderStroke(1.dp, visuals.selected.copy(alpha = 0.22f)) else null,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(destination.icon, contentDescription = destination.label, tint = content, modifier = Modifier.size(iconSize))
                        Text(destination.label, color = content, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, maxLines = 1)
                        Box(
                            modifier = Modifier
                                .size(if (selected) 4.dp else 3.dp)
                                .background(if (selected) visuals.glow else Color.Transparent, CircleShape),
                        )
                    }
                }
            }
        }
    }
}
