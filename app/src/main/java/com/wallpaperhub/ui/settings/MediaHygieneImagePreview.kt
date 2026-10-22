package com.wallpaperhub.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.wallpaper.PrivateFileUriHelper

@Composable
internal fun MediaHygieneImageQuickPreview(entity: WallpaperEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val safeUri = remember(entity.uri) { PrivateFileUriHelper.safeUri(context, entity.uri) }
    val request = remember(safeUri) { ImageRequest.Builder(context).data(safeUri).crossfade(false).build() }
    var scale by remember(entity.id) { mutableFloatStateOf(1f) }
    var offset by remember(entity.id) { mutableStateOf(Offset.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.82f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("放大查看", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            entity.width?.let { w -> entity.height?.let { h -> "${w}×${h} · 双指缩放/拖动" } } ?: "双指缩放/拖动",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (scale > 1.01f || offset != Offset.Zero) {
                        TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("复位", color = Color.White) }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                }
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(entity.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = entity.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    )
                }
            }
        }
    }
}
