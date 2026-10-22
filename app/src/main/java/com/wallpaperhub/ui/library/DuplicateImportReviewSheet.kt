package com.wallpaperhub.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.data.repo.Round4ImportDeduplicator
import com.wallpaperhub.ui.designsystem.MediaReviewPreviewButton
import com.wallpaperhub.ui.designsystem.ThumbnailAssetStore
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.ui.settings.MediaHygieneImageQuickPreview
import com.wallpaperhub.ui.settings.MediaHygieneVideoQuickPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateImportReviewSheet(
    matches: List<Round4ImportDeduplicator.DuplicateMatch>,
    onDismiss: () -> Unit,
    onConfirmSkip: (Round4ImportDeduplicator.DuplicateMatch) -> Unit,
    onImportAnyway: (Round4ImportDeduplicator.DuplicateMatch) -> Unit,
) {
    val listState = rememberLazyListState()
    var previewEntity by remember { mutableStateOf<WallpaperEntity?>(null) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "重复导入报告 · ${matches.size} 项待决策",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "两张缩略图并排用于快速判断；每张左上角都可单独查看。视频播放，图片放大。查看或返回不会改变任何决定，只有明确点“确认重复 · 跳过”或“仍然导入”才会处理这一项。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(matches, key = { _, item -> matchKey(item) }) { index, match ->
                    DuplicateMatchCard(
                        ordinal = index + 1,
                        match = match,
                        onPreview = { previewEntity = it },
                        onConfirmSkip = { onConfirmSkip(match) },
                        onImportAnyway = { onImportAnyway(match) },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("稍后处理") }
            }
        }
    }

    previewEntity?.let { entity ->
        if (entity.type == WallpaperType.VIDEO) {
            MediaHygieneVideoQuickPreview(entity = entity, onDismiss = { previewEntity = null })
        } else {
            MediaHygieneImageQuickPreview(entity = entity, onDismiss = { previewEntity = null })
        }
    }
}

@Composable
private fun DuplicateMatchCard(
    ordinal: Int,
    match: Round4ImportDeduplicator.DuplicateMatch,
    onPreview: (WallpaperEntity) -> Unit,
    onConfirmSkip: () -> Unit,
    onImportAnyway: () -> Unit,
) {
    val existing = match.existingWallpaper
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "$ordinal. ${match.sourceName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (existing != null) {
                val sourcePreview = remember(match.sourceUri, match.sourceName, existing) {
                    existing.copy(
                        id = "import-preview-${match.sha256.take(16)}-${match.sourceUri.hashCode()}",
                        uri = match.sourceUri.toString(),
                        name = match.sourceName,
                        isFavorite = false,
                        inPlaylist = false,
                    )
                }
                InlineDuplicatePair(
                    source = sourcePreview,
                    existing = existing,
                    onPreview = onPreview,
                )
                Text(
                    text = buildString {
                        append("SHA-256：${match.sha256.take(12)}… · 字节级完全一致")
                        if (match.sourceSizeBytes >= 0L) {
                            append(" · ${MediaMetadataFormatter.formatBytes(match.sourceSizeBytes)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "点左图只看本次导入，点右图只看图库已有；返回仍停留在当前待决策列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = match.matchedBatchSourceName?.let { "本批次内重复：与「$it」SHA-256 完全一致；匹配项尚未解析为图库记录，因此当前只保留哈希证据，不替你做决定。" }
                        ?: "已确认内容重复，但本次无法解析到图库目标；不会自动删除、自动跳过或替你做决定。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "SHA-256：${match.sha256.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onConfirmSkip) { Text("确认重复 · 跳过") }
                Button(onClick = onImportAnyway) { Text("仍然导入") }
            }
        }
    }
}

@Composable
private fun InlineDuplicatePair(
    source: WallpaperEntity,
    existing: WallpaperEntity,
    onPreview: (WallpaperEntity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        InlineMediaPreview(
            modifier = Modifier.weight(1f),
            label = "本次导入",
            entity = source,
            onPreview = { onPreview(source) },
        )
        InlineMediaPreview(
            modifier = Modifier.weight(1f),
            label = "图库已有",
            entity = existing,
            onPreview = { onPreview(existing) },
        )
    }
}

@Composable
private fun InlineMediaPreview(
    modifier: Modifier,
    label: String,
    entity: WallpaperEntity,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    var thumbnail by remember(entity.id, entity.uri, entity.type) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(entity.id, entity.uri, entity.type) {
        thumbnail = ThumbnailAssetStore.get(context).resolve(entity)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            onClick = onPreview,
            modifier = Modifier.fillMaxWidth().height(190.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val model: Any? = thumbnail ?: if (entity.type == WallpaperType.STATIC) entity.uri else null
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = entity.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = if (entity.type == WallpaperType.VIDEO) "视频封面未生成\n仍可播放查看" else "缩略图生成中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MediaReviewPreviewButton(
                    type = entity.type,
                    onClick = onPreview,
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
                )
            }
        }
        Text(
            text = entity.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun matchKey(match: Round4ImportDeduplicator.DuplicateMatch): String =
    "${match.sha256}:${match.sourceUri}"
