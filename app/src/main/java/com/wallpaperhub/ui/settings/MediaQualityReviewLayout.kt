package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import com.wallpaperhub.analysis.QualityFlag
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.ui.designsystem.ReviewMediaTile
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.QualityHygieneState
import com.wallpaperhub.ui.viewmodel.QualityReviewItem
import java.util.Locale

@Composable
internal fun MediaQualityReviewCard(
    item: QualityReviewItem,
    selected: Boolean,
    toggle: () -> Unit,
    dismiss: () -> Unit,
    previewMedia: () -> Unit,
) {
    val context = LocalContext.current
    val analysis = item.analysis
    val reasons = qualityReasons(item)
    Surface(color = WallpaperTokens.TextGlass, shape = WallpaperTokens.ShapeLg, border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ReviewMediaTile(
                entity = item.entity,
                context = context,
                selected = selected,
                current = item.protected,
                onClick = { if (!item.protected) toggle() },
                onLongClick = { if (!item.protected) toggle() },
                onPreview = previewMedia,
                modifier = Modifier.width(108.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "画质分 ${analysis.qualityScore ?: 0} / 100",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val metrics = buildList {
                            analysis.width?.let { w -> analysis.height?.let { h -> add("${w}×${h}") } }
                            analysis.upscaleFactor?.let { add(String.format(Locale.US, "放大 %.2f×", it)) }
                            analysis.frameRate?.takeIf { it > 0f }?.let { add(String.format(Locale.US, "%.0f fps", it)) }
                        }.joinToString(" · ")
                        if (metrics.isNotBlank()) Text(metrics, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!item.protected) Checkbox(checked = selected, onCheckedChange = { toggle() })
                }
                reasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(
                    onClick = previewMedia,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(if (item.entity.type == WallpaperType.VIDEO) "播放查看" else "放大查看")
                }
                Text(
                    if (item.protected) "当前正在使用 · 不允许从这里移除" else "仅作为筛查建议，是否移除由你确认",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.protected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("我判断没问题 · 不再提示")
                }
            }
        }
    }
}

@Composable
internal fun MediaQualityBottomActions(
    state: QualityHygieneState.Ready,
    onClearSelection: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = WallpaperTokens.ShapeLg,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 ${state.selectedIds.size} 项", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            TextButton(
                enabled = state.selectedIds.isNotEmpty(),
                onClick = onClearSelection,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("清空") }
            Button(
                enabled = state.selectedIds.isNotEmpty(),
                onClick = onRemove,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) { Text("移除") }
        }
    }
}

internal fun qualityReasons(item: QualityReviewItem): List<String> {
    val flags = item.analysis.qualityFlags.split(',')
        .mapNotNull { token -> runCatching { QualityFlag.valueOf(token) }.getOrNull() }
        .toSet()
    return buildList {
        val upscale = item.analysis.upscaleFactor
        when {
            QualityFlag.SEVERE_UPSCALE in flags && upscale != null -> add(String.format(Locale.US, "相对当前屏幕需要放大 %.2f×，放大风险明显", upscale))
            QualityFlag.UPSCALE in flags && upscale != null -> add(String.format(Locale.US, "相对当前屏幕需要放大 %.2f×", upscale))
        }
        if (QualityFlag.VERY_LOW_DETAIL in flags) add("代表画面的细节/边缘信号很低，可能明显模糊或本身是低细节素材")
        else if (QualityFlag.LOW_DETAIL in flags) add("代表画面的细节信号偏低，建议肉眼复核")
        if (QualityFlag.VERY_LOW_VIDEO_BITRATE in flags) add("视频码率相对分辨率和帧率非常低，压缩痕迹风险较高")
        else if (QualityFlag.LOW_VIDEO_BITRATE in flags) add("视频码率相对分辨率和帧率偏低")
        if (isEmpty()) add("综合技术指标触发画质复核")
    }
}
