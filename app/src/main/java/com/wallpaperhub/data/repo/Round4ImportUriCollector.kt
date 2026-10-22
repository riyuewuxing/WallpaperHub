package com.wallpaperhub.data.repo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Round4ImportUriCollector {
    suspend fun treeMedia(context: Context, treeUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context.applicationContext, treeUri) ?: return@withContext emptyList()
        val result = mutableListOf<Uri>()
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            node.listFiles().forEach { child ->
                if (child.isDirectory) pending.add(child)
                else if (child.isFile && child.type?.let { it.startsWith("image/") || it.startsWith("video/") } == true) result.add(child.uri)
            }
        }
        result
    }

    suspend fun albumMedia(context: Context, bucketId: String): List<Uri> = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val result = mutableListOf<Uri>()
        listOf(
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_ID),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID, MediaStore.Video.Media.BUCKET_ID),
        ).forEach { (collection, idColumn, bucketColumn) ->
            resolver.query(collection, arrayOf(idColumn), "$bucketColumn=?", arrayOf(bucketId), null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                while (cursor.moveToNext()) result.add(ContentUris.withAppendedId(collection, cursor.getLong(idIndex)))
            }
        }
        result
    }
}
