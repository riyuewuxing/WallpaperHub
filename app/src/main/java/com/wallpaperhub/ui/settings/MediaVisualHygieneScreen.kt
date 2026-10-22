package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import com.wallpaperhub.analysis.DuplicateKind
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.MediaHygieneViewModel
import com.wallpaperhub.ui.viewmodel.VisualHygieneState
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val VISUAL_GROUPS_PER_PAGE = 12

@Composable
internal fun MediaVisualHygieneScreen(
    modifier: Modifier,
    vm: MediaHygieneViewModel,
) {
    val state by vm.visualState.collectAsStateWithLifecycle()
    val ready = state as? VisualHygieneState.Ready
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var quickPreview by remember { mutableStateOf<WallpaperEntity?>(null) }
    val pages = ready?.groups?.size?.let { ceil(it / VISUAL_GROUPS_PER_PAGE.toDouble()).toInt().coerceAtLeast(1) } ?: 1

    LaunchedEffect(ready?.groups?.size) { page = page.coerceIn(0, pages - 1) }
    fun changePage(target: Int) {
        page = target.coerceIn(0, pages - 1)
        scope.launch { listState.scrollToItem(0) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("相似内容", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "用代表画面指纹查找重新压缩、不同分辨率或轻微变化的相似素材。检测只是候选：高度相似可批量选择，可能相似永远只做人工复核；每张媒体左上角都可独立查看，视频播放、图片放大，返回保留当前复核位置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val value = state) {
            VisualHygieneState.Idle -> item { Button(onClick = vm::scanVisualDuplicates) { Text("扫描相似内容") } }
            is VisualHygieneState.Scanning -> item {
                Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(if (value.total > 0) "正在分析代表画面 ${value.current} / ${value.total}" else "正在准备分析")
                    }
                }
            }
            is VisualHygieneState.Failed -> item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("相似内容分析失败", color = WallpaperTokens.Error)
                    Text(value.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::scanVisualDuplicates) { Text("重新扫描") }
                }
            }
            is VisualHygieneState.Ready -> {
                if (value.groups.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("没有待复核的相似内容", style = MaterialTheme.typography.titleLarge)
                            Text("已分析 ${value.scannedCount} 项${if (value.failedCount > 0) " · ${value.failedCount} 项读取失败" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = vm::scanVisualDuplicates) { Text("重新扫描") }
                        }
                    }
                } else {
                    val highCount = value.groups.count { it.kind == DuplicateKind.VISUAL_HIGH }
                    val possibleCount = value.groups.size - highCount
                    item {
                        Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("高度相似 $highCount 组 · 可能相似 $possibleCount 组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("已分析 ${value.scannedCount} 项${if (value.failedCount > 0) " · 读取失败 ${value.failedCount}" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = value.selectedIds.isNotEmpty(), onClick = vm::clearVisualSelection) { Text("清空") }
                                    OutlinedButton(enabled = highCount > 0, onClick = vm::selectAllVisualSuggested) {
                                        Text(if (highCount > 0) "全选高度相似" else "无高度相似可批选")
                                    }
                                    Button(enabled = value.selectedIds.isNotEmpty(), onClick = { confirmRemoval = true }) { Text("移除已选") }
                                }
                                VisualPageNav(page, pages, { changePage(page - 1) }, { changePage(page + 1) })
                            }
                        }
                    }
                    val pageGroups = value.groups.drop(page * VISUAL_GROUPS_PER_PAGE).take(VISUAL_GROUPS_PER_PAGE)
                    items(pageGroups, key = { it.fingerprint }) { group ->
                        MediaVisualDuplicateGroupCard(
                            group = group,
                            selectedIds = value.selectedIds,
                            toggle = vm::toggleVisualSelected,
                            setKeeper = { id -> vm.setVisualKeeper(group.fingerprint, id) },
                            dismissGroup = { vm.dismissVisualGroup(group.fingerprint) },
                            previewMedia = { quickPreview = it },
                        )
                    }
                    item { VisualPageNav(page, pages, { changePage(page - 1) }, { changePage(page + 1) }) }
                }
            }
        }
    }

    if (confirmRemoval) {
        val count = ready?.selectedIds?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除所选相似项？") },
            text = { Text("将从 WallpaperHub 图库移除 $count 项。相似判断不是文件级完全一致，请只删除你已经肉眼确认的项目；系统相册原文件不会被删除。") },
            confirmButton = { TextButton(enabled = count > 0, onClick = { confirmRemoval = false; vm.removeVisualSelected() }) { Text("确认移除", color = WallpaperTokens.Error) } },
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
private fun VisualPageNav(page: Int, pages: Int, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(enabled = page > 0, onClick = previous) { Text("上一页") }
        Text("第 ${page + 1} / $pages 页", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = page + 1 < pages, onClick = next) { Text("下一页") }
    }
}
