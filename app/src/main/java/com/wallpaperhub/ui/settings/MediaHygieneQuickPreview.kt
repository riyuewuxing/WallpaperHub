package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.ui.designsystem.MediaFitMode
import com.wallpaperhub.ui.designsystem.WallpaperMediaPreview
import com.wallpaperhub.ui.designsystem.WallpaperTokens

@Composable
internal fun MediaHygieneVideoQuickPreview(entity: WallpaperEntity, onDismiss: () -> Unit) {
    val dimensions = entity.width?.let { width -> entity.height?.let { height -> "${width}×${height}" } } ?: "视频"
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = WallpaperTokens.ShapeLg,
            border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("视频快速预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(dimensions, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                    color = Color.Black,
                    shape = WallpaperTokens.ShapeMd,
                ) {
                    WallpaperMediaPreview(
                        entity = entity,
                        modifier = Modifier.fillMaxSize(),
                        fitMode = MediaFitMode.FIT,
                        contentDescription = "视频快速预览",
                        active = true,
                        videoPosterEnabled = false,
                    )
                }
            }
        }
    }
}
