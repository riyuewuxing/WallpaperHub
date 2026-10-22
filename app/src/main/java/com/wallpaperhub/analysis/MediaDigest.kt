package com.wallpaperhub.analysis

import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.min

object MediaDigest {
    private val unsigned64Modulus: BigInteger = BigInteger.ONE.shiftLeft(64)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun encodeHashSequence(values: List<Long>): String = values.joinToString(",") { value ->
        val unsigned = if (value >= 0L) BigInteger.valueOf(value) else BigInteger.valueOf(value).add(unsigned64Modulus)
        unsigned.toString(16).padStart(16, '0')
    }

    fun decodeHashSequence(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').mapNotNull { token ->
            runCatching { BigInteger(token.trim(), 16).toLong() }.getOrNull()
        }
    }

    fun hammingDistance64(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun sequenceSimilarity(a: List<Long>, b: List<Long>): Double? {
        val count = min(a.size, b.size)
        if (count == 0) return null
        var totalDistance = 0
        for (index in 0 until count) totalDistance += hammingDistance64(a[index], b[index])
        return (1.0 - totalDistance.toDouble() / (64.0 * count)).coerceIn(0.0, 1.0)
    }
}
