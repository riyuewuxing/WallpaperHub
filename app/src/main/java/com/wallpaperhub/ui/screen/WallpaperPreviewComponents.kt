package com.wallpaperhub.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.preview.model.PreviewApplyState
import com.wallpaperhub.ui.designsystem.WallpaperTokens

@Composable
internal fun PreviewTopBar(
    title: String,
    showOverlay: Boolean,
    onBack: () -> Unit,
    onReset: () -> Unit,
    resetEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onReset, enabled = resetEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.RestartAlt, contentDescription = "重置构图")
        }
        Icon(
            if (showOverlay) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (showOverlay) "参考层已显示" else "纯净预览",
            tint = if (showOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
    }
}

@Composable
internal fun PreviewModeSwitch(mode: String, onModeChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        shape = CircleShape,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("clean" to "纯净", "desktop" to "桌面参考", "lock" to "锁屏参考").forEach { (key, label) ->
                val selected = mode == key
                Surface(
                    onClick = { onModeChange(key) },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 0.dp,
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WallpaperPreviewControls(
    scale: Float,
    scaleMode: MediaScaleMode,
    applyState: PreviewApplyState,
    busy: Boolean,
    onScaleModeChange: (MediaScaleMode) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("显示区域", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (scaleMode == MediaScaleMode.FIT) "整张媒体都会进入桌面"
                        else "拖动选区定位，双指缩放取景",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        if (scaleMode == MediaScaleMode.FIT) "FIT" else "${"%.1f".format(scale)}×",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorModeButton(
                    label = "填充",
                    selected = scaleMode == MediaScaleMode.FILL,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onScaleModeChange(MediaScaleMode.FILL) },
                )
                EditorModeButton(
                    label = "适应",
                    selected = scaleMode == MediaScaleMode.FIT,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onScaleModeChange(MediaScaleMode.FIT) },
                )
            }

            PreviewApplyStatus(applyState)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onSave, enabled = !busy, modifier = Modifier.weight(0.72f)) {
                    Text("仅保存")
                }
                Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1.28f)) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("保存并应用")
                }
            }
        }
    }
}

@Composable
private fun EditorModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        contentColor = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            else WallpaperTokens.StrokeSubtle,
        ),
        tonalElevation = 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PreviewApplyStatus(state: PreviewApplyState) {
    if (state == PreviewApplyState.IDLE) return
    val accent = MaterialTheme.colorScheme.primary
    val (label, color) = when (state) {
        PreviewApplyState.SAVING -> "正在保存构图" to accent
        PreviewApplyState.SAVED -> "构图已保存" to WallpaperTokens.Success
        PreviewApplyState.OPENING_SYSTEM_CONFIRMATION -> "正在打开系统确认页" to accent
        PreviewApplyState.WAITING_FOR_SYSTEM_CONFIRMATION -> "请在系统页面确认设置壁纸" to accent
        PreviewApplyState.VERIFYING_APPLIED_WALLPAPER -> "正在启动新的轮播" to accent
        PreviewApplyState.APPLIED -> "壁纸已应用" to WallpaperTokens.Success
        PreviewApplyState.ERROR -> "应用失败，请重试" to WallpaperTokens.Error
        PreviewApplyState.IDLE -> return
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state in setOf(
                    PreviewApplyState.SAVING,
                    PreviewApplyState.OPENING_SYSTEM_CONFIRMATION,
                    PreviewApplyState.VERIFYING_APPLIED_WALLPAPER,
                )
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
            }
            Text(label, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DeviceReferenceOverlay(mode: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f))) {
        if (mode == "lock") {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("12:48", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.headlineLarge)
                Text("8 月 9 日 · 星期日", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                Surface(
                    color = Color.Black.copy(alpha = 0.26f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    Text(
                        "锁屏参考层",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("12:48", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    repeat(4) { index ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.24f),
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
                Text(
                    "桌面参考层",
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
