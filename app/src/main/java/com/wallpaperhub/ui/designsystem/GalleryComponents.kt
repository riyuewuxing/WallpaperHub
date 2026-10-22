package com.wallpaperhub.ui.designsystem

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals
import com.wallpaperhub.wallpaper.PrivateFileUriHelper

private const val PORTRAIT_MEDIA_ASPECT = 9f / 16f

@Composable
fun PlaybackStatusPillV2(
    phase: PlaybackPhase,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    val visuals = LocalWallpaperVisuals.current
    val (label, color, icon) = when (phase) {
        PlaybackPhase.APPLIED -> Triple(playbackStatusLabel(phase, errorMessage), visuals.success, Icons.Default.CheckCircle)
        PlaybackPhase.REQUESTED,
        PlaybackPhase.RESOLVING,
        PlaybackPhase.PREPARING,
        PlaybackPhase.READY,
        PlaybackPhase.TRANSITIONING -> Triple(playbackStatusLabel(phase, errorMessage), visuals.accent, Icons.Default.PlayArrow)
        PlaybackPhase.FAILED -> Triple(playbackStatusLabel(phase, errorMessage), visuals.error, Icons.Default.ErrorOutline)
        PlaybackPhase.IDLE -> Triple(playbackStatusLabel(phase, errorMessage), visuals.textSecondary, Icons.Default.PlayArrow)
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = visuals.navigation.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun NowPlayingHeroV2(
    current: WallpaperEntity?,
    next: WallpaperEntity?,
    phase: PlaybackPhase,
    errorMessage: String?,
    context: Context,
    onPrevious: () -> Unit,
    onPrimary: () -> Unit,
    onNext: () -> Unit,
    onOpenFlow: (() -> Unit)?,
    heroHeight: Dp = 390.dp,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalWallpaperVisuals.current
    val heroRequest = current?.let { entity ->
        remember(entity.uri) {
            ImageRequest.Builder(context)
                .data(PrivateFileUriHelper.safeUri(context, entity.uri))
                .size(720, 1280)
                .crossfade(false)
                .build()
        }
    }
    val mediaHeight = heroHeight - 70.dp

    Column(
        modifier = modifier.fillMaxWidth().height(heroHeight),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mediaHeight)
                .background(
                    Brush.radialGradient(
                        colors = listOf(visuals.glow.copy(alpha = 0.13f), Color.Transparent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(PORTRAIT_MEDIA_ASPECT)
                    .clip(WallpaperTokens.ShapeLg)
                    .background(visuals.surfaceHigh)
                    .then(if (onOpenFlow != null) Modifier.clickable { onOpenFlow.invoke() } else Modifier),
            ) {
                if (current != null && heroRequest != null) {
                    AsyncImage(
                        model = heroRequest,
                        contentDescription = current.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black.copy(alpha = 0.10f),
                                    0.68f to Color.Transparent,
                                    1.00f to Color.Black.copy(alpha = 0.42f),
                                ),
                            ),
                        ),
                )

                PlaybackStatusPillV2(
                    phase = phase,
                    errorMessage = errorMessage,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    current?.name ?: "当前桌面",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        current == null -> "导入图片或视频后从这里开始"
                        next == null -> mediaSummary(current)
                        else -> "下一张 · ${next.name}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HeroControl(onClick = onPrevious, enabled = current != null, contentDescription = "上一张") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Surface(
                onClick = onPrimary,
                enabled = current != null,
                modifier = Modifier
                    .size(50.dp)
                    .semantics { contentDescription = "查看与编辑当前壁纸" },
                shape = CircleShape,
                color = visuals.primaryButton,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(1.dp, visuals.glow.copy(alpha = 0.26f)),
                tonalElevation = 0.dp,
                shadowElevation = 5.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
            HeroControl(onClick = onNext, enabled = current != null, contentDescription = "下一张") {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun HeroControl(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = visuals.secondaryButton.copy(alpha = 0.90f),
        contentColor = visuals.textPrimary.copy(alpha = if (enabled) 0.92f else 0.36f),
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

internal fun playbackStatusLabel(phase: PlaybackPhase, errorMessage: String? = null): String = when (phase) {
    PlaybackPhase.APPLIED -> "已应用 · 服务正常"
    PlaybackPhase.REQUESTED,
    PlaybackPhase.RESOLVING,
    PlaybackPhase.PREPARING,
    PlaybackPhase.READY,
    PlaybackPhase.TRANSITIONING -> "正在准备壁纸"
    PlaybackPhase.FAILED -> errorMessage ?: "应用失败 · 可重试"
    PlaybackPhase.IDLE -> "尚未设为壁纸"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperTileV2(
    entity: WallpaperEntity,
    context: Context,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
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
            .aspectRatio(PORTRAIT_MEDIA_ASPECT)
            .clip(WallpaperTokens.ShapeMd)
            .border(frameWidth, frameColor, WallpaperTokens.ShapeMd)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CachedWallpaperTileImage(
            entity = entity,
            context = context,
            modifier = Modifier.fillMaxSize(),
        )

        if (current) {
            StatusBadge(
                text = "当前",
                color = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
            )
        } else if (entity.type == WallpaperType.VIDEO) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp).size(28.dp),
                shape = CircleShape,
                color = visuals.navigation.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "视频",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

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

@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = visuals.navigation.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

fun mediaSummary(entity: WallpaperEntity): String {
    val type = if (entity.type == WallpaperType.VIDEO) "视频" else "图片"
    val resolution = if (entity.width != null && entity.height != null) "${entity.width}×${entity.height}" else "未读取分辨率"
    return "$type · $resolution"
}
