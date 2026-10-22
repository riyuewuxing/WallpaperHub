package com.wallpaperhub.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.wallpaperhub.data.model.WallpaperType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Bounded current/next/previous image cache. Video sources remain owned by Media3.
 *
 * The cache is byte-bounded rather than item-count-bounded because a handful of 1440p/4K bitmaps
 * can otherwise dominate native/graphics memory. Concurrent queue rebuilds are also de-duplicated so
 * cancellation races do not decode the same large image twice.
 */
class StaticImagePreloader(
    private val context: Context,
    maxEntries: Int = 3,
) {
    private val maxSizeKb = (maxEntries.coerceAtLeast(1) * BYTES_PER_ENTRY_BUDGET / 1024)
        .coerceAtMost(MAX_CACHE_KB)
    private val inFlight = mutableSetOf<String>()

    private val cache = object : LruCache<String, Bitmap>(maxSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount.coerceAtLeast(value.byteCount) / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // remove()/take() transfers ownership to the caller and must not recycle. Eviction and
            // replacement remain cache-owned and must release their pixel storage immediately.
            if ((evicted || newValue != null) && newValue !== oldValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    suspend fun preloadWindow(entries: List<PreloadEntry>, currentIndex: Int) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val normalized = currentIndex.coerceIn(0, entries.lastIndex)
        val indexes = listOf(
            normalized,
            (normalized - 1 + entries.size) % entries.size,
            (normalized + 1) % entries.size,
        ).distinct()
        indexes.mapNotNull { entries.getOrNull(it) }
            .filter { it.type == WallpaperType.STATIC }
            .forEach { entry ->
                coroutineContext.ensureActive()
                preload(entry.uri, entry.width, entry.height)
            }
    }

    fun get(uri: String): Bitmap? = synchronized(cache) { cache.get(uri) }

    /** Removes without recycling; ownership transfers to the renderer upload caller. */
    fun take(uri: String): Bitmap? = synchronized(cache) { cache.remove(uri) }

    fun clear() {
        synchronized(cache) { cache.evictAll() }
        synchronized(inFlight) { inFlight.clear() }
    }

    private fun preload(uri: String, width: Int?, height: Int?) {
        synchronized(cache) {
            if (cache.get(uri) != null) return
        }
        synchronized(inFlight) {
            if (!inFlight.add(uri)) return
        }

        var decoded: Bitmap? = null
        try {
            val parsed = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return

            val targetWidth = (width ?: DEFAULT_TARGET_WIDTH).coerceIn(1, MAX_TARGET_WIDTH)
            val targetHeight = (height ?: DEFAULT_TARGET_HEIGHT).coerceIn(1, MAX_TARGET_HEIGHT)
            val sample = calculateSample(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            decoded = context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val bitmap = decoded ?: return

            synchronized(cache) {
                if (cache.get(uri) == null) {
                    cache.put(uri, bitmap)
                    decoded = null // cache owns it now
                }
            }
        } finally {
            decoded?.takeIf { !it.isRecycled }?.recycle()
            synchronized(inFlight) { inFlight.remove(uri) }
        }
    }

    private fun calculateSample(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    data class PreloadEntry(
        val uri: String,
        val type: WallpaperType,
        val width: Int? = null,
        val height: Int? = null,
    )

    companion object {
        private const val BYTES_PER_ENTRY_BUDGET = 4 * 1024 * 1024
        private const val MAX_CACHE_KB = 16 * 1024
        private const val DEFAULT_TARGET_WIDTH = 720
        private const val DEFAULT_TARGET_HEIGHT = 1280
        private const val MAX_TARGET_WIDTH = 1440
        private const val MAX_TARGET_HEIGHT = 2560
    }
}
