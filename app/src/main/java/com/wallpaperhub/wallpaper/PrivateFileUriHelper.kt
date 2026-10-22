package com.wallpaperhub.wallpaper

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object PrivateFileUriHelper {
    /**
     * If the URI is a file:/// URI pointing inside the app's private files directory,
     * convert it to a content:// URI using our FileProvider.
     * Otherwise, return the original URI.
     */
    fun safeUri(context: Context, uriString: String): Uri {
        val uri = Uri.parse(uriString)
        return safeUri(context, uri)
    }

    fun safeUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                val filesDir = context.filesDir
                val externalDir = context.getExternalFilesDir(null)
                val authority = "${context.packageName}.fileprovider"
                
                if (file.absolutePath.startsWith(filesDir.absolutePath)) {
                    return try {
                        FileProvider.getUriForFile(context, authority, file)
                    } catch (e: Exception) {
                        android.util.Log.e("PrivateFileUriHelper", "Failed to get content URI for $file", e)
                        uri
                    }
                } else if (externalDir != null && file.absolutePath.startsWith(externalDir.absolutePath)) {
                    return try {
                        FileProvider.getUriForFile(context, authority, file)
                    } catch (e: Exception) {
                        android.util.Log.e("PrivateFileUriHelper", "Failed to get content URI for $file in external", e)
                        uri
                    }
                }
            }
        }
        return uri
    }

    /**
     * Extracts the File object if it is a local file.
     */
    fun getFile(context: Context, uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    return file
                }
            }
        }
        return null
    }
}
