package com.wallpaperhub.ui.designsystem

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.R
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.wallpaper.PrivateFileUriHelper

enum class MediaFitMode { FILL, FIT }
enum class VideoPreviewState { POSTER, LOADING, PLAYING, ERROR }
enum class VideoPreviewEvent { PREPARE, FIRST_FRAME, FAILED, RETRY }

object VideoPreviewStateReducer {
    fun reduce(state: VideoPreviewState, event: VideoPreviewEvent): VideoPreviewState = when (event) {
        VideoPreviewEvent.PREPARE, VideoPreviewEvent.RETRY -> VideoPreviewState.LOADING
        VideoPreviewEvent.FIRST_FRAME -> VideoPreviewState.PLAYING
        VideoPreviewEvent.FAILED -> VideoPreviewState.ERROR
    }
}

/** Renders the real resource; request objects are stable across parent recompositions. */
@Composable
fun WallpaperMediaPreview(
    entity: WallpaperEntity,
    modifier: Modifier = Modifier,
    fitMode: MediaFitMode = MediaFitMode.FILL,
    contentDescription: String = entity.name,
    active: Boolean = true,
    videoPosterEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val safeUri = remember(entity.uri) { PrivateFileUriHelper.safeUri(context, entity.uri) }
    when (entity.type) {
        WallpaperType.STATIC -> {
            val request = remember(safeUri) {
                ImageRequest.Builder(context).data(safeUri).crossfade(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = fitMode.toContentScale(),
                modifier = modifier,
            )
        }
        WallpaperType.VIDEO -> {
            if (active) {
                LoopingVideoPreview(
                    uri = safeUri,
                    fitMode = fitMode,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    posterEnabled = videoPosterEnabled,
                )
            } else {
                Box(
                    modifier = modifier
                        .background(Color.Black)
                        .semantics { this.contentDescription = contentDescription },
                )
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun LoopingVideoPreview(
    uri: Uri,
    fitMode: MediaFitMode,
    contentDescription: String,
    modifier: Modifier,
    posterEnabled: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryGeneration by remember(uri) { mutableIntStateOf(0) }
    var previewState by remember(uri, retryGeneration) { mutableStateOf(VideoPreviewState.POSTER) }
    val previewId = remember(uri, retryGeneration) { WallpaperDebugLog.nextId("appPreview") }
    val posterRequest = remember(uri, posterEnabled) {
        if (posterEnabled) {
            ImageRequest.Builder(context)
                .data(uri)
                .size(720, 1280)
                .decoderFactory(com.wallpaperhub.wallpaper.RobustVideoDecoder.Factory())
                .crossfade(false)
                .build()
        } else {
            null
        }
    }
    val player = remember(uri, retryGeneration) {
        WallpaperDebugLog.i(
            "app_preview",
            "player_create",
            "preview" to previewId,
            "uri" to WallpaperDebugLog.uri(uri),
            "retry" to retryGeneration,
            "audioTrackDisabled" to true,
        )
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                WallpaperDebugLog.i("app_preview", "first_frame", "preview" to previewId, "uri" to WallpaperDebugLog.uri(uri))
                previewState = VideoPreviewStateReducer.reduce(previewState, VideoPreviewEvent.FIRST_FRAME)
            }

            override fun onPlayerError(error: PlaybackException) {
                WallpaperDebugLog.e("app_preview", "player_error", error, "preview" to previewId, "uri" to WallpaperDebugLog.uri(uri), "code" to error.errorCodeName)
                previewState = VideoPreviewStateReducer.reduce(previewState, VideoPreviewEvent.FAILED)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                WallpaperDebugLog.d("app_preview", "playback_state", "preview" to previewId, "state" to playbackStateLabel(playbackState), "playWhenReady" to player.playWhenReady)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                WallpaperDebugLog.d("app_preview", "is_playing", "preview" to previewId, "value" to isPlaying)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            WallpaperDebugLog.d("app_preview", "lifecycle", "preview" to previewId, "eventName" to event.name, "playerReleased" to false)
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> if (previewState != VideoPreviewState.ERROR) player.play()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> player.pause()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        previewState = VideoPreviewStateReducer.reduce(previewState, VideoPreviewEvent.PREPARE)
        WallpaperDebugLog.i("app_preview", "prepare", "preview" to previewId, "uri" to WallpaperDebugLog.uri(uri))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            WallpaperDebugLog.i("app_preview", "release", "preview" to previewId, "uri" to WallpaperDebugLog.uri(uri), "state" to playbackStateLabel(player.playbackState))
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        if (posterRequest != null) {
            AnimatedVisibility(
                visible = previewState != VideoPreviewState.PLAYING,
                enter = fadeIn(animationSpec = tween(90)),
                exit = fadeOut(animationSpec = tween(140)),
            ) {
                AsyncImage(
                    model = posterRequest,
                    contentDescription = null,
                    contentScale = fitMode.toContentScale(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (previewState != VideoPreviewState.PLAYING) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.video_preview_player, null, false) as PlayerView).apply {
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = if (fitMode == MediaFitMode.FILL) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription },
        )
        when (previewState) {
            VideoPreviewState.POSTER, VideoPreviewState.LOADING -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
                Text("正在载入视频预览", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            }
            VideoPreviewState.ERROR -> Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("视频预览暂时无法播放", color = Color.White)
                TextButton(onClick = {
                    previewState = VideoPreviewStateReducer.reduce(previewState, VideoPreviewEvent.RETRY)
                    retryGeneration++
                }) { Text("重试") }
            }
            VideoPreviewState.PLAYING -> Unit
        }
    }
}

private fun MediaFitMode.toContentScale(): ContentScale = if (this == MediaFitMode.FILL) ContentScale.Crop else ContentScale.Fit

private fun playbackStateLabel(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> state.toString()
}
