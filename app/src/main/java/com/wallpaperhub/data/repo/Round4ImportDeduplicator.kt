package com.wallpaperhub.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wallpaperhub.analysis.ExactDuplicateScanner
import com.wallpaperhub.analysis.MediaDigest
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.CURRENT_MEDIA_ANALYSIS_VERSION
import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.MediaAnalysisStatus
import com.wallpaperhub.data.db.WallpaperEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class Round4ImportDeduplicator(context: Context) {
    private val appContext = context.applicationContext
    private val analysisDao = AppDatabase.getInstance(appContext).mediaAnalysisDao()
    private val exactScanner = ExactDuplicateScanner(appContext, analysisDao)

    data class Decision(val exactDuplicates: List<WallpaperEntity>)

    data class DuplicateMatch(
        val sourceUri: Uri,
        val sourceName: String,
        val sourceSizeBytes: Long,
        val sha256: String,
        val existingWallpaper: WallpaperEntity? = null,
        val matchedBatchSourceName: String? = null,
    ) {
        val isLibraryMatch: Boolean get() = existingWallpaper != null
    }

    data class Preflight(
        val acceptedUris: List<Uri>,
        val duplicates: List<DuplicateMatch>,
    ) {
        val duplicateCount: Int get() = duplicates.size
        val duplicateNames: List<String> get() = duplicates.map { it.sourceName }
    }

    suspend fun prepareBaseline(repository: WallpaperRepository, beforeIds: Set<String>) = Unit

    suspend fun preflight(repository: WallpaperRepository, uris: List<Uri>): Preflight = withContext(Dispatchers.IO) {
        Round4DuplicateImportAudit.dismiss()
        if (uris.isEmpty()) return@withContext Preflight(emptyList(), emptyList())

        val sourceRows = uris.map { uri ->
            SourceCandidate(
                uri = uri,
                displayName = displayName(uri),
                size = fileSize(uri),
                sha256 = sourceHash(uri),
            )
        }
        val existingRows = repository.allSnapshot().map { entity -> entity to fileSize(Uri.parse(entity.uri)) }
        val existingBySize = existingRows.filter { it.second >= 0L }.groupBy({ it.second }, { it.first })
        val allExisting = existingRows.map { it.first }
        val existingHashCache = mutableMapOf<String, String?>()
        val accepted = mutableListOf<Uri>()
        val duplicates = mutableListOf<DuplicateMatch>()
        val acceptedByHash = linkedMapOf<String, SourceCandidate>()

        sourceRows.forEach { source ->
            val hash = source.sha256
            if (hash.isNullOrBlank()) {
                accepted += source.uri
                return@forEach
            }

            val candidates = if (source.size >= 0L) existingBySize[source.size].orEmpty() else allExisting
            var libraryMatch: WallpaperEntity? = null
            for (candidate in candidates) {
                val candidateHash = if (candidate.id in existingHashCache) {
                    existingHashCache[candidate.id]
                } else {
                    existingHash(candidate).also { existingHashCache[candidate.id] = it }
                }
                if (candidateHash == hash) {
                    libraryMatch = candidate
                    break
                }
            }

            val batchMatch = acceptedByHash[hash]
            when {
                libraryMatch != null -> duplicates += DuplicateMatch(
                    sourceUri = source.uri,
                    sourceName = source.displayName,
                    sourceSizeBytes = source.size,
                    sha256 = hash,
                    existingWallpaper = libraryMatch,
                )
                batchMatch != null -> duplicates += DuplicateMatch(
                    sourceUri = source.uri,
                    sourceName = source.displayName,
                    sourceSizeBytes = source.size,
                    sha256 = hash,
                    matchedBatchSourceName = batchMatch.displayName,
                )
                else -> {
                    accepted += source.uri
                    acceptedByHash[hash] = source
                }
            }
        }
        val result = Preflight(accepted, duplicates)
        Round4DuplicateImportAudit.publish(result.duplicates)
        result
    }

    /**
     * A duplicate inside the same import batch initially points to the first accepted source rather
     * than a library row. After that accepted source is copied, resolve the batch duplicate to the
     * newly-created library entity so the UI can offer the same direct "view original" action.
     */
    suspend fun resolveBatchTargets(
        repository: WallpaperRepository,
        preflight: Preflight,
        beforeIds: Set<String>,
    ): Preflight = withContext(Dispatchers.IO) {
        if (preflight.duplicates.none { it.existingWallpaper == null }) return@withContext preflight
        val wantedHashes = preflight.duplicates
            .asSequence()
            .filter { it.existingWallpaper == null }
            .map { it.sha256 }
            .toSet()
        if (wantedHashes.isEmpty()) return@withContext preflight

        val fresh = repository.allSnapshot().filter { it.id !in beforeIds }
        val targetByHash = linkedMapOf<String, WallpaperEntity>()
        for (entity in fresh) {
            val hash = existingHash(entity) ?: continue
            if (hash in wantedHashes && hash !in targetByHash) targetByHash[hash] = entity
        }
        val resolved = preflight.copy(
            duplicates = preflight.duplicates.map { match ->
                if (match.existingWallpaper != null) match
                else match.copy(existingWallpaper = targetByHash[match.sha256])
            }
        )
        Round4DuplicateImportAudit.publish(resolved.duplicates)
        resolved
    }

    suspend fun inspectNewImports(repository: WallpaperRepository, beforeIds: Set<String>): Decision {
        val after = repository.allSnapshot()
        val newItems = after.filter { it.id !in beforeIds }
        if (newItems.isEmpty()) return Decision(emptyList())
        exactScanner.scan(after)
        val existingHashes = beforeIds.mapNotNull { id -> analysisDao.findByWallpaperId(id)?.sha256 }.filter { it.isNotBlank() }.toHashSet()
        val acceptedNewHashes = hashSetOf<String>()
        val duplicates = newItems.filter { entity ->
            val hash = analysisDao.findByWallpaperId(entity.id)?.sha256?.takeIf { it.isNotBlank() } ?: return@filter false
            hash in existingHashes || !acceptedNewHashes.add(hash)
        }
        return Decision(duplicates)
    }

    private data class SourceCandidate(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val sha256: String?,
    )

    private fun sourceHash(uri: Uri): String? = runCatching { openInput(uri)?.use(MediaDigest::sha256) }.getOrNull()

    private suspend fun existingHash(entity: WallpaperEntity): String? {
        val uri = Uri.parse(entity.uri)
        val size = fileSize(uri)
        val modified = lastModified(uri)
        val existing = analysisDao.findByWallpaperId(entity.id)
        val fresh = existing != null && existing.analysisVersion == CURRENT_MEDIA_ANALYSIS_VERSION && existing.uri == entity.uri &&
            existing.sourceSizeBytes == size && existing.sourceLastModified == modified && !existing.sha256.isNullOrBlank()
        if (fresh) return existing!!.sha256
        val calculated = sourceHash(uri)
        val updated = if (existing != null) {
            existing.copy(
                uri = entity.uri,
                sourceSizeBytes = size,
                sourceLastModified = modified,
                sha256 = calculated,
                status = when {
                    calculated.isNullOrBlank() -> MediaAnalysisStatus.FAILED
                    existing.status == MediaAnalysisStatus.READY -> MediaAnalysisStatus.READY
                    else -> MediaAnalysisStatus.HASHED
                },
                analysisVersion = CURRENT_MEDIA_ANALYSIS_VERSION,
                analyzedAt = System.currentTimeMillis(),
            )
        } else {
            MediaAnalysisEntity(
                wallpaperId = entity.id,
                uri = entity.uri,
                sourceSizeBytes = size,
                sourceLastModified = modified,
                sha256 = calculated,
                width = entity.width,
                height = entity.height,
                durationMs = entity.duration,
                status = if (calculated.isNullOrBlank()) MediaAnalysisStatus.FAILED else MediaAnalysisStatus.HASHED,
                analysisVersion = CURRENT_MEDIA_ANALYSIS_VERSION,
                analyzedAt = System.currentTimeMillis(),
            )
        }
        analysisDao.upsert(updated)
        return calculated
    }

    private fun fileSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf(File::isFile)?.length() ?: -1L
        val descriptorSize = runCatching { appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull()?.takeIf { it >= 0L }
        if (descriptorSize != null) return descriptorSize
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun lastModified(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf(File::exists)?.lastModified() ?: 0L
        return 0L
    }

    private fun openInput(uri: Uri): InputStream? = if (uri.scheme == "file") {
        uri.path?.let(::File)?.takeIf(File::isFile)?.let { runCatching { FileInputStream(it) }.getOrNull() }
    } else {
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let(::File)?.name ?: "重复壁纸"
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "重复壁纸"
    }
}
