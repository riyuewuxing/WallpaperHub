package com.wallpaperhub.wallpaper

import android.content.Context
import android.content.SharedPreferences

/** Playback settings. Strategy generates a full plan; the renderer only consumes that plan. */
object VideoWallpaperSettings {
    const val PREFS_NAME = "video_wallpaper_prefs"
    const val KEY_VIDEO_URI = "video_uri"
    const val KEY_ROTATE_INTERVAL_SECONDS = "rotate_interval_seconds"
    const val KEY_RANDOM_ENABLED = "random_enabled"
    const val KEY_PLAYBACK_MODE = "playback_mode"
    const val KEY_SEQUENTIAL_REVERSED = "sequential_reversed"
    const val KEY_DISPLAY_GRAVITY = "display_gravity"
    const val KEY_TRANSITION_TYPE = "transition_type"
    const val KEY_PARALLAX_STRENGTH = "parallax_strength"
    const val KEY_CROP_OFFSET_PREFIX = "crop_offset:"
    const val KEY_CURRENT_WALLPAPER_URI = "current_wallpaper_uri"

    enum class PlaybackMode(val value: String, val label: String) {
        SEQUENTIAL("sequential", "顺序播放"),
        SHUFFLE("shuffle", "洗牌播放"),
        RANDOM("random", "随机播放");

        companion object {
            fun fromValue(value: String?): PlaybackMode? = entries.firstOrNull { it.value == value }
        }
    }

    /**
     * Kept for renderer/backward compatibility. Product behavior is now always CENTER unless an
     * individual wallpaper has an explicit CropTransform saved from the editor.
     */
    enum class DisplayGravity(val value: String, val label: String) {
        CENTER("center", "居中"),
        LEFT("left", "偏左"),
        RIGHT("right", "偏右"),
        TOP("top", "靠上"),
        BOTTOM("bottom", "靠下");
    }

    data class Settings(
        val rotateIntervalSeconds: Int,
        val randomEnabled: Boolean,
        val playbackMode: PlaybackMode,
        val displayGravity: DisplayGravity,
        val transitionType: String = "crossfade",
        val parallaxStrength: Float = 1.0f,
        /** false = newest -> oldest, true = oldest -> newest. Only meaningful in SEQUENTIAL. */
        val sequentialReversed: Boolean = false,
    )

    val intervalOptions: List<Pair<Int, String>> = listOf(
        0 to "播完后切换",
        15 to "15 秒",
        30 to "30 秒",
        60 to "1 分钟",
        300 to "5 分钟",
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val prefs = prefs(context)
        val legacyRandomEnabled = prefs.getBoolean(KEY_RANDOM_ENABLED, false)
        val playbackMode = PlaybackMode.fromValue(prefs.getString(KEY_PLAYBACK_MODE, null))
            ?: if (legacyRandomEnabled) PlaybackMode.RANDOM else PlaybackMode.SEQUENTIAL
        return Settings(
            rotateIntervalSeconds = prefs.getInt(KEY_ROTATE_INTERVAL_SECONDS, 0),
            randomEnabled = playbackMode == PlaybackMode.SHUFFLE || playbackMode == PlaybackMode.RANDOM,
            playbackMode = playbackMode,
            // Global gravity created a second composition system that duplicated the per-wallpaper
            // crop editor. Keep the invariant simple: unedited media starts centered.
            displayGravity = DisplayGravity.CENTER,
            transitionType = prefs.getString(KEY_TRANSITION_TYPE, "crossfade")
                ?.lowercase()
                ?.takeIf { it == "crossfade" || it == "zoom" }
                ?: "crossfade",
            parallaxStrength = prefs.getFloat(KEY_PARALLAX_STRENGTH, 1.0f).coerceIn(0f, 2f),
            sequentialReversed = prefs.getBoolean(KEY_SEQUENTIAL_REVERSED, false),
        )
    }

    fun updateRotateInterval(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_ROTATE_INTERVAL_SECONDS, seconds).apply()
    }

    fun updateRandomEnabled(context: Context, enabled: Boolean) {
        updatePlaybackMode(context, if (enabled) PlaybackMode.RANDOM else PlaybackMode.SEQUENTIAL)
    }

    fun updatePlaybackMode(context: Context, playbackMode: PlaybackMode) {
        prefs(context).edit()
            .putBoolean(
                KEY_RANDOM_ENABLED,
                playbackMode == PlaybackMode.SHUFFLE || playbackMode == PlaybackMode.RANDOM,
            )
            .putString(KEY_PLAYBACK_MODE, playbackMode.value)
            .apply()
    }

    fun updateSequentialReversed(context: Context, reversed: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEQUENTIAL_REVERSED, reversed).apply()
    }

    /** Legacy compatibility entry point. Global gravity is intentionally no longer persisted. */
    fun updateDisplayGravity(context: Context, displayGravity: DisplayGravity) {
        prefs(context).edit().remove(KEY_DISPLAY_GRAVITY).apply()
    }

    fun gravityToOffset(gravity: DisplayGravity): Pair<Float, Float> = when (gravity) {
        DisplayGravity.CENTER -> 0f to 0f
        DisplayGravity.LEFT -> -1f to 0f
        DisplayGravity.RIGHT -> 1f to 0f
        DisplayGravity.TOP -> 0f to -1f
        DisplayGravity.BOTTOM -> 0f to 1f
    }

    fun saveCropOffset(context: Context, uri: String, x: Float, y: Float) {
        prefs(context).edit()
            .putFloat("$KEY_CROP_OFFSET_PREFIX$uri:x", x.coerceIn(-1f, 1f))
            .putFloat("$KEY_CROP_OFFSET_PREFIX$uri:y", y.coerceIn(-1f, 1f))
            .apply()
    }

    fun loadCropOffset(context: Context, uri: String): Pair<Float, Float>? {
        val p = prefs(context)
        val xKey = "$KEY_CROP_OFFSET_PREFIX$uri:x"
        val yKey = "$KEY_CROP_OFFSET_PREFIX$uri:y"
        if (!p.contains(xKey)) return null
        return p.getFloat(xKey, 0f) to p.getFloat(yKey, 0f)
    }

    fun loadAdjustedCropUris(context: Context): Set<String> = prefs(context).all.keys
        .asSequence()
        .filter { key -> key.startsWith(KEY_CROP_OFFSET_PREFIX) && key.endsWith(":x") }
        .map { key -> key.removePrefix(KEY_CROP_OFFSET_PREFIX).removeSuffix(":x") }
        .toSet()

    fun clearCropOffset(context: Context, uri: String) {
        prefs(context).edit()
            .remove("$KEY_CROP_OFFSET_PREFIX$uri:x")
            .remove("$KEY_CROP_OFFSET_PREFIX$uri:y")
            .apply()
    }

    fun saveCurrentWallpaperUri(context: Context, uri: String) {
        prefs(context).edit().putString(KEY_CURRENT_WALLPAPER_URI, uri).apply()
    }

    fun loadCurrentWallpaperUri(context: Context): String? =
        prefs(context).getString(KEY_CURRENT_WALLPAPER_URI, null)

    fun isWallpaperServiceActive(context: Context): Boolean {
        val manager = android.app.WallpaperManager.getInstance(context)
        val info = manager.wallpaperInfo
        return info != null && info.packageName == context.packageName
    }
}
