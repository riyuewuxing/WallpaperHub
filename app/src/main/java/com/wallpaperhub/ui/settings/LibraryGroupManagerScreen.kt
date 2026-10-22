package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.data.repo.Round4ImportGroupPreference
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.library.LibraryFilterState
import com.wallpaperhub.ui.library.matches
import com.wallpaperhub.ui.viewmodel.LibraryGroupingViewModel

@Composable
fun LibraryGroupManagerScreen(
    modifier: Modifier = Modifier,
    vm: LibraryGroupingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val wallpapers by vm.wallpapers.collectAsStateWithLifecycle()
    val groupTags by vm.groupTags.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(LibraryFilterState()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newGroup by remember { mutableStateOf("") }
    var importTarget by remember { mutableStateOf(Round4ImportGroupPreference.read(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    val displayTags = remember(groupTags, importTarget) {
        (groupTags + listOfNotNull(importTarget)).distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val filtered = remember(wallpapers, filters) { wallpapers.filter { it.matches(filters) } }
    val typedTag = newGroup.trim().take(48)
    val typedIsTarget = typedTag.isNotBlank() && importTarget.equals(typedTag, ignoreCase = true)

    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分组管理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "分组只整理图库，不改变轮播顺序、当前壁纸或构图。最主要的用途是筛选一批壁纸，以及让以后新导入的壁纸自动进入指定组。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                shape = WallpaperTokens.ShapeMd,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("当前新导入目标", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(importTarget ?: "未设置（新导入不会自动归组）", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }

        item { GroupManagerFilterStrip(filters = filters, groupTags = displayTags, onChange = { filters = it }) }

        item {
            Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前筛出 ${filtered.size} 项 · 已选 ${selectedIds.size} 项", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedIds = filtered.mapTo(linkedSetOf()) { it.id } }) { Text("全选筛选结果") }
                        OutlinedButton(enabled = selectedIds.isNotEmpty(), onClick = { selectedIds = emptySet() }) { Text("清空") }
                    }
                    OutlinedTextField(
                        value = newGroup,
                        onValueChange = { newGroup = it.take(48); notice = null },
                        label = { Text("分组名称") },
                        supportingText = { Text("选中壁纸后可批量归组；未选壁纸时可先建立一个新导入目标") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = typedTag.isNotBlank(),
                            onClick = {
                                val tag = typedTag
                                if (selectedIds.isNotEmpty()) {
                                    val count = selectedIds.size
                                    vm.assignGroup(selectedIds, tag)
                                    selectedIds = emptySet()
                                    notice = "已将 $count 项归入「$tag」"
                                } else {
                                    Round4ImportGroupPreference.write(context, tag)
                                    importTarget = tag
                                    notice = "已创建「$tag」，并设为新导入目标"
                                }
                            },
                        ) { Text(if (selectedIds.isEmpty()) "创建分组" else "创建/归入分组") }
                        OutlinedButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val count = selectedIds.size
                                vm.assignGroup(selectedIds, null)
                                selectedIds = emptySet()
                                notice = "已将 $count 项设为未分组"
                            },
                        ) { Text("设为未分组") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = typedTag.isNotBlank() && !typedIsTarget,
                            onClick = {
                                Round4ImportGroupPreference.write(context, typedTag)
                                importTarget = typedTag
                                notice = "已应用：之后新导入会自动归入「$typedTag」"
                            },
                        ) { Text(if (typedIsTarget) "已设为新导入目标" else "设为新导入目标") }
                        OutlinedButton(
                            enabled = importTarget != null,
                            onClick = {
                                Round4ImportGroupPreference.write(context, null)
                                importTarget = null
                                notice = "已关闭新导入自动归组"
                            },
                        ) { Text("关闭自动归组") }
                    }
                    if (displayTags.isNotEmpty()) {
                        Text("已有分组 · 点击后填入上方名称", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            displayTags.forEach { tag ->
                                FilterChip(
                                    selected = importTarget.equals(tag, ignoreCase = true),
                                    onClick = {
                                        newGroup = tag
                                        notice = if (importTarget.equals(tag, ignoreCase = true)) {
                                            "「$tag」当前就是新导入目标"
                                        } else {
                                            "已选择「$tag」；需要自动归组时点“设为新导入目标”"
                                        }
                                    },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }
        }

        items(filtered, key = { it.id }) { entity ->
            GroupManagerRow(
                entity = entity,
                selected = entity.id in selectedIds,
                onToggle = {
                    selectedIds = if (entity.id in selectedIds) selectedIds - entity.id else selectedIds + entity.id
                },
            )
        }
    }
}
