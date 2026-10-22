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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.BuildConfig
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals

private data class ToolGroup(val title: String, val items: List<ToolItem>)
private data class ToolItem(val id: String, val title: String, val summary: String, val icon: ImageVector)

@Composable
fun SettingsV1(
    onOpenTool: (String) -> Unit = {},
    vm: SettingsSummaryViewModel = viewModel(),
) {
    val transitionType by vm.transitionType.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val visuals = LocalWallpaperVisuals.current

    val groups = buildList {
        add(ToolGroup("个性化", listOf(ToolItem("theme", "主题与场景", "Scene、材质、颜色、粒子、点击反馈、视差与性能", Icons.Default.Palette))))
        add(ToolGroup("体验", listOf(ToolItem("performance", "转场效果", if (transitionType == "zoom") "当前 · Zoom" else "当前 · Crossfade", Icons.Default.Palette))))
        add(
            ToolGroup(
                "存储",
                listOf(
                    ToolItem("cleanup", "智能清理", "完全重复、相似内容与画质检查", Icons.Default.FilterList),
                    ToolItem("grouping", "分组管理", "筛选、批量归组与未分组整理", Icons.Default.FolderSpecial),
                    ToolItem("storage", "存储与缓存", "运行缓存 $cacheSize · 本地副本与清理", Icons.Default.Storage),
                ),
            )
        )
        if (BuildConfig.DEBUG) {
            add(ToolGroup("开发", listOf(
                ToolItem("diagnostics", "高级诊断", "播放阶段、队列版本与错误信息", Icons.Default.BugReport),
                ToolItem("showcase", "UI Showroom", "视觉验收夹具，仅 Debug 包可见", Icons.Default.BugReport),
            )))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设置", style = MaterialTheme.typography.headlineLarge, color = visuals.textPrimary)
                Text("场景、主题、壁纸体验、存储与诊断", color = visuals.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        groups.forEach { group -> item(key = group.title) { ToolGroupPanel(group = group, onOpen = onOpenTool) } }
    }
}

@Composable
private fun ToolGroupPanel(group: ToolGroup, onOpen: (String) -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(group.title, style = MaterialTheme.typography.labelMedium, color = visuals.textSecondary, modifier = Modifier.padding(start = 4.dp))
        Surface(
            color = WallpaperTokens.TextGlass,
            contentColor = visuals.textPrimary,
            shape = WallpaperTokens.ShapeLg,
            border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.42f)),
            tonalElevation = 0.dp,
        ) {
            Column {
                group.items.forEachIndexed { index, item ->
                    SettingRow(item = item, onClick = { onOpen(item.id) })
                    if (index < group.items.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 76.dp, end = 14.dp), color = visuals.stroke.copy(alpha = 0.26f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(item: ToolItem, onClick: () -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    Surface(onClick = onClick, color = Color.Transparent, contentColor = visuals.textPrimary) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = visuals.secondaryButton.copy(alpha = 0.58f),
                contentColor = visuals.accent,
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = visuals.textPrimary)
                Text(item.summary, color = visuals.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = visuals.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
