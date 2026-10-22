package com.wallpaperhub.data.repo

import java.security.MessageDigest

/** Keeps queue metadata bounded regardless of how many wallpapers the user owns. */
internal object PlaybackQueueStoragePolicy {
    private const val EMPTY_JSON = "[]"
    private val hex = "0123456789abcdef".toCharArray()

    @Suppress("UNUSED_PARAMETER")
    fun legacyQueueJson(queue: List<String>): String = EMPTY_JSON

    @Suppress("UNUSED_PARAMETER")
    fun legacyShuffleRoundJson(queue: List<String>): String = EMPTY_JSON

    fun fingerprint(queue: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        queue.forEach { uri ->
            val bytes = uri.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().let { bytes ->
            CharArray(bytes.size * 2).also { output ->
                bytes.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    output[index * 2] = hex[value ushr 4]
                    output[index * 2 + 1] = hex[value and 0x0f]
                }
            }.concatToString()
        }
    }
}
