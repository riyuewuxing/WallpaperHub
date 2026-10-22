package com.wallpaperhub.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.designsystem.PlaybackStatusPillV2
import com.wallpaperhub.ui.designsystem.WallpaperMediaPreview
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.designsystem.mediaSummary
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.ui.viewmodel.DetailViewModel

@Composable
fun DetailScreen(
    wallpaperId: String,
    onBack: () -> Unit,
    onOpenPreview: (String) -> Unit,
    vm: DetailViewModel = viewModel(),
) {
    LaunchedEffect(wallpaperId) { vm.load(wallpaperId) }
    val entity by vm.entity.collectAsStateWithLifecycle()
    val fileSize by vm.fileSize.collectAsStateWithLifecycle()
    val session by vm.playbackSession.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showMore by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var mediaPreviewActive by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mediaPreviewActive = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> mediaPreviewActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val item = entity
        if (item == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.70f)
                    .pointerInput(item.id) {
                        detectTapGestures(onDoubleTap = { vm.toggleFavorite() })
                    },
            ) {
                WallpaperMediaPreview(
                    entity = item,
                    active = mediaPreviewActive,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black.copy(alpha = 0.30f),
                                    0.28f to Color.Transparent,
                                    0.62f to Color.Transparent,
                                    1.00f to Color.Black.copy(alpha = 0.88f),
                                ),
                            ),
                        ),
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingIconButton(
                        onClick = {
                            mediaPreviewActive = false
                            onBack()
                        },
                        description = "返回",
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Spacer(Modifier.weight(1f))
                    FloatingIconButton(
                        onClick = { showMore = !showMore },
                        description = "更多信息",
                        filled = showMore,
                    ) {
                        Icon(Icons.Default.MoreHoriz, null)
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaybackStatusPillV2(session.phase, errorMessage = session.errorMessage)
                    Text(
                        item.name,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            add(mediaSummary(item))
                            add(MediaMetadataFormatter.formatBytes(fileSize))
                        }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (showMore) DetailInfoPopover(item, fileSize)
            }

            DetailActionPanel(
                item = item,
                busy = busy,
                isCurrent = session.appliedUri == item.uri,
                onOpenPreview = {
                    mediaPreviewActive = false
                    onOpenPreview(it)
                },
                onTogglePlaylist = vm::togglePlaylist,
                onToggleFavorite = vm::toggleFavorite,
            )
        }
    }
}

@Composable
private fun BoxScope.DetailInfoPopover(item: WallpaperEntity, fileSize: Long?) {
    Surface(
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = WallpaperTokens.NavigationChrome.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${if (item.type == WallpaperType.VIDEO) "视频" else "图片"} · ${MediaMetadataFormatter.formatBytes(fileSize)}")
            if (item.type == WallpaperType.VIDEO) {
                Text("时长 · ${MediaMetadataFormatter.formatDuration(item.duration)}")
            }
            Text(
                "加入于 ${MediaMetadataFormatter.formatAddedDate(item.addedAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailActionPanel(
    item: WallpaperEntity,
    busy: Boolean,
    isCurrent: Boolean,
    onOpenPreview: (String) -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailStatePill(
                    label = if (isCurrent) "当前桌面" else "图库壁纸",
                    emphasized = isCurrent,
                    modifier = Modifier.weight(1f),
                )
                DetailStatePill(
                    label = if (item.inPlaylist) "已加入轮播" else "未加入轮播",
                    emphasized = item.inPlaylist,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Button(
                    onClick = { onOpenPreview(item.id) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                ) {
                    Text("编辑并应用", style = MaterialTheme.typography.labelLarge)
                }
                DetailRoundAction(
                    active = item.isFavorite,
                    label = "收藏",
                    onClick = onToggleFavorite,
                ) {
                    Icon(
                        if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                    )
                }
                DetailRoundAction(
                    active = item.inPlaylist,
                    label = "轮播",
                    onClick = onTogglePlaylist,
                ) {
                    Icon(
                        if (item.inPlaylist) Icons.AutoMirrored.Filled.PlaylistAddCheck
                        else Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailStatePill(
    label: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            else WallpaperTokens.StrokeSubtle,
        ),
        tonalElevation = 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DetailRoundAction(
    active: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(52.dp).semantics { contentDescription = label },
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primaryContainer else WallpaperTokens.NavigationChrome,
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
            tonalElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FloatingIconButton(
    onClick: () -> Unit,
    description: String,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = WallpaperTokens.NavigationChrome.copy(alpha = 0.86f),
        contentColor = if (filled) MaterialTheme.colorScheme.primary else Color.White,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
