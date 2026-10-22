package com.wallpaperhub.data.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.CRC32

/**
 * Lightweight source identity used by rebuildable media caches.
 *
 * revisionStamp is normally the provider/file last-modified timestamp. When a content provider does
 * not expose one, a negative CRC32 of the first few KiB is used instead. The full SHA-256 remains
 * authoritative for exact-duplicate detection; this stamp only decides whether cached analysis can
 * be safely reused without decoding the whole item again.
 */
data class MediaSourceStamp(
    val sizeBytes: Long,
    val revisionStamp: Long,
)

object MediaSourceStampReader {
    private const val FINGERPRINT_BYTES = 4 * 1024
    private const val SECONDS_TO_MILLIS_CUTOFF = 10_000_000_000L

    fun read(context: Context, uri: Uri): MediaSourceStamp {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            val size = file?.takeIf(File::isFile)?.length() ?: -1L
            val modified = file?.takeIf(File::exists)?.lastModified() ?: 0L
            val revision = modified.takeIf { it > 0L }
                ?: quickFingerprint(openFile(uri))
            return MediaSourceStamp(size, revision)
        }

        val metadata = queryMetadata(context, uri)
        val size = metadata.sizeBytes.takeIf { it >= 0L }
            ?: runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0L }
            ?: -1L
        val revision = metadata.lastModifiedMillis.takeIf { it > 0L }
            ?: quickFingerprint(runCatching { context.contentResolver.openInputStream(uri) }.getOrNull())
        return MediaSourceStamp(size, revision)
    }

    private data class ProviderMetadata(
        val sizeBytes: Long = -1L,
        val lastModifiedMillis: Long = 0L,
    )

    private fun queryMetadata(context: Context, uri: Uri): ProviderMetadata {
        var size = -1L
        var modified = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it) }

                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { index -> modified = maxOf(modified, normalizeTimestamp(cursor.getLong(index))) }

                cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { index -> modified = maxOf(modified, normalizeTimestamp(cursor.getLong(index))) }
            }
        }
        return ProviderMetadata(sizeBytes = size, lastModifiedMillis = modified)
    }

    private fun normalizeTimestamp(value: Long): Long {
        if (value <= 0L) return 0L
        return if (value < SECONDS_TO_MILLIS_CUTOFF) value * 1_000L else value
    }

    private fun quickFingerprint(input: InputStream?): Long {
        if (input == null) return 0L
        return runCatching {
            input.use { stream ->
                val crc = CRC32()
                val buffer = ByteArray(1024)
                var remaining = FINGERPRINT_BYTES
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    crc.update(buffer, 0, read)
                    remaining -= read
                }
                -(crc.value + 1L)
            }
        }.getOrDefault(0L)
    }

    private fun openFile(uri: Uri): InputStream? {
        val path = uri.path ?: return null
        return runCatching { FileInputStream(File(path)) }.getOrNull()
    }
}
