package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.library.LibraryFilterState
import com.wallpaperhub.ui.library.LibraryMediaFilter
import com.wallpaperhub.ui.library.LibraryOrientation

@Composable
internal fun GroupManagerFilterStrip(
    filters: LibraryFilterState,
    groupTags: List<String>,
    onChange: (LibraryFilterState) -> Unit,
) {
    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryMediaFilter.entries.forEach { option ->
                    FilterChip(selected = filters.media == option, onClick = { onChange(filters.copy(media = option)) }, label = { Text(groupMediaLabel(option)) })
                }
                FilterChip(selected = filters.favoriteOnly, onClick = { onChange(filters.copy(favoriteOnly = !filters.favoriteOnly)) }, label = { Text("收藏") })
                FilterChip(selected = filters.playlistOnly, onClick = { onChange(filters.copy(playlistOnly = !filters.playlistOnly)) }, label = { Text("在轮播") })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryOrientation.entries.forEach { orientation ->
                    FilterChip(selected = filters.orientation == orientation, onClick = { onChange(filters.copy(orientation = orientation)) }, label = { Text(groupOrientationLabel(orientation)) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filters.groupTag == null && !filters.ungroupedOnly,
                    onClick = { onChange(filters.copy(groupTag = null, ungroupedOnly = false)) },
                    label = { Text("全部分组") },
                )
                FilterChip(
                    selected = filters.ungroupedOnly,
                    onClick = { onChange(filters.copy(groupTag = null, ungroupedOnly = true)) },
                    label = { Text("未分组") },
                )
                groupTags.forEach { tag ->
                    FilterChip(
                        selected = filters.groupTag.equals(tag, ignoreCase = true) && !filters.ungroupedOnly,
                        onClick = { onChange(filters.copy(groupTag = tag, ungroupedOnly = false)) },
                        label = { Text(tag) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupManagerRow(entity: WallpaperEntity, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        color = WallpaperTokens.TextGlass,
        shape = WallpaperTokens.ShapeMd,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        entity.groupTag?.takeIf { it.isNotBlank() }?.let { "组 · $it" } ?: "未分组",
                        if (entity.isFavorite) "收藏" else null,
                        if (entity.inPlaylist) "轮播" else null,
                        entity.width?.let { w -> entity.height?.let { h -> "${w}×${h}" } },
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun groupMediaLabel(filter: LibraryMediaFilter): String = when (filter) {
    LibraryMediaFilter.ALL -> "全部媒体"
    LibraryMediaFilter.IMAGE -> "图片"
    LibraryMediaFilter.VIDEO -> "视频"
}

private fun groupOrientationLabel(filter: LibraryOrientation): String = when (filter) {
    LibraryOrientation.ALL -> "全部方向"
    LibraryOrientation.PORTRAIT -> "竖屏"
    LibraryOrientation.LANDSCAPE -> "横屏"
    LibraryOrientation.SQUARE -> "方形"
}
