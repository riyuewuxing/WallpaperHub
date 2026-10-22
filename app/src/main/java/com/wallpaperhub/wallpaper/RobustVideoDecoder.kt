package com.wallpaperhub.wallpaper

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.util.Size as AndroidSize
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class RobustVideoDecoder(
    private val source: coil.decode.ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.IO) {
        var rawBitmap: Bitmap? = null
        val file = source.fileOrNull()?.toFile()
        val targetWidth = (options.size.width as? Dimension.Pixels)?.px?.takeIf { it > 0 }
        val targetHeight = (options.size.height as? Dimension.Pixels)?.px?.takeIf { it > 0 }
        var decodeSize: AndroidSize? = null

        if (file != null && file.exists()) {
            val retriever = MediaMetadataRetriever()
            try {
                setRetrieverDataSource(retriever, file)

                val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val sourceWidth = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
                val sourceHeight = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
                decodeSize = decodeSizeFor(sourceWidth, sourceHeight, targetWidth, targetHeight)

                val requestedFrameMicros = options.parameters.value("coil#video_frame_micros") as? Long
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val durationUs = durationMs.coerceAtLeast(0L) * 1_000L
                val candidateTimes = buildCandidateTimes(requestedFrameMicros, durationUs)

                for (timeUs in candidateTimes) {
                    coroutineContext.ensureActive()
                    try {
                        val bmp = decodeFrame(retriever, timeUs, decodeSize)
                        if (bmp != null) {
                            if (!isNearBlackFrame(bmp)) {
                                rawBitmap = bmp
                                break
                            }
                            recycleQuietly(bmp)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get poster frame at $timeUs us", e)
                    }
                }

                if (rawBitmap == null) {
                    coroutineContext.ensureActive()
                    try {
                        val bmp = retriever.frameAtTime
                        if (bmp != null) {
                            if (!isNearBlackFrame(bmp)) rawBitmap = bmp else recycleQuietly(bmp)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get generic poster frame", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever could not extract poster", e)
            } finally {
                runCatching { retriever.release() }
            }
        }

        coroutineContext.ensureActive()
        if (rawBitmap == null && file != null && file.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val fallbackSize = decodeSize
                    ?: if (targetWidth != null && targetHeight != null) AndroidSize(targetWidth, targetHeight)
                    else AndroidSize(FALLBACK_WIDTH, FALLBACK_HEIGHT)
                val bmp = ThumbnailUtils.createVideoThumbnail(
                    file,
                    fallbackSize,
                    CancellationSignal(),
                )
                if (!isNearBlackFrame(bmp)) rawBitmap = bmp else recycleQuietly(bmp)
            } catch (e: Exception) {
                Log.w(TAG, "ThumbnailUtils could not extract poster", e)
            }
        }

        val sourceBitmap = rawBitmap ?: return@withContext null
        val finalBitmap = scaleToCover(sourceBitmap, targetWidth, targetHeight)

        DecodeResult(
            drawable = android.graphics.drawable.BitmapDrawable(options.context.resources, finalBitmap),
            isSampled = targetWidth != null && targetHeight != null,
        )
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, file: File) {
        try {
            retriever.setDataSource(file.absolutePath)
        } catch (pathError: Exception) {
            try {
                FileInputStream(file).use { input -> retriever.setDataSource(input.fd) }
            } catch (fdError: Exception) {
                fdError.addSuppressed(pathError)
                throw fdError
            }
        }
    }

    private fun buildCandidateTimes(requested: Long?, durationUs: Long): List<Long> {
        val fixed = listOf(0L, 250_000L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 3_000_000L)
        val relative = if (durationUs > 0L) listOf(durationUs / 20L, durationUs / 10L, durationUs / 5L, durationUs / 2L) else emptyList()
        val candidates = buildList {
            requested?.takeIf { it >= 0L }?.let(::add)
            addAll(fixed)
            addAll(relative)
        }
        return candidates.asSequence()
            .filter { it >= 0L && (durationUs <= 0L || it <= durationUs) }
            .distinct()
            .toList()
            .ifEmpty { listOf(0L) }
    }

    private fun decodeFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        decodeSize: AndroidSize?,
    ): Bitmap? {
        if (decodeSize != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                decodeSize.width,
                decodeSize.height,
            )
        }
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    private fun decodeSizeFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int?,
        targetHeight: Int?,
    ): AndroidSize? {
        if (targetWidth == null || targetHeight == null) return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return AndroidSize(targetWidth, targetHeight)

        val coverScale = maxOf(
            targetWidth.toFloat() / sourceWidth.toFloat(),
            targetHeight.toFloat() / sourceHeight.toFloat(),
        ).coerceAtMost(1f)
        return AndroidSize(
            (sourceWidth * coverScale).roundToInt().coerceAtLeast(1),
            (sourceHeight * coverScale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun scaleToCover(sourceBitmap: Bitmap, targetWidth: Int?, targetHeight: Int?): Bitmap {
        if (targetWidth == null || targetHeight == null) return sourceBitmap
        if (sourceBitmap.width == targetWidth && sourceBitmap.height == targetHeight) return sourceBitmap

        return try {
            val srcRatio = sourceBitmap.width.toFloat() / sourceBitmap.height.toFloat()
            val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
            val (newWidth, newHeight) = if (srcRatio > targetRatio) {
                val scale = targetHeight.toFloat() / sourceBitmap.height.toFloat()
                Pair((sourceBitmap.width * scale).roundToInt().coerceAtLeast(1), targetHeight)
            } else {
                val scale = targetWidth.toFloat() / sourceBitmap.width.toFloat()
                Pair(targetWidth, (sourceBitmap.height * scale).roundToInt().coerceAtLeast(1))
            }
            if (newWidth == sourceBitmap.width && newHeight == sourceBitmap.height) return sourceBitmap
            val scaled = Bitmap.createScaledBitmap(sourceBitmap, newWidth, newHeight, true)
            if (scaled !== sourceBitmap) recycleQuietly(sourceBitmap)
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scale bitmap proportionally", e)
            sourceBitmap
        }
    }

    private fun isNearBlackFrame(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return true
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true

        var dark = 0
        var samples = 0
        var luminanceSum = 0
        var minLuma = 255
        var maxLuma = 0
        for (iy in 1..8) {
            val y = h * iy / 9
            for (ix in 1..8) {
                val x = w * ix / 9
                val pixel = bitmap.getPixel(x.coerceAtMost(w - 1), y.coerceAtMost(h - 1))
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val luma = (r * 54 + g * 183 + b * 19) shr 8
                if (luma < 22) dark++
                luminanceSum += luma
                minLuma = minOf(minLuma, luma)
                maxLuma = maxOf(maxLuma, luma)
                samples++
            }
        }
        if (samples == 0) return true
        val darkRatio = dark.toFloat() / samples
        val average = luminanceSum.toFloat() / samples
        return darkRatio >= 0.88f && average < 24f && (maxLuma - minLuma) < 42
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Throwable) {
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? =
            RobustVideoDecoder(result.source, options)
    }

    companion object {
        private const val TAG = "RobustVideoDecoder"
        private const val FALLBACK_WIDTH = 1080
        private const val FALLBACK_HEIGHT = 1920
    }
}
