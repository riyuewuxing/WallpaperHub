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
import androidx.compose.material3.FilterChip
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
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.MediaHygieneViewModel
import com.wallpaperhub.ui.viewmodel.QualityHygieneState
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val QUALITY_ITEMS_PER_PAGE = 50

private enum class QualityMediaFilter { ALL, IMAGE, VIDEO }
private enum class QualityRiskFilter { HIGH_CONFIDENCE, ALL_TECHNICAL }

@Composable
internal fun MediaQualityHygieneScreen(
    modifier: Modifier,
    vm: MediaHygieneViewModel,
) {
    val state by vm.qualityState.collectAsStateWithLifecycle()
    val ready = state as? QualityHygieneState.Ready
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmRemoval by remember { mutableStateOf(false) }
    var quickPreview by remember { mutableStateOf<WallpaperEntity?>(null) }
    var mediaFilter by remember { mutableStateOf(QualityMediaFilter.ALL) }
    var riskFilter by remember { mutableStateOf(QualityRiskFilter.HIGH_CONFIDENCE) }
    var page by remember { mutableIntStateOf(0) }

    fun changePage(target: Int, pages: Int) {
        page = target.coerceIn(0, (pages - 1).coerceAtLeast(0))
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
                Text("画质检查", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "默认只展示高置信度画质风险。可切换图片/视频，也可展开全部技术风险；结果只用于复核，不会自动删除。每个媒体左上角都可独立查看：视频播放、图片放大；返回保留当前筛选、页码和选择。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val value = state) {
            QualityHygieneState.Idle -> item { Button(onClick = vm::scanQuality) { Text("开始画质检查") } }
            is QualityHygieneState.Scanning -> item {
                Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(if (value.total > 0) "正在分析 ${value.current} / ${value.total}" else "正在准备分析")
                    }
                }
            }
            is QualityHygieneState.Failed -> item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("画质分析失败", color = WallpaperTokens.Error)
                    Text(value.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::scanQuality) { Text("重新检查") }
                }
            }
            is QualityHygieneState.Ready -> {
                val filtered = value.items.filter { item ->
                    val mediaMatches = when (mediaFilter) {
                        QualityMediaFilter.ALL -> true
                        QualityMediaFilter.IMAGE -> item.entity.type == WallpaperType.STATIC
                        QualityMediaFilter.VIDEO -> item.entity.type == WallpaperType.VIDEO
                    }
                    val riskMatches = riskFilter == QualityRiskFilter.ALL_TECHNICAL || item.highConfidence
                    mediaMatches && riskMatches
                }
                val pages = ceil(filtered.size / QUALITY_ITEMS_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
                val safePage = page.coerceIn(0, pages - 1)
                val pageItems = filtered.drop(safePage * QUALITY_ITEMS_PER_PAGE).take(QUALITY_ITEMS_PER_PAGE)
                val highCount = value.items.count { it.highConfidence }

                item {
                    LaunchedEffect(mediaFilter, riskFilter, value.items.size) { page = 0 }
                    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("高置信风险 $highCount 项 · 待复核技术风险 ${value.items.size} 项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("当前筛选 ${filtered.size} 项 · 已选 ${value.selectedIds.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QualityMediaFilter.entries.forEach { option ->
                                    FilterChip(selected = mediaFilter == option, onClick = { mediaFilter = option }, label = { Text(qualityMediaLabel(option)) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = riskFilter == QualityRiskFilter.HIGH_CONFIDENCE,
                                    onClick = { riskFilter = QualityRiskFilter.HIGH_CONFIDENCE },
                                    label = { Text("高置信") },
                                )
                                FilterChip(
                                    selected = riskFilter == QualityRiskFilter.ALL_TECHNICAL,
                                    onClick = { riskFilter = QualityRiskFilter.ALL_TECHNICAL },
                                    label = { Text("全部技术风险") },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = value.selectedIds.isNotEmpty(), onClick = vm::clearQualitySelection) { Text("清空") }
                                Button(enabled = value.selectedIds.isNotEmpty(), onClick = { confirmRemoval = true }) { Text("移除已选") }
                                OutlinedButton(onClick = vm::scanQuality) { Text("重新分析") }
                            }
                            QualityPageNav(
                                page = safePage,
                                pages = pages,
                                total = filtered.size,
                                previous = { changePage(safePage - 1, pages) },
                                next = { changePage(safePage + 1, pages) },
                            )
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (riskFilter == QualityRiskFilter.HIGH_CONFIDENCE) "当前筛选下没有待复核的高置信画质风险" else "当前筛选下没有待复核的画质风险",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                } else {
                    items(pageItems, key = { it.entity.id }) { item ->
                        MediaQualityReviewCard(
                            item = item,
                            selected = item.entity.id in value.selectedIds,
                            toggle = { vm.toggleQualitySelected(item.entity.id) },
                            dismiss = { vm.dismissQuality(item.entity.id) },
                            previewMedia = { quickPreview = item.entity },
                        )
                    }
                    item {
                        QualityPageNav(
                            page = safePage,
                            pages = pages,
                            total = filtered.size,
                            previous = { changePage(safePage - 1, pages) },
                            next = { changePage(safePage + 1, pages) },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemoval) {
        val count = ready?.selectedIds?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除所选画质风险项？") },
            text = { Text("将从 WallpaperHub 图库移除 $count 项。画质分析只是技术筛查，请确认你已经看过这些项目；系统相册原文件不会被删除。") },
            confirmButton = { TextButton(enabled = count > 0, onClick = { confirmRemoval = false; vm.removeQualitySelected() }) { Text("确认移除", color = WallpaperTokens.Error) } },
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
private fun QualityPageNav(
    page: Int,
    pages: Int,
    total: Int,
    previous: () -> Unit,
    next: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(enabled = page > 0, onClick = previous) { Text("上一页") }
        Text("第 ${page + 1} / $pages 页 · 共 $total 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = page + 1 < pages, onClick = next) { Text("下一页") }
    }
}

private fun qualityMediaLabel(filter: QualityMediaFilter): String = when (filter) {
    QualityMediaFilter.ALL -> "全部"
    QualityMediaFilter.IMAGE -> "图片"
    QualityMediaFilter.VIDEO -> "视频"
}
