package com.wallpaperhub.analysis

import android.content.Context
import android.net.Uri
import com.wallpaperhub.data.db.CURRENT_MEDIA_ANALYSIS_VERSION
import com.wallpaperhub.data.db.MediaAnalysisDao
import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.MediaAnalysisStatus
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.media.MediaSourceStamp
import com.wallpaperhub.data.media.MediaSourceStampReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class ExactDuplicateGroup(val fingerprint: String, val wallpaperIds: List<String>, val totalBytes: Long)
data class ExactDuplicateScanResult(val groups: List<ExactDuplicateGroup>, val analyzed: Int, val reused: Int, val failed: Int)

class ExactDuplicateScanner(
    private val context: Context,
    private val dao: MediaAnalysisDao,
) {
    suspend fun scan(
        wallpapers: List<WallpaperEntity>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ExactDuplicateScanResult = withContext(Dispatchers.IO) {
        val existingById = dao.allRows().associateBy { it.wallpaperId }
        val stamped = wallpapers.map { wallpaper ->
            val uri = Uri.parse(wallpaper.uri)
            StampedWallpaper(wallpaper, uri, MediaSourceStampReader.read(context, uri))
        }
        val candidates = stamped
            .filter { it.stamp.sizeBytes >= 0L }
            .groupBy { it.stamp.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatten()

        val byFingerprint = linkedMapOf<String, MutableList<Pair<String, Long>>>()
        val pendingWrites = ArrayList<MediaAnalysisEntity>(WRITE_BATCH_SIZE)
        var analyzed = 0
        var reused = 0
        var failed = 0

        suspend fun flushWrites() {
            if (pendingWrites.isEmpty()) return
            dao.upsertAll(pendingWrites.toList())
            pendingWrites.clear()
        }

        candidates.forEachIndexed { index, candidate ->
            currentCoroutineContext().ensureActive()
            val wallpaper = candidate.wallpaper
            val existing = existingById[wallpaper.id]
            val sameSource = existing != null &&
                existing.uri == wallpaper.uri &&
                existing.sourceSizeBytes == candidate.stamp.sizeBytes &&
                existing.sourceLastModified == candidate.stamp.revisionStamp
            val cachedSha = existing?.sha256?.takeIf { sameSource && it.isNotBlank() }

            val fingerprint = if (cachedSha != null) {
                reused++
                cachedSha
            } else {
                val calculated = runCatching {
                    openInput(candidate.uri)?.use(MediaDigest::sha256)
                }.getOrNull()
                val row = exactResultRow(
                    wallpaper = wallpaper,
                    stamp = candidate.stamp,
                    existing = existing?.takeIf { sameSource },
                    calculatedSha = calculated,
                )
                pendingWrites += row
                if (pendingWrites.size >= WRITE_BATCH_SIZE) flushWrites()
                if (calculated.isNullOrBlank()) failed++ else analyzed++
                calculated
            }

            if (!fingerprint.isNullOrBlank()) {
                byFingerprint.getOrPut(fingerprint) { mutableListOf() }
                    .add(wallpaper.id to candidate.stamp.sizeBytes)
            }

            val current = index + 1
            if (current == 1 || current == candidates.size || current % PROGRESS_STEP == 0) {
                onProgress(current, candidates.size)
            }
        }

        flushWrites()
        ExactDuplicateScanResult(
            groups = byFingerprint
                .filterValues { it.size > 1 }
                .map { (fingerprint, members) ->
                    ExactDuplicateGroup(
                        fingerprint = fingerprint,
                        wallpaperIds = members.map { it.first },
                        totalBytes = members.sumOf { it.second.coerceAtLeast(0L) },
                    )
                }
                .sortedByDescending { it.totalBytes },
            analyzed = analyzed,
            reused = reused,
            failed = failed,
        )
    }

    /**
     * Exact hashing must never erase fresh visual/quality signals for the same bytes. Conversely,
     * when source identity changed, old derived fields must not leak into the new content row.
     */
    private fun exactResultRow(
        wallpaper: WallpaperEntity,
        stamp: MediaSourceStamp,
        existing: MediaAnalysisEntity?,
        calculatedSha: String?,
    ): MediaAnalysisEntity {
        if (existing != null) {
            val status = when {
                existing.status == MediaAnalysisStatus.READY -> MediaAnalysisStatus.READY
                calculatedSha.isNullOrBlank() -> MediaAnalysisStatus.FAILED
                else -> MediaAnalysisStatus.HASHED
            }
            return existing.copy(
                sha256 = calculatedSha,
                status = status,
                analyzedAt = System.currentTimeMillis(),
            )
        }

        return MediaAnalysisEntity(
            wallpaperId = wallpaper.id,
            uri = wallpaper.uri,
            sourceSizeBytes = stamp.sizeBytes,
            sourceLastModified = stamp.revisionStamp,
            sha256 = calculatedSha,
            width = wallpaper.width,
            height = wallpaper.height,
            durationMs = wallpaper.duration,
            status = if (calculatedSha.isNullOrBlank()) MediaAnalysisStatus.FAILED else MediaAnalysisStatus.HASHED,
            analysisVersion = CURRENT_MEDIA_ANALYSIS_VERSION,
            analyzedAt = System.currentTimeMillis(),
        )
    }

    private data class StampedWallpaper(
        val wallpaper: WallpaperEntity,
        val uri: Uri,
        val stamp: MediaSourceStamp,
    )

    private fun openInput(uri: Uri): InputStream? = if (uri.scheme == "file") {
        val path = uri.path ?: return null
        runCatching { FileInputStream(File(path)) }.getOrNull()
    } else {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private companion object {
        const val WRITE_BATCH_SIZE = 24
        const val PROGRESS_STEP = 8
    }
}
