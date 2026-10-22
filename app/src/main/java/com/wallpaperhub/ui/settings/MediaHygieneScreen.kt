package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.MediaHygieneMode
import com.wallpaperhub.ui.viewmodel.MediaHygieneState
import com.wallpaperhub.ui.viewmodel.MediaHygieneViewModel
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val REVIEW_GROUPS_PER_PAGE = 12

@Composable
fun MediaHygieneScreen(
    modifier: Modifier = Modifier,
    vm: MediaHygieneViewModel = viewModel(),
) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    if (mode != MediaHygieneMode.EXACT) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HygieneModeSwitchRow(mode = mode, onMode = vm::setMode)
            when (mode) {
                MediaHygieneMode.VISUAL -> MediaVisualHygieneScreen(modifier = Modifier.weight(1f), vm = vm)
                MediaHygieneMode.QUALITY -> MediaQualityHygieneScreen(modifier = Modifier.weight(1f), vm = vm)
                MediaHygieneMode.EXACT -> Unit
            }
        }
        return
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? MediaHygieneState.Ready
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pageIndex by remember { mutableIntStateOf(0) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var quickPreview by remember { mutableStateOf<WallpaperEntity?>(null) }
    val pageCount = ready?.groups?.size?.let { ceil(it / REVIEW_GROUPS_PER_PAGE.toDouble()).toInt().coerceAtLeast(1) } ?: 1

    LaunchedEffect(ready?.groups?.size) { pageIndex = pageIndex.coerceIn(0, pageCount - 1) }

    fun changePage(next: Int) {
        pageIndex = next.coerceIn(0, pageCount - 1)
        scope.launch { listState.scrollToItem(0) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = if (ready?.groups?.isNotEmpty() == true) 88.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("智能清理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "完全重复内容只保留一份。点按复选框、媒体或长按媒体都可以选择；左上角独立查看按钮不会改变选择：视频播放，图片放大。当前正在使用的壁纸保持锁定。你也可以明确决定保留整组，系统会记住，不再反复提示已复核关系。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HygieneModeSwitchRow(mode = mode, onMode = vm::setMode)
                }
            }

            when (val value = state) {
                MediaHygieneState.Idle -> item { Button(onClick = vm::scanExactDuplicates) { Text("扫描完全重复内容") } }
                is MediaHygieneState.Scanning -> item {
                    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text(if (value.total > 0) "正在比较 ${value.current} / ${value.total} 个候选" else "正在建立候选集")
                        }
                    }
                }
                is MediaHygieneState.Failed -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("扫描失败", color = WallpaperTokens.Error)
                        Text(value.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = vm::scanExactDuplicates) { Text("重新扫描") }
                    }
                }
                is MediaHygieneState.Ready -> {
                    if (value.groups.isEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("没有待复核的完全重复内容", style = MaterialTheme.typography.titleLarge)
                                OutlinedButton(onClick = vm::scanExactDuplicates) { Text("重新扫描") }
                            }
                        }
                    } else {
                        val pageGroups = value.groups.drop(pageIndex * REVIEW_GROUPS_PER_PAGE).take(REVIEW_GROUPS_PER_PAGE)
                        item { SummaryCard(value, pageIndex, pageCount, { changePage(pageIndex - 1) }, { changePage(pageIndex + 1) }, vm::clearSelection) }
                        items(pageGroups, key = { it.fingerprint }) { group ->
                            MediaHygieneDuplicateGroupCard(
                                group = group,
                                selectedIds = value.selectedIds,
                                toggle = vm::toggleSelected,
                                setKeeper = { id -> vm.setKeeper(group.fingerprint, id) },
                                dismissGroup = { vm.dismissExactGroup(group.fingerprint) },
                                previewMedia = { quickPreview = it },
                            )
                        }
                        item { PageNav(pageIndex, pageCount, { changePage(pageIndex - 1) }, { changePage(pageIndex + 1) }) }
                    }
                }
            }
        }

        if (ready != null && ready.groups.isNotEmpty()) {
            MediaHygieneBottomActions(
                state = ready,
                onClearSelection = vm::clearSelection,
                onSelectAll = vm::selectAllSuggested,
                onRemove = { confirmRemoval = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    if (confirmRemoval) {
        val count = ready?.selectedIds?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除所选重复副本？") },
            text = { Text("将从 WallpaperHub 图库移除 $count 项。轮播中的副本会由现有播放列表逻辑安全移除；系统相册原文件不会被删除。") },
            confirmButton = { TextButton(enabled = count > 0, onClick = { confirmRemoval = false; vm.removeSelected() }) { Text("确认移除", color = WallpaperTokens.Error) } },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("取消") } },
        )
    }

    quickPreview?.let { entity ->
        if (entity.type == WallpaperType.VIDEO) {
            MediaHygieneVideoQuickPreview(entity = entity, onDismiss = { quickPreview = null })
        } else {
            MediaHygieneImageQuickPreview(entity = entity, onDismiss = { quickPreview = null })
        }
    }
}

@Composable
private fun HygieneModeSwitchRow(mode: MediaHygieneMode, onMode: (MediaHygieneMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = mode != MediaHygieneMode.EXACT, onClick = { onMode(MediaHygieneMode.EXACT) }) { Text("完全重复") }
        OutlinedButton(enabled = mode != MediaHygieneMode.VISUAL, onClick = { onMode(MediaHygieneMode.VISUAL) }) { Text("相似内容") }
        OutlinedButton(enabled = mode != MediaHygieneMode.QUALITY, onClick = { onMode(MediaHygieneMode.QUALITY) }) { Text("画质检查") }
    }
}

@Composable
private fun SummaryCard(
    state: MediaHygieneState.Ready,
    page: Int,
    pages: Int,
    previous: () -> Unit,
    next: () -> Unit,
    clear: () -> Unit,
) {
    val suggested = state.groups.sumOf { it.suggestedIds.size }
    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发现 ${state.groups.size} 组待复核完全重复", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("建议可清理 $suggested 项 · 当前已选 ${state.selectedIds.size} 项", color = MaterialTheme.colorScheme.primary)
            PageNav(page, pages, previous, next)
            TextButton(enabled = state.selectedIds.isNotEmpty(), onClick = clear) { Text("清空选择") }
        }
    }
}

@Composable
private fun PageNav(page: Int, pages: Int, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(enabled = page > 0, onClick = previous) { Text("上一页") }
        Text("第 ${page + 1} / $pages 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = page + 1 < pages, onClick = next) { Text("下一页") }
    }
}
