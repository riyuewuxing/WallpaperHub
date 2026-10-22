package com.wallpaperhub.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.preview.model.ApplyLaunchStep

/** Starts system confirmation or submits an active-service command without claiming early success. */
object VideoWallpaperStarter {

    data class Result(
        val step: ApplyLaunchStep,
        val message: String,
    ) {
        val success: Boolean get() = step != ApplyLaunchStep.FAILED
    }

    fun saveVideoUri(context: Context, uri: Uri) {
        WallpaperDebugLog.d(
            "apply_launcher",
            "persist_target_uri",
            "uri" to WallpaperDebugLog.uri(uri),
        )
        VideoWallpaperSettings.prefs(context)
            .edit()
            .putString(VideoWallpaperSettings.KEY_VIDEO_URI, uri.toString())
            .apply()
    }

    fun loadVideoUri(context: Context): Uri? = VideoWallpaperSettings.prefs(context)
        .getString(VideoWallpaperSettings.KEY_VIDEO_URI, null)
        ?.let(Uri::parse)

    fun openSystemLiveWallpaperPage(
        context: Context,
        uri: Uri,
        forceOpenChooser: Boolean = false,
        coalesceActiveApply: Boolean = false,
    ): Result {
        // The selected first item must survive service/process recreation in both active and inactive paths.
        saveVideoUri(context, uri)
        val serviceActive = VideoWallpaperSettings.isWallpaperServiceActive(context)
        WallpaperDebugLog.i(
            "apply_launcher",
            "route_request",
            "uri" to WallpaperDebugLog.uri(uri),
            "forceChooser" to forceOpenChooser,
            "serviceActive" to serviceActive,
            "coalesceActiveApply" to coalesceActiveApply,
        )
        if (!forceOpenChooser && serviceActive) {
            val app = context.applicationContext as? WallpaperHubApp
            if (app == null) {
                WallpaperDebugLog.e(
                    "apply_launcher",
                    "application_cast_failed",
                    null,
                    "uri" to WallpaperDebugLog.uri(uri),
                )
                return Result(ApplyLaunchStep.FAILED, "无法连接壁纸服务，请重新打开应用")
            }
            // saveAndApply() has already rebuilt the stable playlist with this URI at slot 0.
            // A different pre-click target may therefore already be rendering from that playlist
            // revision. Repository coalescing waits for its confirmed first frame; same-URI reapply
            // passes false here and retains the original forced APPLY restart semantics.
            app.playbackRepository.submitApplyAfterRuntimeSwitchAsync(
                uri = uri.toString(),
                source = "ui",
                allowRuntimeCoalesce = coalesceActiveApply,
            )
            WallpaperDebugLog.i(
                "apply_launcher",
                "active_apply_command_routed",
                "uri" to WallpaperDebugLog.uri(uri),
                "coalesce" to coalesceActiveApply,
            )
            return Result(ApplyLaunchStep.COMMAND_SUBMITTED, "正在准备并验证壁纸…")
        }

        val component = ComponentName(context, VideoWallpaperService::class.java)
        val directIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(directIntent)
            WallpaperDebugLog.i(
                "apply_launcher",
                "system_preview_started",
                "uri" to WallpaperDebugLog.uri(uri),
                "newTask" to true,
            )
            Result(
                ApplyLaunchStep.SYSTEM_CONFIRMATION_REQUIRED,
                "已打开系统确认页，请完成“设置壁纸”后返回",
            )
        } catch (error: ActivityNotFoundException) {
            WallpaperDebugLog.w(
                "apply_launcher",
                "direct_preview_unavailable",
                "error" to error.javaClass.simpleName,
            )
            openChooserFallback(context)
        } catch (error: SecurityException) {
            WallpaperDebugLog.w(
                "apply_launcher",
                "direct_preview_denied",
                "error" to error.javaClass.simpleName,
            )
            openChooserFallback(context)
        }
    }

    private fun openChooserFallback(context: Context): Result = try {
        val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
        WallpaperDebugLog.i("apply_launcher", "chooser_started")
        Result(
            ApplyLaunchStep.SYSTEM_CONFIRMATION_REQUIRED,
            "已打开动态壁纸列表，请选择壁纸管家并完成设置",
        )
    } catch (error: Exception) {
        WallpaperDebugLog.e("apply_launcher", "chooser_failed", error)
        Result(
            ApplyLaunchStep.FAILED,
            error.localizedMessage ?: "无法打开系统动态壁纸页面",
        )
    }
}
