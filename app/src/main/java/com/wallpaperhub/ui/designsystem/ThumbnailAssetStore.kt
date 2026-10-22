package com.wallpaperhub.ui.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size as AndroidSize
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.media.MediaSourceStampReader
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.wallpaper.PrivateFileUriHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Small rebuildable disk assets for gallery tiles. Original media stays authoritative. */
internal class ThumbnailAssetStore private constructor(private val appContext: Context) {
    private val generationSlots = Semaphore(2)
    private val generationsSinceTrim = AtomicInteger(0)
    private val legacyCleanupDone = AtomicBoolean(false)
    private val root = File(appContext.cacheDir, "wallpaper_thumbnails_v4")
    private val legacyRoot = File(appContext.cacheDir, "wallpaper_thumbnails_v3")

    suspend fun resolve(entity: WallpaperEntity): File? = withContext(Dispatchers.IO) {
        cleanupLegacyOnce()
        // Internal thumbnail extraction should retain a real file:// URI when one exists. Converting
        // private files to FileProvider content:// is useful for external consumers, but it makes
        // MediaMetadataRetriever depend on a descriptor path even though the real file is available.
        val rawUri = Uri.parse(entity.uri)
        val uri = if (rawUri.scheme == "file") rawUri else PrivateFileUriHelper.safeUri(appContext, rawUri)
        val stamp = MediaSourceStampReader.read(appContext, uri)
        val id = safeId(entity.id)
        val target = File(root, "$id.webp")
        val meta = File(root, "$id.meta")
        val signature = signatureFor(entity, stamp.sizeBytes, stamp.revisionStamp)

        if (isFresh(target, meta, signature)) {
            touch(target)
            return@withContext target
        }

        generationSlots.withPermit {
            if (isFresh(target, meta, signature)) {
                touch(target)
                return@withPermit target
            }

            root.mkdirs()
            val bitmap = when (entity.type) {
                WallpaperType.STATIC -> decodeStatic(uri)
                WallpaperType.VIDEO -> decodeVideo(uri, entity)
            } ?: return@withPermit null

            val tempTarget = File(root, "$id.${System.nanoTime()}.tmp")
            val tempMeta = File(root, "$id.${System.nanoTime()}.meta.tmp")
            try {
                tempTarget.outputStream().buffered().use { output ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    check(bitmap.compress(format, 82, output))
                }
                tempMeta.writeText(signature, Charsets.UTF_8)

                replaceAtomically(tempTarget, target)
                replaceAtomically(tempMeta, meta)
                touch(target)
                maybeTrimCache()
                target.takeIf { it.isFile && it.length() > 0L }
            } catch (_: Exception) {
                tempTarget.delete()
                tempMeta.delete()
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun decodeStatic(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1280) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openInput(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return downscale(decoded, 360, 640)
    }

    private fun decodeVideo(uri: Uri, entity: WallpaperEntity): Bitmap? {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)?.takeIf(File::isFile) ?: return null
            val retrieverFrame = withRetriever { retriever ->
                retriever.setDataSource(file.absolutePath)
                representativeFrame(retriever, entity)
            }
            if (retrieverFrame != null) return retrieverFrame
            return fallbackVideoThumbnail(file)
        }

        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                withRetriever { retriever ->
                    val length = afd.declaredLength
                    if (length >= 0L) {
                        retriever.setDataSource(afd.fileDescriptor, afd.startOffset, length)
                    } else {
                        retriever.setDataSource(afd.fileDescriptor)
                    }
                    representativeFrame(retriever, entity)
                }
            }
        }.getOrNull()
    }

    private inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            block(retriever)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Prefer the first useful visual moment, not an arbitrary middle-of-video frame. */
    private fun representativeFrame(retriever: MediaMetadataRetriever, entity: WallpaperEntity): Bitmap? {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: entity.duration
            ?: 0L
        val durationUs = durationMs.coerceAtLeast(0L) * 1_000L
        val candidatesUs = earliestUsefulCandidates(durationUs)
        candidatesUs.forEach { timeUs ->
            val frame = runCatching {
                if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 360, 640)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?.let { downscale(it, 360, 640) }
                }
            }.getOrNull() ?: return@forEach
            if (!isNearBlack(frame)) return frame
            frame.recycle()
        }

        // Some encoders reject explicit time queries but still expose a generic representative frame.
        val generic = runCatching { retriever.frameAtTime }.getOrNull()
        if (generic != null) {
            if (!isNearBlack(generic)) return downscale(generic, 360, 640)
            generic.recycle()
        }
        return null
    }

    private fun fallbackVideoThumbnail(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val bitmap = runCatching {
            ThumbnailUtils.createVideoThumbnail(
                file,
                AndroidSize(360, 640),
                CancellationSignal(),
            )
        }.getOrNull() ?: return null
        if (isNearBlack(bitmap)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun earliestUsefulCandidates(durationUs: Long): List<Long> {
        val fixed = listOf(0L, 250_000L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 3_000_000L)
        val relative = if (durationUs > 0L) {
            listOf(durationUs / 20L, durationUs / 10L, durationUs / 5L, durationUs / 2L)
        } else {
            emptyList()
        }
        return (fixed + relative)
            .asSequence()
            .filter { it >= 0L && (durationUs <= 0L || it <= durationUs) }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { listOf(0L) }
    }

    /** Reject flat near-black slates while retaining genuinely dark scenes that still contain detail. */
    private fun isNearBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        var dark = 0
        var samples = 0
        var luminanceSum = 0
        var minLuma = 255
        var maxLuma = 0
        for (iy in 1..8) {
            val y = bitmap.height * iy / 9
            for (ix in 1..8) {
                val x = bitmap.width * ix / 9
                val pixel = bitmap.getPixel(x.coerceAtMost(bitmap.width - 1), y.coerceAtMost(bitmap.height - 1))
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

    private fun downscale(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (source.width <= maxWidth && source.height <= maxHeight) return source
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun openInput(uri: Uri): InputStream? = if (uri.scheme == "file") {
        uri.path?.let(::File)?.takeIf(File::isFile)?.let { runCatching { FileInputStream(it) }.getOrNull() }
    } else {
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private fun isFresh(target: File, meta: File, expectedSignature: String): Boolean {
        if (!target.isFile || target.length() <= 0L || !meta.isFile) return false
        val actual = runCatching { meta.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return actual == expectedSignature
    }

    private fun signatureFor(entity: WallpaperEntity, size: Long, revision: Long): String {
        val payload = buildString {
            append(entity.uri)
            append('|').append(size)
            append('|').append(revision)
            append('|').append(entity.type.name)
            append('|').append(entity.width ?: -1)
            append('x').append(entity.height ?: -1)
            append('|').append(entity.duration ?: -1L)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun replaceAtomically(source: File, target: File) {
        if (target.exists() && !target.delete()) error("Unable to replace thumbnail cache file")
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun cleanupLegacyOnce() {
        if (!legacyCleanupDone.compareAndSet(false, true)) return
        runCatching { if (legacyRoot.exists()) legacyRoot.deleteRecursively() }
    }

    private fun maybeTrimCache() {
        if (generationsSinceTrim.incrementAndGet() < TRIM_EVERY_GENERATIONS) return
        generationsSinceTrim.set(0)
        trimCache()
    }

    private fun trimCache() {
        val files = root.listFiles()?.filter { it.isFile && it.extension == "webp" }.orEmpty()
        var totalBytes = files.sumOf(File::length)
        if (files.size <= MAX_CACHE_FILES && totalBytes <= MAX_CACHE_BYTES) return

        var remainingFiles = files.size
        for (file in files.sortedBy(File::lastModified)) {
            if (remainingFiles <= TARGET_CACHE_FILES && totalBytes <= TARGET_CACHE_BYTES) break
            val bytes = file.length()
            if (file.delete()) {
                File(root, "${file.nameWithoutExtension}.meta").delete()
                totalBytes -= bytes
                remainingFiles--
            }
        }
    }

    private fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)

    companion object {
        const val TRIM_EVERY_GENERATIONS = 32
        const val MAX_CACHE_FILES = 8_192
        const val TARGET_CACHE_FILES = 7_500
        const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        const val TARGET_CACHE_BYTES = 224L * 1024L * 1024L

        @Volatile private var instance: ThumbnailAssetStore? = null
        fun get(context: Context): ThumbnailAssetStore = instance ?: synchronized(this) {
            instance ?: ThumbnailAssetStore(context.applicationContext).also { instance = it }
        }
    }
}
