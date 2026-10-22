package com.wallpaperhub.ui.designsystem

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals

private const val REVIEW_MEDIA_ASPECT = 9f / 16f

/**
 * Review-only tile. Selection and preview are separate actions: tapping the body selects,
 * while the explicit top-left button only opens the media viewer/player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewMediaTile(
    entity: WallpaperEntity,
    context: Context,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
) {
    val visuals = LocalWallpaperVisuals.current
    val frameColor = when {
        selected -> visuals.selected
        current -> visuals.accent.copy(alpha = 0.88f)
        else -> Color.Transparent
    }
    val frameWidth = when {
        selected -> 2.dp
        current -> 1.5.dp
        else -> 0.dp
    }

    Box(
        modifier = modifier
            .aspectRatio(REVIEW_MEDIA_ASPECT)
            .clip(WallpaperTokens.ShapeMd)
            .border(frameWidth, frameColor, WallpaperTokens.ShapeMd)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CachedWallpaperTileImage(
            entity = entity,
            context = context,
            modifier = Modifier.fillMaxSize(),
        )

        MediaReviewPreviewButton(
            type = entity.type,
            onClick = onPreview,
            modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
        )

        if (entity.isFavorite && !selected) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "已收藏",
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(15.dp),
            )
        }

        if (selected) {
            Box(Modifier.fillMaxSize().background(visuals.selected.copy(alpha = 0.08f)))
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(28.dp),
                shape = CircleShape,
                color = visuals.selected,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "已选择", modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

/** Video = play; image = magnify. This action never changes review selection/decision state. */
@Composable
fun MediaReviewPreviewButton(
    type: WallpaperType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalWallpaperVisuals.current
    val isVideo = type == WallpaperType.VIDEO
    val label = if (isVideo) "播放查看视频" else "放大查看图片"
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(32.dp)
            .semantics { contentDescription = label },
        shape = CircleShape,
        color = visuals.navigation.copy(alpha = 0.88f),
        contentColor = Color.White.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.ZoomIn,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.96f),
                modifier = Modifier.size(if (isVideo) 18.dp else 17.dp),
            )
        }
    }
}
