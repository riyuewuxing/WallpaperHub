package com.wallpaperhub.ui.flow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.playback.engine.PlaybackOrderCoordinator
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.designsystem.NowPlayingHeroV2
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.designsystem.mediaSummary
import com.wallpaperhub.ui.viewmodel.LibraryViewModel
import com.wallpaperhub.wallpaper.PrivateFileUriHelper
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowV1(
    onOpenDetail: (String) -> Unit = {},
    vm: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by vm.playlistQueue.collectAsStateWithLifecycle()
    val settings by vm.playlistSettings.collectAsStateWithLifecycle()
    val session by vm.playbackSession.collectAsStateWithLifecycle()
    val transitionType by vm.transitionType.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val current = remember(queue, session.appliedUri, session.currentIndex) {
        queue.getOrNull(session.currentIndex)?.takeIf { it.uri == session.appliedUri }
            ?: queue.firstOrNull { it.uri == session.appliedUri }
            ?: queue.getOrNull(session.currentIndex)
            ?: queue.firstOrNull()
    }
    val next = remember(queue, session.nextPreviewUri) {
        session.nextPreviewUri?.let { uri -> queue.firstOrNull { it.uri == uri } }
    }

    val observedAppliedIndex = remember(queue, session.appliedUri) {
        queue.indexOfFirst { it.uri == session.appliedUri }
    }
    val observedDesiredIndex = remember(queue, session.desiredUri) {
        queue.indexOfFirst { it.uri == session.desiredUri }
    }
    val observedFirstUri = queue.firstOrNull()?.uri
    val observedStoredUri = queue.getOrNull(session.currentIndex)?.uri
    LaunchedEffect(
        session.queueRevision,
        queue.size,
        observedAppliedIndex,
        observedDesiredIndex,
        observedFirstUri,
        observedStoredUri,
        session.appliedUri,
        session.desiredUri,
        session.currentIndex,
        session.nextPreviewUri,
        session.phase,
        settings.playbackMode,
        settings.sequentialReversed,
    ) {
        WallpaperDebugLog.i(
            "flow_ui",
            "carousel_state_observed",
            "mode" to settings.playbackMode,
            "reverse" to settings.sequentialReversed,
            "queueSize" to queue.size,
            "revision" to session.queueRevision,
            "phase" to session.phase,
            "applied" to WallpaperDebugLog.uri(session.appliedUri),
            "appliedIndex" to observedAppliedIndex,
            "desired" to WallpaperDebugLog.uri(session.desiredUri),
            "desiredIndex" to observedDesiredIndex,
            "storedIndex" to session.currentIndex,
            "storedUri" to WallpaperDebugLog.uri(observedStoredUri),
            "next" to WallpaperDebugLog.uri(session.nextPreviewUri),
            "first" to WallpaperDebugLog.uri(observedFirstUri),
        )
    }

    val applyPlan: (VideoWallpaperSettings.PlaybackMode, Boolean?) -> Unit = { mode, reversed ->
        scope.launch {
            runCatching {
                PlaybackOrderCoordinator.switchMode(
                    context = context,
                    mode = mode,
                    sequentialReversed = reversed,
                )
            }.onSuccess { result ->
                WallpaperDebugLog.i(
                    "flow_ui",
                    "playback_plan_regenerated",
                    "mode" to result.mode,
                    "reverse" to (reversed ?: settings.sequentialReversed),
                    "queueSize" to result.queueSize,
                    "current" to WallpaperDebugLog.uri(result.currentUri),
                    "first" to WallpaperDebugLog.uri(result.firstUri),
                    "next" to WallpaperDebugLog.uri(result.queue.getOrNull(1)),
                    "revision" to result.revision,
                )
                vm.refreshAllSettings()
            }.onFailure { error ->
                WallpaperDebugLog.e(
                    "flow_ui",
                    "playback_plan_regenerate_failed",
                    error,
                    "mode" to mode,
                    "reverse" to reversed,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FlowHeader(
                queueSize = queue.size,
                settings = settings,
                transitionType = transitionType,
                onSettings = { settingsOpen = true },
            )
        }

        if (queue.isEmpty()) {
            item { EmptyQueueCard() }
        } else {
            item {
                NowPlayingHeroV2(
                    current = current,
                    next = next,
                    phase = session.phase,
                    errorMessage = session.errorMessage,
                    context = context,
                    onPrevious = vm::showPreviousWallpaper,
                    onPrimary = { current?.let { onOpenDetail(it.id) } },
                    onNext = vm::showNextWallpaper,
                    onOpenFlow = current?.let { item -> { onOpenDetail(item.id) } },
                    heroHeight = 404.dp,
                )
            }
            next?.let { nextItem ->
                item { NextUpRow(nextItem, context, onOpen = { onOpenDetail(nextItem.id) }) }
            }

            item {
                SectionHeader("播放队列", "${queue.size} 项 · 浏览不会切换桌面")
            }
            item {
                CarouselRail(
                    queue = queue,
                    appliedUri = session.appliedUri,
                    desiredUri = session.desiredUri,
                    phase = session.phase,
                    storedIndex = session.currentIndex,
                    queueRevision = session.queueRevision,
                    allowReorder = false,
                    onApply = vm::applyWallpaper,
                    onMove = {},
                    onOpen = { onOpenDetail(it.id) },
                )
            }
        }
    }

    if (settingsOpen) {
        PlaybackSettingsSheet(
            settings = settings,
            transitionType = transitionType,
            canClear = queue.isNotEmpty(),
            onDismiss = { settingsOpen = false },
            onPlan = applyPlan,
            onInterval = vm::updateRotateInterval,
            onTransitionType = vm::updateTransitionType,
            onClear = {
                vm.clearPlaylist()
                settingsOpen = false
            },
        )
    }
}

@Composable
private fun FlowHeader(
    queueSize: Int,
    settings: VideoWallpaperSettings.Settings,
    transitionType: String,
    onSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("轮播", style = MaterialTheme.typography.headlineLarge)
            Text(
                "$queueSize 项 · ${modeSummary(settings)} · ${intervalSummary(settings)} · ${transitionLabel(transitionType)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "轮播设置")
        }
    }
}

@Composable
private fun NextUpRow(
    item: WallpaperEntity,
    context: android.content.Context,
    onOpen: () -> Unit,
) {
    val request = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(PrivateFileUriHelper.safeUri(context, item.uri))
            .size(360, 640)
            .crossfade(false)
            .build()
    }
    Surface(
        onClick = onOpen,
        color = Color.Transparent,
        shape = WallpaperTokens.ShapeMd,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = request,
                contentDescription = "下一张：${item.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .height(96.dp)
                    .clip(WallpaperTokens.ShapeMd),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("下一张", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mediaSummary(item), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private data class StrategyChoice(
    val label: String,
    val mode: VideoWallpaperSettings.PlaybackMode,
    val reversed: Boolean? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSettingsSheet(
    settings: VideoWallpaperSettings.Settings,
    transitionType: String,
    canClear: Boolean,
    onDismiss: () -> Unit,
    onPlan: (VideoWallpaperSettings.PlaybackMode, Boolean?) -> Unit,
    onInterval: (Int) -> Unit,
    onTransitionType: (String) -> Unit,
    onClear: () -> Unit,
) {
    val choices = listOf(
        StrategyChoice("顺序", VideoWallpaperSettings.PlaybackMode.SEQUENTIAL, false),
        StrategyChoice("逆序", VideoWallpaperSettings.PlaybackMode.SEQUENTIAL, true),
        StrategyChoice("Shuffle", VideoWallpaperSettings.PlaybackMode.SHUFFLE),
        StrategyChoice("Random", VideoWallpaperSettings.PlaybackMode.RANDOM),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("播放设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "只保留影响轮播行为的设置，媒体浏览留在主页面。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SettingsSection("播放方式") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { choice ->
                        val selected = when (choice.mode) {
                            VideoWallpaperSettings.PlaybackMode.SEQUENTIAL ->
                                settings.playbackMode == choice.mode && settings.sequentialReversed == choice.reversed
                            else -> settings.playbackMode == choice.mode
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onPlan(choice.mode, choice.reversed) },
                            label = { Text(choice.label, maxLines = 1) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SettingsSection("切换间隔") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoWallpaperSettings.intervalOptions.forEach { (seconds, label) ->
                        val selected = settings.rotateIntervalSeconds == seconds
                        Surface(
                            onClick = { onInterval(seconds) },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                            shape = WallpaperTokens.ShapeMd,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "当前选择",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsSection("转场") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("crossfade" to "Crossfade", "zoom" to "Zoom").forEach { (key, label) ->
                        FilterChip(
                            selected = transitionType == key,
                            onClick = { onTransitionType(key) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (canClear) {
                Surface(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    contentColor = WallpaperTokens.Error,
                    shape = WallpaperTokens.ShapeMd,
                    border = BorderStroke(1.dp, WallpaperTokens.Error.copy(alpha = 0.28f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("清空轮播队列", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

private fun modeSummary(settings: VideoWallpaperSettings.Settings): String = when (settings.playbackMode) {
    VideoWallpaperSettings.PlaybackMode.SEQUENTIAL -> if (settings.sequentialReversed) "逆序" else "顺序"
    VideoWallpaperSettings.PlaybackMode.SHUFFLE -> "Shuffle"
    VideoWallpaperSettings.PlaybackMode.RANDOM -> "Random"
}

private fun intervalSummary(settings: VideoWallpaperSettings.Settings): String =
    VideoWallpaperSettings.intervalOptions.firstOrNull { it.first == settings.rotateIntervalSeconds }?.second
        ?: "跟随媒体"

private fun transitionLabel(transitionType: String): String = if (transitionType == "zoom") "Zoom" else "Crossfade"

@Composable
private fun EmptyQueueCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = WallpaperTokens.ShapeLg,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("轮播还没有内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("导入的新壁纸会自动加入轮播。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
