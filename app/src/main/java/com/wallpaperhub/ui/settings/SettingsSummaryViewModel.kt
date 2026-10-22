package com.wallpaperhub.ui.settings

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Minimal state for the Settings landing page; intentionally does not subscribe to the wallpaper DB. */
class SettingsSummaryViewModel(app: Application) : AndroidViewModel(app), SharedPreferences.OnSharedPreferenceChangeListener {
    private val context = app.applicationContext
    private val prefs = VideoWallpaperSettings.prefs(context)

    private val _transitionType = MutableStateFlow(prefs.getString(VideoWallpaperSettings.KEY_TRANSITION_TYPE, "crossfade") ?: "crossfade")
    val transitionType: StateFlow<String> = _transitionType

    private val _cacheSize = MutableStateFlow("正在读取")
    val cacheSize: StateFlow<String> = _cacheSize

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        refreshCacheSize()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == VideoWallpaperSettings.KEY_TRANSITION_TYPE) {
            _transitionType.value = prefs.getString(VideoWallpaperSettings.KEY_TRANSITION_TYPE, "crossfade") ?: "crossfade"
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            var total = 0L
            listOf(context.cacheDir, context.externalCacheDir).filterNotNull().forEach { dir ->
                total += dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
            }
            _cacheSize.value = MediaMetadataFormatter.formatBytes(total)
        }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onCleared()
    }
}
