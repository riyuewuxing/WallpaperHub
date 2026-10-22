package com.wallpaperhub.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    toolId: String,
    onBack: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val transitionType by vm.transitionType.collectAsStateWithLifecycle()
    val localStats by vm.localCopyStats.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val session by vm.playbackSession.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(toolTitle(toolId), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when (toolId) {
            "cleanup" -> MediaHygieneScreen(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 10.dp,
                    bottom = 32.dp,
                ),
            )
            "grouping" -> LibraryGroupManagerScreen(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 10.dp,
                    bottom = 32.dp,
                ),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 10.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    when (toolId) {
                        "storage" -> StorageSettings(localStats, cacheSize, vm)
                        "diagnostics" -> DiagnosticsSettings(session)
                        else -> TransitionSettings(transitionType, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitionSettings(transitionType: String, vm: LibraryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("转场只决定轮播切换的视觉方式，不改变播放顺序、切换间隔或单张壁纸构图。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text("效果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = transitionType == "crossfade", onClick = { vm.updateTransitionType("crossfade") }, label = { Text("Crossfade") })
            FilterChip(selected = transitionType == "zoom", onClick = { vm.updateTransitionType("zoom") }, label = { Text("Zoom") })
        }
    }
}

@Composable
private fun StorageSettings(
    stats: com.wallpaperhub.data.repo.WallpaperRepository.LocalCopyStats?,
    cacheSize: String,
    vm: LibraryViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("这里只管理 WallpaperHub 自己保存的本地副本和可重新生成的运行缓存，不碰系统相册原文件。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        SettingMetric(title = "本地副本", value = stats?.let { "${it.fileCount} 个文件 · ${MediaMetadataFormatter.formatBytes(it.totalBytes)}" } ?: "正在读取")
        SettingMetric(title = "运行缓存", value = cacheSize)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::refreshLocalCopyStats) { Text("刷新统计") }
            OutlinedButton(onClick = vm::clearAppCaches) { Text("清理缓存") }
        }
    }
}

@Composable
private fun SettingMetric(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DiagnosticsSettings(session: com.wallpaperhub.playback.model.PlaybackSession) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("仅用于开发验收。这里显示内部播放阶段、资源指纹和队列版本，不显示完整媒体 URI。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text("播放阶段 · ${session.phase.name}", style = MaterialTheme.typography.titleMedium)
        Text("当前资源 · ${WallpaperDebugLog.uri(session.appliedUri)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("目标资源 · ${WallpaperDebugLog.uri(session.desiredUri)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("队列版本 · ${session.queueRevision} · ${session.queue.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val safeError = session.errorMessage?.let(WallpaperDebugLog::safeText)
        Text("最近错误 · ${safeError ?: "无"}", color = if (safeError == null) MaterialTheme.colorScheme.onSurfaceVariant else WallpaperTokens.Error)
    }
}

private fun toolTitle(id: String): String = when (id) {
    "cleanup" -> "智能清理"
    "grouping" -> "分组管理"
    "storage" -> "存储与缓存"
    "diagnostics" -> "高级诊断"
    else -> "转场效果"
}
