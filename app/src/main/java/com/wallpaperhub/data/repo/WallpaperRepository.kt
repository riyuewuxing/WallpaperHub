package com.wallpaperhub.data.repo

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.WallpaperDao
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.db.FolderPermissionEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.playback.engine.PlaybackOrderCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 壁纸数据仓库：UI 层只跟它打交道，不直接接触数据库。
 *
 * Library storage and playlist planning are deliberately separated. Importing a resource changes
 * only the library. Every playlist membership/order mutation is delegated to PlaybackOrderCoordinator
 * so gallery presentation, canonical order and runtime traversal cannot silently overwrite each other.
 */
class WallpaperRepository(
    private val context: Context,
    private val dao: WallpaperDao
) {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        repositoryScope.launch {
            cleanupOrphanedUriPermissions()
            try {
                migrateInternalToExternal()
                PlaybackOrderCoordinator.reconcile(context)
            } catch (e: Exception) {
                android.util.Log.e("WallpaperRepository", "Failed to run internal to external migration", e)
            }
        }
    }

    fun observeAll(): Flow<List<WallpaperEntity>> = dao.observeAll()

    fun observeByType(type: WallpaperType): Flow<List<WallpaperEntity>> =
        dao.observeByType(type)

    fun observeFavorites(): Flow<List<WallpaperEntity>> = dao.observeFavorites()

    fun observePlaylist(): Flow<List<WallpaperEntity>> = dao.observePlaylist()

    suspend fun playlistSnapshot(): List<WallpaperEntity> = dao.playlistSnapshot()

    suspend fun allSnapshot(): List<WallpaperEntity> = dao.allSnapshot()

    suspend fun findById(id: String): WallpaperEntity? = dao.findById(id)

    suspend fun resolveFileSize(uriString: String): Long? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.length()
            "content" -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn).takeIf { it >= 0L }
                    } else {
                        null
                    }
                }
            }.getOrNull()
            else -> null
        }
    }

    suspend fun updateCropTransform(
        entity: WallpaperEntity,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        scaleMode: MediaScaleMode,
    ) = withContext(Dispatchers.IO) {
        dao.update(
            entity.copy(
                cropScale = scale,
                cropOffsetX = offsetX,
                cropOffsetY = offsetY,
                cropScaleMode = scaleMode.persistedValue,
                cropRevision = entity.cropRevision + 1L,
            )
        )
    }

    /** Repair/publish the current plan after storage-level URI or file reconciliation. */
    suspend fun syncPlaylistState(currentUri: String? = null) {
        if (currentUri != null) {
            val selected = dao.findByUri(currentUri)
            if (selected != null) {
                PlaybackOrderCoordinator.restartFrom(context, selected)
                return
            }
        }
        PlaybackOrderCoordinator.reconcile(context)
    }

    /** Compatibility wrapper. Traversal semantics are owned by the persisted playback mode. */
    suspend fun restartPlaylistFrom(
        selected: WallpaperEntity,
        @Suppress("UNUSED_PARAMETER") shuffle: Boolean,
    ): List<WallpaperEntity> = withContext(Dispatchers.IO) {
        PlaybackOrderCoordinator.restartFrom(context, selected)
        dao.playlistSnapshot()
    }

    /**
     * 导入一组用户选中的资源（图片或视频）。
     * Import changes Library only; new items are NOT implicitly inserted into a running carousel.
     */
    suspend fun importFromUris(
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = importFromUrisCopied(uris, onProgress)

    suspend fun importFromTreeUri(
        treeUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = importFromTreeUriCopied(treeUri, onProgress)

    suspend fun importFromUrisCopied(
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImportResult(0, 0, 0)
        var addedImage = 0
        var addedVideo = 0
        var skipped = 0
        val total = uris.size
        var processed = 0

        val chunks = uris.chunked(50)
        val db = AppDatabase.getInstance(context)

        for (chunk in chunks) {
            val entitiesToInsert = mutableListOf<WallpaperEntity>()

            for (uri in chunk) {
                val mime = getSafeMimeType(uri)
                val type = typeFromMime(mime)
                val name = queryDisplayName(uri) ?: "未命名"
                val copiedUri = copyToLocalImportDir(uri, name, mime)
                if (copiedUri == null) {
                    skipped++
                    continue
                }
                val meta = when (type) {
                    WallpaperType.VIDEO -> extractVideoMeta(copiedUri)
                    WallpaperType.STATIC -> extractImageMeta(copiedUri)
                }
                entitiesToInsert.add(
                    WallpaperEntity(
                        id = UUID.randomUUID().toString(),
                        uri = copiedUri.toString(),
                        type = type,
                        name = name,
                        addedAt = System.currentTimeMillis(),
                        width = meta.first,
                        height = meta.second,
                        duration = meta.third
                    )
                )
            }

            if (entitiesToInsert.isNotEmpty()) {
                db.withTransaction {
                    val insertedIds = dao.insertAll(entitiesToInsert)
                    insertedIds.forEachIndexed { index, id ->
                        if (id != -1L) {
                            val entity = entitiesToInsert[index]
                            if (entity.type == WallpaperType.VIDEO) addedVideo++ else addedImage++
                        } else {
                            skipped++
                        }
                    }
                }
            }

            processed += chunk.size
            onProgress(minOf(processed, total), total)
        }

        ImportResult(addedImage, addedVideo, skipped)
    }

    suspend fun importFromTreeUriCopied(
        treeUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        val mediaFiles = collectTreeMediaFiles(treeUri)
        if (mediaFiles.isEmpty()) return@withContext ImportResult(0, 0, 0)

        var addedImage = 0
        var addedVideo = 0
        var skipped = 0
        val total = mediaFiles.size
        var processed = 0

        val chunks = mediaFiles.chunked(50)
        val db = AppDatabase.getInstance(context)

        for (chunk in chunks) {
            val entitiesToInsert = mutableListOf<WallpaperEntity>()

            for (file in chunk) {
                val uri = file.uri
                val mime = file.type
                val type = typeFromMime(mime)
                val name = file.name.ifBlank { queryDisplayName(uri) ?: "未命名" }
                val copiedUri = copyToLocalImportDir(uri, name, mime)
                if (copiedUri == null) {
                    skipped++
                    continue
                }
                val meta = when (type) {
                    WallpaperType.VIDEO -> extractVideoMeta(copiedUri)
                    WallpaperType.STATIC -> extractImageMeta(copiedUri)
                }
                entitiesToInsert.add(
                    WallpaperEntity(
                        id = UUID.randomUUID().toString(),
                        uri = copiedUri.toString(),
                        type = type,
                        name = name,
                        addedAt = System.currentTimeMillis(),
                        width = meta.first,
                        height = meta.second,
                        duration = meta.third
                    )
                )
            }

            if (entitiesToInsert.isNotEmpty()) {
                db.withTransaction {
                    val insertedIds = dao.insertAll(entitiesToInsert)
                    insertedIds.forEachIndexed { index, id ->
                        if (id != -1L) {
                            val entity = entitiesToInsert[index]
                            if (entity.type == WallpaperType.VIDEO) addedVideo++ else addedImage++
                        } else {
                            skipped++
                        }
                    }
                }
            }

            processed += chunk.size
            onProgress(minOf(processed, total), total)
        }

        ImportResult(addedImage, addedVideo, skipped)
    }

    /** 导入结果 */
    data class ImportResult(
        val addedImage: Int,
        val addedVideo: Int,
        val skipped: Int
    ) {
        val total: Int get() = addedImage + addedVideo
    }

    data class LocalCopyStats(
        val directoryPath: String,
        val fileCount: Int,
        val totalBytes: Long
    )

    data class LocalDirSyncResult(
        val addedImage: Int,
        val addedVideo: Int,
        val removedMissing: Int,
        val skipped: Int
    ) {
        val addedTotal: Int get() = addedImage + addedVideo
    }

    suspend fun syncLocalImportDir(): LocalDirSyncResult = withContext(Dispatchers.IO) {
        val root = ensureLocalImportDir()
        val localFiles = collectLocalImportMediaFiles(root)
        val localPaths = localFiles.mapNotNull { file ->
            try {
                file.canonicalPath
            } catch (_: Exception) {
                null
            }
        }.toSet()

        val all = dao.allSnapshot()
        val missingLocalRecords = all.filter { entity ->
            val uri = Uri.parse(entity.uri)
            if (uri.scheme != "file") return@filter false
            val path = uri.path ?: return@filter false
            try {
                val file = File(path).canonicalFile
                val localRoot = root.canonicalFile
                file.path.startsWith(localRoot.path) && !file.exists()
            } catch (_: Exception) {
                false
            }
        }

        var addedImage = 0
        var addedVideo = 0
        var skipped = 0
        val existingUris = all.map { it.uri }.toSet()
        val entitiesToInsert = mutableListOf<WallpaperEntity>()

        localFiles.forEach { file ->
            val uri = Uri.fromFile(file)
            if (uri.toString() in existingUris || file.canonicalPath !in localPaths) {
                skipped++
                return@forEach
            }
            val type = typeFromFile(file)
            val meta = when (type) {
                WallpaperType.VIDEO -> extractVideoMeta(uri)
                WallpaperType.STATIC -> extractImageMeta(uri)
            }
            entitiesToInsert.add(
                WallpaperEntity(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    type = type,
                    name = file.name,
                    addedAt = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                    width = meta.first,
                    height = meta.second,
                    duration = meta.third
                )
            )
        }

        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            if (missingLocalRecords.isNotEmpty()) {
                dao.deleteByIds(missingLocalRecords.map { it.id })
            }

            if (entitiesToInsert.isNotEmpty()) {
                val insertedIds = dao.insertAll(entitiesToInsert)
                insertedIds.forEachIndexed { index, id ->
                    if (id != -1L) {
                        val entity = entitiesToInsert[index]
                        if (entity.type == WallpaperType.VIDEO) addedVideo++ else addedImage++
                    } else {
                        skipped++
                    }
                }
            }
        }

        if (missingLocalRecords.any { it.inPlaylist }) {
            PlaybackOrderCoordinator.reconcile(context)
        }

        LocalDirSyncResult(
            addedImage = addedImage,
            addedVideo = addedVideo,
            removedMissing = missingLocalRecords.size,
            skipped = skipped
        )
    }

    data class MediaAlbum(
        val bucketId: String,
        val name: String,
        val imageCount: Int,
        val videoCount: Int,
        val latestAdded: Long
    ) {
        val totalCount: Int get() = imageCount + videoCount
    }

    suspend fun mediaAlbums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val albums = linkedMapOf<String, MutableMediaAlbum>()
        queryAlbumCollection(
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            bucketIdColumn = MediaStore.Images.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            dateAddedColumn = MediaStore.Images.Media.DATE_ADDED,
            isVideo = false,
            output = albums
        )
        queryAlbumCollection(
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            bucketIdColumn = MediaStore.Video.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            dateAddedColumn = MediaStore.Video.Media.DATE_ADDED,
            isVideo = true,
            output = albums
        )
        albums.values
            .map {
                MediaAlbum(
                    bucketId = it.bucketId,
                    name = it.name,
                    imageCount = it.imageCount,
                    videoCount = it.videoCount,
                    latestAdded = it.latestAdded
                )
            }
            .sortedByDescending { it.latestAdded }
    }

    suspend fun importAlbum(
        album: MediaAlbum,
        copyToLocal: Boolean,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult {
        val uris = mediaUrisInAlbum(album.bucketId)
        return importFromUrisCopied(uris, onProgress)
    }

    private data class MutableMediaAlbum(
        val bucketId: String,
        var name: String,
        var imageCount: Int = 0,
        var videoCount: Int = 0,
        var latestAdded: Long = 0L
    )

    private fun queryAlbumCollection(
        collectionUri: Uri,
        idColumn: String,
        bucketIdColumn: String,
        bucketNameColumn: String,
        dateAddedColumn: String,
        isVideo: Boolean,
        output: MutableMap<String, MutableMediaAlbum>
    ) {
        val projection = arrayOf(idColumn, bucketIdColumn, bucketNameColumn, dateAddedColumn)
        try {
            context.contentResolver.query(
                collectionUri,
                projection,
                null,
                null,
                "$dateAddedColumn DESC"
            )?.use { cursor ->
                val bucketIdIndex = cursor.getColumnIndexOrThrow(bucketIdColumn)
                val bucketNameIndex = cursor.getColumnIndexOrThrow(bucketNameColumn)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(dateAddedColumn)
                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(bucketIdIndex) ?: continue
                    val bucketName = cursor.getString(bucketNameIndex) ?: "未命名相册"
                    val dateAdded = cursor.getLong(dateAddedIndex)
                    val album = output.getOrPut(bucketId) {
                        MutableMediaAlbum(bucketId = bucketId, name = bucketName)
                    }
                    album.name = bucketName
                    album.latestAdded = maxOf(album.latestAdded, dateAdded)
                    if (isVideo) album.videoCount++ else album.imageCount++
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private suspend fun mediaUrisInAlbum(bucketId: String): List<Uri> = withContext(Dispatchers.IO) {
        buildList {
            addMediaUrisInAlbum(
                collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                idColumn = MediaStore.Images.Media._ID,
                bucketIdColumn = MediaStore.Images.Media.BUCKET_ID,
                bucketId = bucketId
            )
            addMediaUrisInAlbum(
                collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                idColumn = MediaStore.Video.Media._ID,
                bucketIdColumn = MediaStore.Video.Media.BUCKET_ID,
                bucketId = bucketId
            )
        }
    }

    private fun MutableList<Uri>.addMediaUrisInAlbum(
        collectionUri: Uri,
        idColumn: String,
        bucketIdColumn: String,
        bucketId: String
    ) {
        val projection = arrayOf(idColumn)
        try {
            context.contentResolver.query(
                collectionUri,
                projection,
                "$bucketIdColumn = ?",
                arrayOf(bucketId),
                "$idColumn DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                while (cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(collectionUri, cursor.getLong(idIndex)))
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private data class TreeMediaFile(
        val uri: Uri,
        val type: String,
        val name: String
    )

    private fun collectTreeMediaFiles(treeUri: Uri): List<TreeMediaFile> {
        val results = mutableListOf<TreeMediaFile>()
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return results
        }
        val queue = java.util.ArrayDeque<String>()
        queue.add(documentId)

        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        while (!queue.isEmpty()) {
            val parentId = queue.poll()
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            } catch (e: Exception) {
                continue
            }
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val childId = if (idIdx >= 0) cursor.getString(idIdx) else null ?: continue
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "未命名"

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(childId)
                        } else if (mime.startsWith("image/") || mime.startsWith("video/")) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            results.add(TreeMediaFile(childUri, mime, name))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WallpaperRepository", "Error querying child documents for parent: $parentId", e)
            }
        }
        return results
    }

    private suspend fun importFromTreeFiles(
        files: List<TreeMediaFile>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext ImportResult(0, 0, 0)
        var addedImage = 0
        var addedVideo = 0
        var skipped = 0

        val existingUris = dao.allUris().toSet()
        val total = files.size
        var processed = 0

        val chunks = files.chunked(50)
        val db = AppDatabase.getInstance(context)

        for (chunk in chunks) {
            val entitiesToInsert = mutableListOf<WallpaperEntity>()

            for (file in chunk) {
                val uri = file.uri
                val uriStr = uri.toString()
                if (uriStr in existingUris) {
                    skipped++
                    continue
                }
                if (!canRead(uri)) {
                    skipped++
                    continue
                }
                val mime = file.type
                val type = typeFromMime(mime)
                val meta = when (type) {
                    WallpaperType.VIDEO -> extractVideoMeta(uri)
                    WallpaperType.STATIC -> extractImageMeta(uri)
                }
                entitiesToInsert.add(
                    WallpaperEntity(
                        id = UUID.randomUUID().toString(),
                        uri = uriStr,
                        type = type,
                        name = file.name.ifBlank { queryDisplayName(uri) ?: "未命名" },
                        addedAt = System.currentTimeMillis(),
                        width = meta.first,
                        height = meta.second,
                        duration = meta.third
                    )
                )
            }

            if (entitiesToInsert.isNotEmpty()) {
                db.withTransaction {
                    val insertedIds = dao.insertAll(entitiesToInsert)
                    insertedIds.forEachIndexed { index, id ->
                        if (id != -1L) {
                            val entity = entitiesToInsert[index]
                            if (entity.type == WallpaperType.VIDEO) addedVideo++ else addedImage++
                        } else {
                            skipped++
                        }
                    }
                }
            }

            processed += chunk.size
            onProgress(minOf(processed, total), total)
        }

        ImportResult(addedImage, addedVideo, skipped)
    }

    private fun canRead(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { true } == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun togglePlaylist(entity: WallpaperEntity) = withContext(Dispatchers.IO) {
        val fresh = dao.findById(entity.id) ?: entity
        if (fresh.inPlaylist) {
            PlaybackOrderCoordinator.removeMembers(context, listOf(fresh))
        } else {
            PlaybackOrderCoordinator.addMembers(context, listOf(fresh))
        }
    }

    suspend fun addToPlaylist(entities: List<WallpaperEntity>) = withContext(Dispatchers.IO) {
        PlaybackOrderCoordinator.addMembers(context, entities)
    }

    suspend fun removeFromPlaylist(entities: List<WallpaperEntity>) = withContext(Dispatchers.IO) {
        PlaybackOrderCoordinator.removeMembers(context, entities)
    }

    suspend fun reorderPlaylist(ordered: List<WallpaperEntity>) = withContext(Dispatchers.IO) {
        PlaybackOrderCoordinator.reorderCanonical(context, ordered)
    }

    suspend fun clearPlaylist() = withContext(Dispatchers.IO) {
        PlaybackOrderCoordinator.clearMembers(context)
    }

    /** 删除一条记录，并释放 URI 权限以减少系统侧累积。 */
    suspend fun delete(entity: WallpaperEntity) = withContext(Dispatchers.IO) {
        val fresh = dao.findById(entity.id) ?: entity
        if (fresh.inPlaylist) {
            PlaybackOrderCoordinator.removeMembers(context, listOf(fresh))
        }
        val uri = Uri.parse(fresh.uri)
        releasePersistedReadPermissionIfHeld(uri)
        deleteOwnedLocalCopyIfNeeded(uri)
        dao.deleteById(fresh.id)
    }

    suspend fun deleteMany(entities: List<WallpaperEntity>): Int = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext 0
        val freshByUri = dao.findByUris(entities.map { it.uri }.distinct()).associateBy { it.uri }
        val fresh = entities.mapNotNull { freshByUri[it.uri] }.distinctBy { it.id }
        val playlistMembers = fresh.filter { it.inPlaylist }
        if (playlistMembers.isNotEmpty()) {
            PlaybackOrderCoordinator.removeMembers(context, playlistMembers)
        }
        fresh.forEach { entity ->
            val uri = Uri.parse(entity.uri)
            releasePersistedReadPermissionIfHeld(uri)
            deleteOwnedLocalCopyIfNeeded(uri)
        }
        AppDatabase.getInstance(context).withTransaction {
            dao.deleteByIds(fresh.map { it.id })
        }
    }

    suspend fun toggleFavorite(entity: WallpaperEntity) = withContext(Dispatchers.IO) {
        dao.update(entity.copy(isFavorite = !entity.isFavorite))
    }

    suspend fun rename(entity: WallpaperEntity, newName: String) = withContext(Dispatchers.IO) {
        dao.update(entity.copy(name = newName))
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun localCopyStats(): LocalCopyStats = withContext(Dispatchers.IO) {
        ensureLocalImportDir()
        val root = localImportDir()
        val files = root.listFiles()?.filter { it.isFile && it.name != ".nomedia" }.orEmpty()
        LocalCopyStats(
            directoryPath = root.absolutePath,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() }
        )
    }

    fun openLocalCopyDirectory(): Boolean {
        ensureLocalImportDir()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(localImportDir()), "*/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releasePersistedReadPermissionIfHeld(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun typeFromMime(mime: String): WallpaperType {
        return when {
            mime.startsWith("video/") -> WallpaperType.VIDEO
            mime.startsWith("image/") -> WallpaperType.STATIC
            else -> WallpaperType.STATIC
        }
    }

    private fun typeFromFile(file: File): WallpaperType {
        val extension = file.extension.lowercase()
        return when (extension) {
            "mp4", "mkv", "mov", "webm", "avi", "3gp" -> WallpaperType.VIDEO
            else -> WallpaperType.STATIC
        }
    }

    private fun isSupportedMediaFile(file: File): Boolean {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp",
            "mp4", "mkv", "mov", "webm", "avi", "3gp" -> true
            else -> false
        }
    }

    private fun collectLocalImportMediaFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { directory -> directory.name != ".thumbnails" }
            .forEach { file ->
                if (file.isFile && file.name != ".nomedia" && isSupportedMediaFile(file)) {
                    result += file
                }
            }
        return result
    }

    private fun copyToLocalImportDir(sourceUri: Uri, displayName: String, mime: String): Uri? {
        val importDir = ensureLocalImportDir()
        val safeName = sanitizeFileName(displayName).ifBlank { "wallpaper" }
        val extension = extensionFor(displayName, mime)
        val baseName = safeName.removeSuffix(".$extension").ifBlank { "wallpaper" }
        val target = File(importDir, "${System.currentTimeMillis()}_${UUID.randomUUID()}_$baseName.$extension")

        var copySuccessful = false
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                    copySuccessful = true
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!copySuccessful && target.exists()) {
                target.delete()
            }
        }

        return if (copySuccessful) Uri.fromFile(target) else null
    }

    private fun extensionFor(displayName: String, mime: String): String {
        val fromName = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.length in 1..8 }
        if (!fromName.isNullOrEmpty()) return fromName
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (mime.startsWith("video/")) "mp4" else "jpg"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\x00-\x1F\\/:*?"<>|]"""), "_")
            .trim()
            .take(80)
    }

    private fun localImportDir(): File {
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, "wallpapers")
        } else {
            File(context.filesDir, "wallpapers")
        }
    }

    private fun ensureLocalImportDir(): File {
        val dir = localImportDir()
        try {
            dir.mkdirs()
            File(dir, ".nomedia").apply {
                if (!exists()) createNewFile()
            }
        } catch (_: Exception) {
        }
        return dir
    }

    private fun deleteOwnedLocalCopyIfNeeded(uri: Uri) {
        if (uri.scheme != "file") return
        try {
            val target = File(uri.path ?: return).canonicalFile
            val root = localImportDir().canonicalFile
            if (target.path.startsWith(root.path)) {
                target.delete()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun cleanupOrphanedUriPermissions() = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val persisted = resolver.persistedUriPermissions
            if (persisted.isNullOrEmpty()) return@withContext

            val dbUris = dao.allUris().toSet()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

            for (perm in persisted) {
                val permUri = perm.uri
                val permUriStr = permUri.toString()
                val isReferenced = dbUris.any { dbUri ->
                    dbUri == permUriStr ||
                    dbUri.startsWith(permUriStr + "/") ||
                    dbUri.startsWith(permUriStr + "%2F")
                }

                if (!isReferenced) {
                    try {
                        resolver.releasePersistableUriPermission(permUri, flags)
                    } catch (_: SecurityException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun makeSpaceForPersistablePermissions(requiredCount: Int) = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val currentPersisted = resolver.persistedUriPermissions
            val limit = if (android.os.Build.VERSION.SDK_INT >= 30) 512 else 128
            val safeLimit = limit - requiredCount - 5

            if (currentPersisted.size <= safeLimit) return@withContext

            val persistedUris = currentPersisted.map { it.uri }.toSet()
            val allWallpapers = dao.allSnapshot()
            val contentWallpapers = allWallpapers.filter { it.uri.startsWith("content://") }
                .sortedBy { it.addedAt }

            var excessCount = currentPersisted.size - safeLimit
            var changedPlaylistUri = false
            for (wallpaper in contentWallpapers) {
                if (excessCount <= 0) break

                val uri = Uri.parse(wallpaper.uri)
                if (uri !in persistedUris) continue

                val mime = getSafeMimeType(uri)
                val copiedUri = copyToLocalImportDir(uri, wallpaper.name, mime)
                if (copiedUri != null) {
                    dao.update(wallpaper.copy(uri = copiedUri.toString()))
                    if (wallpaper.inPlaylist) changedPlaylistUri = true
                    releasePersistedReadPermissionIfHeld(uri)
                    excessCount--
                }
            }
            if (changedPlaylistUri) PlaybackOrderCoordinator.reconcile(context)
        } catch (_: Exception) {
        }
    }

    private suspend fun evictFolderPermissionsLru(requiredCount: Int) = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val currentPersisted = resolver.persistedUriPermissions
            val limit = if (android.os.Build.VERSION.SDK_INT >= 30) 512 else 128
            val safeLimit = limit - requiredCount - 5

            if (currentPersisted.size <= safeLimit) return@withContext

            var excessCount = currentPersisted.size - safeLimit
            val folderPermissions = dao.getOldestFolderPermissions()
            if (folderPermissions.isEmpty()) return@withContext

            val db = AppDatabase.getInstance(context)
            var removedPlaylistMember = false

            for (fp in folderPermissions) {
                if (excessCount <= 0) break
                val uri = Uri.parse(fp.uri)
                try {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                dao.deleteFolderPermission(fp.uri)

                db.withTransaction {
                    val all = dao.allSnapshot()
                    val folderUriStr = fp.uri
                    val toDelete = all.filter { entity ->
                        entity.uri == folderUriStr ||
                            entity.uri.startsWith(folderUriStr + "/") ||
                            entity.uri.startsWith(folderUriStr + "%2F")
                    }
                    if (toDelete.any { it.inPlaylist }) removedPlaylistMember = true
                    if (toDelete.isNotEmpty()) dao.deleteByIds(toDelete.map { it.id })
                }

                excessCount--
            }
            if (removedPlaylistMember) PlaybackOrderCoordinator.reconcile(context)
        } catch (e: Exception) {
            android.util.Log.e("WallpaperRepository", "Error evicting folder permissions LRU", e)
        }
    }

    private fun getSafeMimeType(uri: Uri): String {
        var mime = try {
            context.contentResolver.getType(uri).orEmpty()
        } catch (_: SecurityException) {
            ""
        }
        if (mime.isEmpty()) {
            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            if (!extension.isNullOrEmpty()) {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()).orEmpty()
            }
        }
        if (mime.isEmpty() && uri.scheme == "file") {
            val ext = uri.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext.isNotEmpty()) {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
            }
        }
        return mime
    }

    private fun extractVideoMeta(uri: Uri): Triple<Int?, Int?, Long?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (rotation % 180 != 0 && width != null && height != null) {
                val tmp = width
                width = height
                height = tmp
            }
            Triple(width, height, duration)
        } catch (e: Exception) {
            android.util.Log.w("WallpaperRepository", "Failed to extract video meta for $uri", e)
            Triple(null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractImageMeta(uri: Uri): Triple<Int?, Int?, Long?> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                var width = options.outWidth
                var height = options.outHeight
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val exif = android.media.ExifInterface(input)
                        val orientation = exif.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                            orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE
                        ) {
                            val temp = width
                            width = height
                            height = temp
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WallpaperRepository", "Failed to parse EXIF orientation for $uri", e)
                }
                Triple(width, height, null)
            } else {
                Triple(null, null, null)
            }
        } catch (e: Exception) {
            android.util.Log.w("WallpaperRepository", "Failed to extract image meta for $uri", e)
            Triple(null, null, null)
        }
    }

    suspend fun migrateAllIndexToLocal(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val all = dao.allSnapshot()
        val contentWallpapers = all.filter { it.uri.startsWith("content://") }
        if (contentWallpapers.isEmpty()) return@withContext 0

        var migratedCount = 0
        var changedPlaylistUri = false
        val total = contentWallpapers.size
        for ((index, wallpaper) in contentWallpapers.withIndex()) {
            val uri = Uri.parse(wallpaper.uri)
            val mime = getSafeMimeType(uri)
            val copiedUri = copyToLocalImportDir(uri, wallpaper.name, mime)
            if (copiedUri != null) {
                val finalUri = copiedUri
                val meta = when (wallpaper.type) {
                    WallpaperType.VIDEO -> extractVideoMeta(finalUri)
                    WallpaperType.STATIC -> extractImageMeta(finalUri)
                }
                dao.update(wallpaper.copy(
                    uri = finalUri.toString(),
                    width = wallpaper.width ?: meta.first,
                    height = wallpaper.height ?: meta.second,
                    duration = wallpaper.duration ?: meta.third
                ))
                if (wallpaper.inPlaylist) changedPlaylistUri = true
                releasePersistedReadPermissionIfHeld(uri)
                migratedCount++
            }
            onProgress(index + 1, total)
        }
        if (changedPlaylistUri) PlaybackOrderCoordinator.reconcile(context)
        migratedCount
    }

    suspend fun migrateInternalToExternal() = withContext(Dispatchers.IO) {
        val all = dao.allSnapshot()
        val internalPrefix = context.filesDir.absolutePath
        val externalDir = context.getExternalFilesDir(null) ?: return@withContext
        val targetDir = File(externalDir, "wallpapers")
        if (!targetDir.exists()) {
            try {
                targetDir.mkdirs()
            } catch (_: Exception) {}
        }
        if (!targetDir.exists()) return@withContext

        var changedPlaylistUri = false
        for (wallpaper in all) {
            val uri = Uri.parse(wallpaper.uri)
            if (uri.scheme == "file") {
                val path = uri.path ?: continue
                val file = File(path)
                if (path.startsWith(internalPrefix) && file.exists()) {
                    val targetFile = File(targetDir, file.name)
                    var copySuccessful = false
                    try {
                        file.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                                copySuccessful = true
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WallpaperRepository", "Failed to copy file from $file to $targetFile during migration", e)
                    }

                    if (copySuccessful) {
                        val newUri = Uri.fromFile(targetFile).toString()
                        dao.update(wallpaper.copy(uri = newUri))
                        if (wallpaper.inPlaylist) changedPlaylistUri = true
                        try {
                            file.delete()
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        if (changedPlaylistUri) PlaybackOrderCoordinator.reconcile(context)
    }

    companion object {
        @Volatile
        private var INSTANCE: WallpaperRepository? = null

        fun getInstance(context: Context): WallpaperRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WallpaperRepository(
                    context.applicationContext,
                    AppDatabase.getInstance(context).wallpaperDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
