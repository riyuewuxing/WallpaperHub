package com.wallpaperhub.ui.designsystem

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.wallpaper.PrivateFileUriHelper

@Composable
internal fun CachedWallpaperTileImage(
    entity: WallpaperEntity,
    context: Context,
    modifier: Modifier = Modifier,
) {
    val store = remember(context) { ThumbnailAssetStore.get(context) }
    var model by remember(entity.id, entity.uri, entity.type) { mutableStateOf<Any?>(null) }
    LaunchedEffect(entity.id, entity.uri, entity.type) {
        val cached = store.resolve(entity)
        model = cached ?: if (entity.type == WallpaperType.STATIC) {
            PrivateFileUriHelper.safeUri(context, entity.uri)
        } else {
            null
        }
    }
    val request = model?.let { resolved ->
        remember(resolved) {
            ImageRequest.Builder(context)
                .data(resolved)
                .size(360, 640)
                .crossfade(false)
                .build()
        }
    }
    if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = entity.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else if (entity.type == WallpaperType.VIDEO) {
        Box(
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "视频封面未生成 · 可点左上角播放",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
