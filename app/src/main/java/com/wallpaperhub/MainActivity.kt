package com.wallpaperhub

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.AppNavigation
import com.wallpaperhub.ui.theme.ThemeViewModel
import com.wallpaperhub.ui.theme.WallpaperHubTheme

/** Application's only Activity. Media pixels never feed application chrome. */
class MainActivity : ComponentActivity() {
    private val debugId = WallpaperDebugLog.nextId("activity")
    private var createdAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        createdAtMs = SystemClock.elapsedRealtime()
        WallpaperDebugLog.i("app_ui", "activity_create_start", "activity" to debugId, "restored" to (savedInstanceState != null))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = viewModel()
            val profile by themeVm.profile.collectAsStateWithLifecycle()
            WallpaperHubTheme(profile = profile) {
                LaunchedEffect(Unit) {
                    WallpaperDebugLog.i("app_ui", "compose_root_entered", "activity" to debugId, "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs)
                }
                AppNavigation(profile = profile, themeVm = themeVm)
            }
        }
        WallpaperDebugLog.i("app_ui", "activity_content_set", "activity" to debugId, "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs)
    }

    override fun onStart() { super.onStart(); WallpaperDebugLog.d("app_ui", "activity_start", "activity" to debugId) }
    override fun onResume() { super.onResume(); WallpaperDebugLog.i("app_ui", "activity_resume", "activity" to debugId, "elapsedMs" to SystemClock.elapsedRealtime() - createdAtMs) }
    override fun onPause() { WallpaperDebugLog.d("app_ui", "activity_pause", "activity" to debugId); super.onPause() }
    override fun onStop() { WallpaperDebugLog.d("app_ui", "activity_stop", "activity" to debugId); super.onStop() }
    override fun onDestroy() { WallpaperDebugLog.i("app_ui", "activity_destroy", "activity" to debugId, "finishing" to isFinishing); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); WallpaperDebugLog.d("app_ui", "window_focus", "activity" to debugId, "focused" to hasFocus) }
}
