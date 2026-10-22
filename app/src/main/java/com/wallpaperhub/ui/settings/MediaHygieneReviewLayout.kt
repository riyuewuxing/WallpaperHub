package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.ui.designsystem.ReviewMediaTile
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.ui.viewmodel.DuplicateReviewGroup
import com.wallpaperhub.ui.viewmodel.MediaHygieneState

@Composable
internal fun MediaHygieneDuplicateGroupCard(
    group: DuplicateReviewGroup,
    selectedIds: Set<String>,
    toggle: (String) -> Unit,
    setKeeper: (String) -> Unit,
    dismissGroup: () -> Unit,
    previewMedia: (WallpaperEntity) -> Unit,
) {
    val perFile = group.totalBytes / group.members.size.coerceAtLeast(1)
    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "完全重复 · ${group.members.size} 项 · 每项约 ${MediaMetadataFormatter.formatBytes(perFile)}",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "SHA-256 完全一致仍只作为清理建议。若你有意保留多份，可记录这个决定；这组已复核关系以后不再提示，新出现的副本仍会重新询问。左上角：视频播放，图片放大；查看不会改变选择。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.members.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    pair.forEach { entity ->
                        MemberCard(
                            modifier = Modifier.weight(1f),
                            entity = entity,
                            keeper = entity.id == group.keeperId,
                            protected = entity.id in group.protectedIds,
                            live = entity.id in group.liveIds,
                            selected = entity.id in selectedIds,
                            keeperLocked = group.protectedIds.isNotEmpty(),
                            toggle = { toggle(entity.id) },
                            setKeeper = { setKeeper(entity.id) },
                            previewMedia = { previewMedia(entity) },
                        )
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = dismissGroup, modifier = Modifier.align(Alignment.End)) {
                Text("我决定都保留 · 不再提示")
            }
        }
    }
}

@Composable
private fun MemberCard(
    modifier: Modifier,
    entity: WallpaperEntity,
    keeper: Boolean,
    protected: Boolean,
    live: Boolean,
    selected: Boolean,
    keeperLocked: Boolean,
    toggle: () -> Unit,
    setKeeper: () -> Unit,
    previewMedia: () -> Unit,
) {
    val context = LocalContext.current
    val selectable = !keeper && !protected
    val label = when {
        live -> "当前使用 · 锁定"
        protected -> "受保护 · 不批量清理"
        keeper -> "建议保留"
        selected -> "✓ 已选清理"
        else -> "可清理"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (keeper || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectable) Checkbox(checked = selected, onCheckedChange = { toggle() })
        }
        ReviewMediaTile(
            entity = entity,
            context = context,
            selected = selected,
            current = live,
            onClick = { if (selectable) toggle() },
            onLongClick = { if (selectable) toggle() },
            onPreview = previewMedia,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            listOfNotNull(
                entity.width?.let { w -> entity.height?.let { h -> "${w}×${h}" } },
                if (entity.inPlaylist) "轮播" else null,
                if (entity.isFavorite) "收藏" else null,
                if (entity.cropRevision > 0L) "有构图" else null,
            ).joinToString(" · ").ifBlank { "无额外状态" },
            modifier = Modifier.fillMaxWidth().height(36.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            if (selectable) TextButton(enabled = !keeperLocked, onClick = setKeeper) { Text(if (keeperLocked) "受保护项必须保留" else "改为保留") }
        }
    }
}

@Composable
internal fun MediaHygieneBottomActions(
    state: MediaHygieneState.Ready,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bytes = remember(state.groups, state.selectedIds) {
        state.groups.sumOf { group ->
            val each = group.totalBytes / group.members.size.coerceAtLeast(1)
            group.members.count { it.id in state.selectedIds } * each
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = WallpaperTokens.ShapeLg,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "已选 ${state.selectedIds.size} · ${MediaMetadataFormatter.formatBytes(bytes)}",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            TextButton(
                enabled = state.selectedIds.isNotEmpty(),
                onClick = onClearSelection,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text("清空")
            }
            OutlinedButton(onClick = onSelectAll, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("全选可清理")
            }
            Button(enabled = state.selectedIds.isNotEmpty(), onClick = onRemove, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("移除")
            }
        }
    }
}
