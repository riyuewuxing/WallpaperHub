package com.wallpaperhub

import android.app.Application
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.wallpaperhub.data.repo.PlaybackRepository
import com.wallpaperhub.data.repo.WallpaperRepository
import com.wallpaperhub.playback.engine.PlaybackPlanRuntimeCoordinator
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog

/** Global application state and shared media/image infrastructure. */
class WallpaperHubApp : Application(), ImageLoaderFactory {

    val repository: WallpaperRepository by lazy {
        WallpaperRepository.getInstance(this)
    }

    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepository.getInstance(this)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val startedAt = SystemClock.elapsedRealtime()
        WallpaperDebugLog.startMainThreadWatchdog()
        WallpaperDebugLog.i("process", "application_create_start")
        repository
        playbackRepository
        PlaybackPlanRuntimeCoordinator.start(this)
        WallpaperDebugLog.i(
            "process",
            "application_create_complete",
            "elapsedMs" to SystemClock.elapsedRealtime() - startedAt,
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(com.wallpaperhub.wallpaper.RobustVideoDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .crossfade(true)
            .build()
}
