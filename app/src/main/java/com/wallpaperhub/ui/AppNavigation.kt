package com.wallpaperhub.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wallpaperhub.BuildConfig
import com.wallpaperhub.data.repo.Round4DuplicateImportAudit
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.app.DockDestination
import com.wallpaperhub.ui.app.WallpaperBottomDock
import com.wallpaperhub.ui.flow.FlowV1
import com.wallpaperhub.ui.library.DuplicateImportReviewSheet
import com.wallpaperhub.ui.library.LibraryV1
import com.wallpaperhub.ui.screen.DetailScreen
import com.wallpaperhub.ui.screen.WallpaperPreviewScreen
import com.wallpaperhub.ui.settings.SettingsV1
import com.wallpaperhub.ui.settings.ThemeStudioScreen
import com.wallpaperhub.ui.settings.ToolDetailScreen
import com.wallpaperhub.ui.showcase.UiShowcaseScreen
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals
import com.wallpaperhub.ui.theme.ThemeProfile
import com.wallpaperhub.ui.theme.ThemeViewModel
import com.wallpaperhub.ui.theme.WallpaperBackdrop
import com.wallpaperhub.ui.theme.WallpaperScene
import com.wallpaperhub.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay

object Routes {
    const val LIBRARY = "library"
    const val FLOW = "flow"
    const val SETTINGS = "settings"
    const val THEME = "theme"
    const val DETAIL = "detail/{wallpaperId}"
    const val PREVIEW = "preview/{wallpaperId}"
    const val TOOL = "tool/{toolId}"
    const val SHOWCASE = "showcase"
    fun detail(id: String) = "detail/$id"
    fun preview(id: String) = "preview/$id"
    fun tool(id: String) = "tool/$id"
}

@Composable
fun AppNavigation(profile: ThemeProfile, themeVm: ThemeViewModel) {
    val nav = rememberNavController()
    val libraryVm: LibraryViewModel = viewModel()
    val duplicateImportReport by Round4DuplicateImportAudit.report.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val visuals = LocalWallpaperVisuals.current
    var sceneBusy by remember { mutableStateOf(false) }
    var navigationBusy by remember { mutableStateOf(false) }
    val destinations = listOf(
        DockDestination(Routes.LIBRARY, "图库", Icons.Default.PhotoLibrary),
        DockDestination(Routes.FLOW, "轮播", Icons.Default.PlayCircle),
        DockDestination(Routes.SETTINGS, "设置", Icons.Default.Settings),
    )
    val scene = when (currentRoute) {
        Routes.LIBRARY -> WallpaperScene.LIBRARY
        Routes.FLOW -> WallpaperScene.FLOW
        Routes.SETTINGS -> WallpaperScene.SETTINGS
        Routes.THEME -> WallpaperScene.THEME_STUDIO
        Routes.DETAIL -> WallpaperScene.DETAIL
        Routes.PREVIEW -> WallpaperScene.PREVIEW
        else -> WallpaperScene.UTILITY
    }

    fun markNavigationBusy() {
        navigationBusy = true
    }

    LaunchedEffect(currentRoute) {
        sceneBusy = false
        navigationBusy = true
        WallpaperDebugLog.i("app_ui", "route_active", "route" to (currentRoute ?: "none"))
        delay(420)
        navigationBusy = false
    }

    WallpaperBackdrop(profile = profile, scene = scene, busy = sceneBusy || navigationBusy) {
        CompositionLocalProvider(LocalContentColor provides visuals.textPrimary) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = visuals.textPrimary,
                bottomBar = {
                    if (currentRoute in destinations.map { it.route }) {
                        WallpaperBottomDock(
                            destinations = destinations,
                            currentRoute = currentRoute,
                            onSelect = { route ->
                                markNavigationBusy()
                                nav.navigate(route) {
                                    popUpTo(Routes.LIBRARY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                NavHost(navController = nav, startDestination = Routes.LIBRARY, modifier = Modifier.padding(padding)) {
                    composable(Routes.LIBRARY) {
                        LibraryV1(
                            onOpenDetail = {
                                markNavigationBusy()
                                nav.navigate(Routes.detail(it))
                            },
                            vm = libraryVm,
                        )
                    }
                    composable(Routes.FLOW) {
                        FlowV1(
                            onOpenDetail = {
                                markNavigationBusy()
                                nav.navigate(Routes.detail(it))
                            },
                            vm = libraryVm,
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsV1(
                            onOpenTool = { id ->
                                markNavigationBusy()
                                when (id) {
                                    "theme" -> nav.navigate(Routes.THEME)
                                    "showcase" -> nav.navigate(Routes.SHOWCASE)
                                    else -> nav.navigate(Routes.tool(id))
                                }
                            },
                        )
                    }
                    composable(Routes.THEME) {
                        ThemeStudioScreen(
                            onBack = {
                                markNavigationBusy()
                                nav.popBackStack()
                            },
                            vm = themeVm,
                            onSceneBusyChange = { sceneBusy = it },
                        )
                    }
                    composable(Routes.DETAIL, arguments = listOf(navArgument("wallpaperId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("wallpaperId") ?: return@composable
                        DetailScreen(
                            wallpaperId = id,
                            onBack = {
                                markNavigationBusy()
                                nav.popBackStack()
                            },
                            onOpenPreview = {
                                markNavigationBusy()
                                nav.navigate(Routes.preview(it))
                            },
                        )
                    }
                    composable(Routes.PREVIEW, arguments = listOf(navArgument("wallpaperId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("wallpaperId") ?: return@composable
                        WallpaperPreviewScreen(
                            wallpaperId = id,
                            onBack = {
                                markNavigationBusy()
                                nav.popBackStack()
                            },
                        )
                    }
                    composable(Routes.TOOL, arguments = listOf(navArgument("toolId") { type = NavType.StringType })) { entry ->
                        val id = entry.arguments?.getString("toolId") ?: return@composable
                        ToolDetailScreen(
                            toolId = id,
                            onBack = {
                                markNavigationBusy()
                                nav.popBackStack()
                            },
                            vm = libraryVm,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        composable(Routes.SHOWCASE) {
                            UiShowcaseScreen(
                                onBack = {
                                    markNavigationBusy()
                                    nav.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    duplicateImportReport?.let { matches ->
        DuplicateImportReviewSheet(
            matches = matches,
            onDismiss = Round4DuplicateImportAudit::dismiss,
            onConfirmSkip = libraryVm::confirmDuplicateSkip,
            onImportAnyway = libraryVm::importDuplicateAnyway,
        )
    }
}
