package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallpaperhub.analysis.DuplicateKind
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.ui.designsystem.ReviewMediaTile
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.DuplicateReviewGroup
import java.util.Locale

@Composable
internal fun MediaVisualDuplicateGroupCard(
    group: DuplicateReviewGroup,
    selectedIds: Set<String>,
    toggle: (String) -> Unit,
    setKeeper: (String) -> Unit,
    dismissGroup: () -> Unit,
    previewMedia: (WallpaperEntity) -> Unit,
) {
    val title = when (group.kind) {
        DuplicateKind.VISUAL_HIGH -> "高度相似 · ${group.members.size} 项 · ${visualSimilarity(group.similarity)}"
        DuplicateKind.VISUAL_POSSIBLE -> "可能相似 · ${group.members.size} 项 · ${visualSimilarity(group.similarity)}"
        else -> "相似内容 · ${group.members.size} 项"
    }
    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (group.kind == DuplicateKind.VISUAL_POSSIBLE) {
                    "可能相似只做人工对照，不进入批量建议。若肉眼判断不是同一内容，直接驳回即可，系统会记住。左上角：视频播放，图片放大。"
                } else {
                    "高度相似也只是算法建议；你可以保留、删除，或明确判定为不相似。左上角：视频播放，图片放大。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.members.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    pair.forEach { entity ->
                        VisualMember(
                            modifier = Modifier.weight(1f),
                            entity = entity,
                            keeper = entity.id == group.keeperId,
                            protected = entity.id in group.protectedIds,
                            selected = entity.id in selectedIds,
                            keeperLocked = group.protectedIds.isNotEmpty(),
                            possible = group.kind == DuplicateKind.VISUAL_POSSIBLE,
                            toggle = { toggle(entity.id) },
                            setKeeper = { setKeeper(entity.id) },
                            previewMedia = { previewMedia(entity) },
                        )
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = dismissGroup, modifier = Modifier.align(Alignment.End)) {
                Text("不是相似内容 · 不再提示")
            }
        }
    }
}

@Composable
private fun VisualMember(
    modifier: Modifier,
    entity: WallpaperEntity,
    keeper: Boolean,
    protected: Boolean,
    selected: Boolean,
    keeperLocked: Boolean,
    possible: Boolean,
    toggle: () -> Unit,
    setKeeper: () -> Unit,
    previewMedia: () -> Unit,
) {
    val context = LocalContext.current
    val selectable = !keeper && !protected
    val label = when {
        protected -> "当前使用 · 锁定"
        keeper -> if (possible) "对照保留" else "建议保留"
        selected -> "✓ 已选择"
        else -> if (possible) "人工复核" else "可处理"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (keeper || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectable) Checkbox(checked = selected, onCheckedChange = { toggle() })
        }
        ReviewMediaTile(
            entity = entity,
            context = context,
            selected = selected,
            current = protected,
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
            if (selectable) TextButton(enabled = !keeperLocked, onClick = setKeeper) { Text(if (keeperLocked) "当前项必须保留" else "改为保留") }
        }
    }
}

private fun visualSimilarity(value: Double?): String = if (value == null) "—" else String.format(Locale.US, "%.1f%%", value * 100.0)
