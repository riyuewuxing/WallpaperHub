package com.wallpaperhub.playback.telemetry

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.wallpaperhub.BuildConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured debug-only Logcat output for reproducing wallpaper apply and carousel stalls.
 *
 * Full media URIs are deliberately never logged. [uri] returns a stable short fingerprint so
 * events from UI, database, wallpaper engines, renderers, and decoders can still be correlated.
 * All free-form fields and throwable messages are also redacted before they reach Logcat.
 */
object WallpaperDebugLog {
    const val TAG = "WPH_TRACE"

    val enabled: Boolean
        get() = BuildConfig.DEBUG

    private val sequence = AtomicLong(0L)
    private val ids = AtomicLong(0L)
    private val mainWatchdogStarted = AtomicBoolean(false)

    fun startMainThreadWatchdog() {
        if (!enabled || !mainWatchdogStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        var expectedAtMs = SystemClock.elapsedRealtime() + MAIN_WATCHDOG_INTERVAL_MS
        val heartbeat = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val lagMs = now - expectedAtMs
                if (lagMs >= MAIN_THREAD_LAG_WARNING_MS) {
                    w(
                        "process",
                        "main_thread_lag",
                        "lagMs" to lagMs,
                    )
                }
                expectedAtMs = now + MAIN_WATCHDOG_INTERVAL_MS
                handler.postDelayed(this, MAIN_WATCHDOG_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeat, MAIN_WATCHDOG_INTERVAL_MS)
        i("process", "main_thread_watchdog_started")
    }

    fun nextId(prefix: String): String = "$prefix-${ids.incrementAndGet()}"

    fun uri(value: Any?): String {
        val raw = value?.toString().orEmpty()
        if (raw.isBlank()) return "none"
        val hash = raw.hashCode().toUInt().toString(16).padStart(8, '0')
        val suffix = raw.substringBefore('?')
            .substringAfterLast('/')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)
            .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        return if (suffix == null) "uri#$hash" else "uri#$hash.$suffix"
    }

    /** Redacts URIs/device paths for user-visible diagnostics while preserving readable spacing. */
    fun safeText(value: Any?): String = DebugLogSanitizer.redact(value?.toString().orEmpty())
        .take(MAX_SAFE_TEXT_LENGTH)

    fun command(value: String?): String = value
        ?.takeIf(String::isNotBlank)
        ?.take(8)
        ?: "none"

    fun i(area: String, event: String, vararg fields: Pair<String, Any?>) {
        log(Log.INFO, area, event, null, fields)
    }

    fun d(area: String, event: String, vararg fields: Pair<String, Any?>) {
        log(Log.DEBUG, area, event, null, fields)
    }

    fun w(area: String, event: String, vararg fields: Pair<String, Any?>) {
        log(Log.WARN, area, event, null, fields)
    }

    fun e(area: String, event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        log(Log.ERROR, area, event, error, fields)
    }

    private fun log(
        priority: Int,
        area: String,
        event: String,
        error: Throwable?,
        fields: Array<out Pair<String, Any?>>,
    ) {
        if (!enabled) return
        val seq = sequence.incrementAndGet()
        val message = buildString(224) {
            append("seq=").append(seq)
            append(" monoMs=").append(SystemClock.elapsedRealtime())
            append(" pid=").append(Process.myPid())
            append(" thread=").append(DebugLogSanitizer.token(Thread.currentThread().name))
            append(" area=").append(DebugLogSanitizer.token(area))
            append(" event=").append(DebugLogSanitizer.token(event))
            fields.forEach { (key, value) ->
                append(' ')
                append(DebugLogSanitizer.token(key))
                append('=')
                append(DebugLogSanitizer.field(value))
            }
            if (error != null) {
                append(" errorType=").append(DebugLogSanitizer.token(error.javaClass.name))
                append(" errorMessage=").append(DebugLogSanitizer.field(error.message))
            }
        }

        Log.println(priority, TAG, message)
        if (error != null) {
            DebugLogSanitizer.throwableStack(error).forEach { line ->
                Log.println(priority, TAG, "seq=$seq detail=$line")
            }
        }
    }

    private const val MAIN_WATCHDOG_INTERVAL_MS = 500L
    private const val MAIN_THREAD_LAG_WARNING_MS = 200L
    private const val MAX_SAFE_TEXT_LENGTH = 500
}

/** Pure sanitizer kept Android-free so privacy behavior can be unit-tested on the JVM. */
internal object DebugLogSanitizer {
    private val uriPattern = Regex("""(?i)\b(?:content|file|https?|ftp)://[^\s\"'<>]+""")
    private val windowsPathPattern = Regex("""(?i)\b[A-Z]:\\[^\s\"'<>]+""")
    private val androidPathPattern = Regex("""(?i)(?<![A-Za-z0-9])/(?:storage|sdcard|data|mnt|system|vendor|product|apex)/[^\s\"'<>]+""")

    fun field(value: Any?): String = redact(value?.toString().orEmpty())
        .replace('\n', '_')
        .replace('\r', '_')
        .replace(' ', '_')
        .take(MAX_FIELD_LENGTH)

    fun token(value: String): String = field(value)

    fun redact(value: String): String = value
        .replace(uriPattern, "<redacted_uri>")
        .replace(windowsPathPattern, "<redacted_path>")
        .replace(androidPathPattern, "<redacted_path>")

    fun throwableStack(error: Throwable): List<String> {
        val output = mutableListOf<String>()
        var current: Throwable? = error
        var causeDepth = 0
        while (current != null && causeDepth < MAX_CAUSE_DEPTH) {
            output += "cause=$causeDepth type=${token(current.javaClass.name)}"
            current.stackTrace.take(MAX_STACK_FRAMES_PER_CAUSE).forEach { frame ->
                val file = frame.fileName?.let(::field) ?: "Unknown"
                output += "at=${token(frame.className)}.${token(frame.methodName)}($file:${frame.lineNumber})"
            }
            current = current.cause
            causeDepth++
        }
        return output
    }

    private const val MAX_FIELD_LENGTH = 160
    private const val MAX_CAUSE_DEPTH = 4
    private const val MAX_STACK_FRAMES_PER_CAUSE = 24
}
