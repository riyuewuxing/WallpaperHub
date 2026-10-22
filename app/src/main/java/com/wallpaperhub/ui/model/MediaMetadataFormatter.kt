package com.wallpaperhub.ui.model

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MediaMetadataFormatter {
    fun formatBytes(bytes: Long?): String {
        val value = bytes?.coerceAtLeast(0L) ?: return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unitIndex = -1
        while (size >= 1024 && unitIndex < units.lastIndex) {
            size /= 1024
            unitIndex++
        }
        return "${DecimalFormat("0.#").format(size)} ${units[unitIndex]}"
    }

    fun formatDuration(durationMillis: Long?): String {
        val totalSeconds = durationMillis?.coerceAtLeast(0L)?.div(1000L) ?: return "—"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    fun formatAddedDate(
        timestampMillis: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).apply {
        this.timeZone = timeZone
    }.format(Date(timestampMillis))
}
