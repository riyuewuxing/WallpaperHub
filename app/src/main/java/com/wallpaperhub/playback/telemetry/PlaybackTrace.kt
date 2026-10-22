package com.wallpaperhub.playback.telemetry

import android.os.Trace

/** Small, consistently named trace slices for Perfetto/Android Studio System Trace. */
object PlaybackTrace {
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection("wallpaper.$name")
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    suspend inline fun <T> suspendSection(name: String, crossinline block: suspend () -> T): T {
        Trace.beginSection("wallpaper.$name")
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }
}
