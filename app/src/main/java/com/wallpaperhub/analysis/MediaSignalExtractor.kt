package com.wallpaperhub.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.wallpaperhub.data.db.CURRENT_MEDIA_ANALYSIS_VERSION
import com.wallpaperhub.data.db.MediaAnalysisDao
import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.MediaAnalysisStatus
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.media.MediaSourceStampReader
import com.wallpaperhub.data.model.WallpaperType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Builds rebuildable visual/quality signals for Round 4.
 *
 * This class never writes WallpaperEntity, playlist/session state or crop state. It only refreshes
 * media_analysis rows and reuses fresh rows whenever source identity + analysis version match.
 */
class MediaSignalExtractor(
    private val context: Context,
    private val dao: MediaAnalysisDao,
) {
    data class ScanResult(
        val rows: List<MediaAnalysisEntity>,
        val analyzed: Int,
        val reused: Int,
        val failed: Int,
    )

    suspend fun scan(
        wallpapers: List<WallpaperEntity>,
        requireVisualFingerprint: Boolean,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = withContext(Dispatchers.IO) {
        val target = targetPixels()
        val rows = ArrayList<MediaAnalysisEntity>(wallpapers.size)
        val existingById = dao.allRows().associateBy { it.wallpaperId }
        val pendingWrites = ArrayList<MediaAnalysisEntity>(WRITE_BATCH_SIZE)
        var analyzed = 0
        var reused = 0
        var failed = 0

        suspend fun flushWrites() {
            if (pendingWrites.isEmpty()) return
            dao.upsertAll(pendingWrites.toList())
            pendingWrites.clear()
        }

        wallpapers.forEachIndexed { index, wallpaper ->
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(wallpaper.uri)
            val stamp = MediaSourceStampReader.read(context, uri)
            val existing = existingById[wallpaper.id]
            val sameSource = existing != null &&
                existing.uri == wallpaper.uri &&
                existing.sourceSizeBytes == stamp.sizeBytes &&
                existing.sourceLastModified == stamp.revisionStamp
            val fresh = sameSource &&
                existing!!.analysisVersion == CURRENT_MEDIA_ANALYSIS_VERSION &&
                existing.qualityScore != null &&
                existing.status == MediaAnalysisStatus.READY &&
                (!requireVisualFingerprint || existing.visualHashSequence.isNotBlank())

            val row = if (fresh) {
                reused++
                existing
            } else {
                val reusableExisting = existing?.takeIf { sameSource }
                val built = runCatching {
                    buildRow(
                        wallpaper = wallpaper,
                        uri = uri,
                        sourceSizeBytes = stamp.sizeBytes,
                        sourceRevision = stamp.revisionStamp,
                        targetWidth = target.first,
                        targetHeight = target.second,
                        reusableExisting = reusableExisting,
                    )
                }.getOrElse {
                    MediaAnalysisEntity(
                        wallpaperId = wallpaper.id,
                        uri = wallpaper.uri,
                        sourceSizeBytes = stamp.sizeBytes,
                        sourceLastModified = stamp.revisionStamp,
                        sha256 = reusableExisting?.sha256,
                        visualHashSequence = reusableExisting?.visualHashSequence.orEmpty(),
                        width = wallpaper.width,
                        height = wallpaper.height,
                        durationMs = wallpaper.duration,
                        status = MediaAnalysisStatus.FAILED,
                        analysisVersion = CURRENT_MEDIA_ANALYSIS_VERSION,
                        analyzedAt = System.currentTimeMillis(),
                    )
                }
                pendingWrites += built
                if (pendingWrites.size >= WRITE_BATCH_SIZE) flushWrites()
                if (built.status == MediaAnalysisStatus.READY) analyzed++ else failed++
                built
            }
            rows += row

            val current = index + 1
            if (current == 1 || current == wallpapers.size || current % PROGRESS_STEP == 0) {
                onProgress(current, wallpapers.size)
            }
        }

        flushWrites()
        ScanResult(rows = rows, analyzed = analyzed, reused = reused, failed = failed)
    }

    private fun buildRow(
        wallpaper: WallpaperEntity,
        uri: Uri,
        sourceSizeBytes: Long,
        sourceRevision: Long,
        targetWidth: Int,
        targetHeight: Int,
        reusableExisting: MediaAnalysisEntity?,
    ): MediaAnalysisEntity {
        val extracted = when (wallpaper.type) {
            WallpaperType.STATIC -> analyzeStatic(uri, wallpaper)
            WallpaperType.VIDEO -> analyzeVideo(uri, wallpaper)
        }
        val width = extracted.width ?: wallpaper.width
        val height = extracted.height ?: wallpaper.height
        val duration = extracted.durationMs ?: wallpaper.duration
        val qualityFps = extracted.frameRate
            ?: extracted.bitrateBitsPerSecond?.takeIf { it > 0L }?.let { DEFAULT_VIDEO_FPS }
        val assessment = MediaQualityPolicy.assess(
            MediaQualityInput(
                width = width,
                height = height,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                detailScore = extracted.detailScore,
                bitrateBitsPerSecond = extracted.bitrateBitsPerSecond,
                frameRate = qualityFps,
            )
        )
        val flags = assessment.flags.joinToString(",") { it.name }
        val visual = if (extracted.hashes.isNotEmpty()) {
            MediaDigest.encodeHashSequence(extracted.hashes)
        } else {
            reusableExisting?.visualHashSequence.orEmpty()
        }
        val usable = assessment.upscaleFactor != null ||
            extracted.detailScore != null ||
            extracted.bitrateBitsPerSecond != null

        return MediaAnalysisEntity(
            wallpaperId = wallpaper.id,
            uri = wallpaper.uri,
            sourceSizeBytes = sourceSizeBytes,
            sourceLastModified = sourceRevision,
            sha256 = reusableExisting?.sha256,
            visualHashSequence = visual,
            width = width,
            height = height,
            durationMs = duration,
            bitrateBitsPerSecond = extracted.bitrateBitsPerSecond,
            frameRate = extracted.frameRate,
            detailScore = extracted.detailScore,
            upscaleFactor = assessment.upscaleFactor,
            qualityScore = if (usable) assessment.score else null,
            qualityFlags = flags,
            status = if (usable) MediaAnalysisStatus.READY else MediaAnalysisStatus.FAILED,
            analysisVersion = CURRENT_MEDIA_ANALYSIS_VERSION,
            analyzedAt = System.currentTimeMillis(),
        )
    }

    private data class ExtractedSignals(
        val hashes: List<Long> = emptyList(),
        val detailScore: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val bitrateBitsPerSecond: Long? = null,
        val frameRate: Float? = null,
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private fun analyzeStatic(uri: Uri, wallpaper: WallpaperEntity): ExtractedSignals {
        val decoded = decodeSampledBitmap(uri, STATIC_SAMPLE_MAX_DIMENSION)
            ?: return ExtractedSignals(width = wallpaper.width, height = wallpaper.height)
        return try {
            val signals = signalsForBitmap(decoded.bitmap)
            ExtractedSignals(
                hashes = listOf(signals.hash),
                detailScore = signals.sharpness,
                // Prefer import metadata when available (it may already account for orientation),
                // otherwise use the original decode bounds, never the sampled bitmap dimensions.
                width = wallpaper.width ?: decoded.sourceWidth,
                height = wallpaper.height ?: decoded.sourceHeight,
            )
        } finally {
            decoded.bitmap.recycle()
        }
    }

    private fun analyzeVideo(uri: Uri, wallpaper: WallpaperEntity): ExtractedSignals {
        val retriever = MediaMetadataRetriever()
        try {
            setRetrieverSource(retriever, uri)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: wallpaper.width
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: wallpaper.height
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if ((rotation == 90 || rotation == 270) && width != null && height != null) {
                val swap = width
                width = height
                height = swap
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: wallpaper.duration
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val fps = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            }.getOrNull()?.takeIf { it > 0f }
            val positions = if (duration != null && duration > 0L) {
                VIDEO_SAMPLE_FRACTIONS.map { fraction -> (duration * 1_000.0 * fraction).toLong() }
            } else {
                listOf(0L)
            }
            val hashes = mutableListOf<Long>()
            val sharpness = mutableListOf<Double>()
            positions.forEach { timeUs ->
                val frame = frameAt(retriever, timeUs)
                if (frame != null) {
                    try {
                        val signals = signalsForBitmap(frame)
                        hashes += signals.hash
                        signals.sharpness?.let(sharpness::add)
                    } finally {
                        frame.recycle()
                    }
                }
            }
            return ExtractedSignals(
                hashes = hashes,
                detailScore = sharpness.takeIf { it.isNotEmpty() }?.average(),
                width = width,
                height = height,
                durationMs = duration,
                bitrateBitsPerSecond = bitrate,
                frameRate = fps,
            )
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                VIDEO_SAMPLE_WIDTH,
                VIDEO_SAMPLE_HEIGHT,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { downscale(it, VIDEO_SAMPLE_WIDTH, VIDEO_SAMPLE_HEIGHT) }
        }
    }.getOrNull()

    private fun setRetrieverSource(retriever: MediaMetadataRetriever, uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Missing file path")
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, uri)
        }
    }

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): DecodedBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openInput(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return DecodedBitmap(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private data class BitmapSignals(
        val hash: Long,
        val sharpness: Double?,
    )

    private fun signalsForBitmap(source: Bitmap): BitmapSignals {
        val hashBitmap = Bitmap.createScaledBitmap(source, 9, 8, true)
        val hash = try {
            val pixels = IntArray(9 * 8)
            hashBitmap.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            var value = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(pixels[y * 9 + x]) > luma(pixels[y * 9 + x + 1])) value = value or (1L shl bit)
                    bit++
                }
            }
            value
        } finally {
            if (hashBitmap !== source) hashBitmap.recycle()
        }

        val detailBitmap = Bitmap.createScaledBitmap(source, SHARPNESS_SAMPLE_SIZE, SHARPNESS_SAMPLE_SIZE, true)
        val sharpness = try {
            val pixels = IntArray(SHARPNESS_SAMPLE_SIZE * SHARPNESS_SAMPLE_SIZE)
            detailBitmap.getPixels(
                pixels,
                0,
                SHARPNESS_SAMPLE_SIZE,
                0,
                0,
                SHARPNESS_SAMPLE_SIZE,
                SHARPNESS_SAMPLE_SIZE,
            )
            val luma = IntArray(pixels.size) { index -> luma(pixels[index]) }
            MediaSharpnessEstimator.estimate(luma, SHARPNESS_SAMPLE_SIZE, SHARPNESS_SAMPLE_SIZE)
        } finally {
            if (detailBitmap !== source) detailBitmap.recycle()
        }
        return BitmapSignals(hash = hash, sharpness = sharpness)
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    private fun downscale(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (source.width <= maxWidth && source.height <= maxHeight) return source
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun openInput(uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            runCatching { FileInputStream(File(path)) }.getOrNull()
        } else {
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        }
    }

    private fun targetPixels(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        return shortSide to longSide
    }

    private companion object {
        const val WRITE_BATCH_SIZE = 24
        const val PROGRESS_STEP = 8
        const val STATIC_SAMPLE_MAX_DIMENSION = 384
        const val SHARPNESS_SAMPLE_SIZE = 96
        const val VIDEO_SAMPLE_WIDTH = 216
        const val VIDEO_SAMPLE_HEIGHT = 384
        const val DEFAULT_VIDEO_FPS = 30f
        val VIDEO_SAMPLE_FRACTIONS = listOf(0.20, 0.50, 0.80)
    }
}
