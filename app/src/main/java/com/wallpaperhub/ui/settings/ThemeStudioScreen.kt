package com.wallpaperhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals
import com.wallpaperhub.ui.theme.ScenePreset
import com.wallpaperhub.ui.theme.ThemeColorRole
import com.wallpaperhub.ui.theme.ThemeLevels
import com.wallpaperhub.ui.theme.ThemePreset
import com.wallpaperhub.ui.theme.ThemeProfile
import com.wallpaperhub.ui.theme.ThemeViewModel
import com.wallpaperhub.ui.theme.artworkRes

private enum class ColorCategory(val title: String) {
    SCENE("场景"), MATERIAL("材质"), TEXT("文字"), INTERACTION("交互"), STATUS("状态")
}

private val sceneColorRoles = listOf(
    ThemeColorRole.BACKGROUND, ThemeColorRole.BACKGROUND_MID, ThemeColorRole.BACKGROUND_BOTTOM,
    ThemeColorRole.GRADIENT_A, ThemeColorRole.GRADIENT_B, ThemeColorRole.GRADIENT_C, ThemeColorRole.CONTOUR,
)
private val materialRoles = listOf(ThemeColorRole.SURFACE, ThemeColorRole.SURFACE_HIGH, ThemeColorRole.NAVIGATION, ThemeColorRole.STROKE)
private val textRoles = listOf(ThemeColorRole.TEXT_PRIMARY, ThemeColorRole.TEXT_SECONDARY)
private val interactionRoles = listOf(
    ThemeColorRole.ACCENT, ThemeColorRole.PRIMARY_BUTTON, ThemeColorRole.PRIMARY_BUTTON_CONTENT,
    ThemeColorRole.SECONDARY_BUTTON, ThemeColorRole.SELECTED, ThemeColorRole.FOCUS, ThemeColorRole.GLOW,
    ThemeColorRole.RIPPLE, ThemeColorRole.PARTICLE_PRIMARY, ThemeColorRole.PARTICLE_SECONDARY,
)
private val statusRoles = listOf(ThemeColorRole.SUCCESS, ThemeColorRole.WARNING, ThemeColorRole.ERROR)

@Composable
fun ThemeStudioScreen(
    onBack: () -> Unit,
    vm: ThemeViewModel,
    onSceneBusyChange: (Boolean) -> Unit = {},
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val visuals = LocalWallpaperVisuals.current
    var category by remember { mutableStateOf(ColorCategory.SCENE) }
    var selectedRole by remember { mutableStateOf(sceneColorRoles.first()) }

    LaunchedEffect(category) { selectedRole = rolesFor(category).first() }
    DisposableEffect(Unit) {
        onDispose {
            vm.cancelColorPreview()
            onSceneBusyChange(false)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = visuals.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text("主题与场景", style = MaterialTheme.typography.headlineLarge, color = visuals.textPrimary)
                    Text("当前页面本身就是实时预览，不再重复渲染第二套场景", style = MaterialTheme.typography.bodyMedium, color = visuals.textSecondary)
                }
            }
        }

        item { CurrentSceneSummary(profile) }

        item {
            ThemeSectionTitle("Scene", "选择 App 所处的动态世界。卡片直接预览实际基地美术；场景不修改任何媒体像素。")
            LazyRow(contentPadding = PaddingValues(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ScenePreset.entries, key = { it.id }) { scene ->
                    SceneCard(scene, profile.sceneId == scene.id) { vm.applyScene(scene) }
                }
            }
        }

        item {
            StudioPanel("动态世界", "基地美术保持清晰；粒子、日冕、吸积盘、流线与偶发事件作为实时覆盖层运行。") {
                LevelSelector("场景动态", profile.backgroundMotionLevel, ThemeLevels.motion, vm::updateBackgroundMotion)
                LevelSelector("场景速度", profile.sceneSpeedLevel, ThemeLevels.speed, vm::updateSceneSpeed)
                LevelSelector("场景强度", profile.auroraIntensityLevel, ThemeLevels.intensity, vm::updateAuroraIntensity)
                LevelSelector("粒子数量", profile.ambientParticleLevel, ThemeLevels.particles, vm::updateAmbientParticles)
                LevelSelector("粒子速度", profile.particleSpeedLevel, ThemeLevels.speed, vm::updateParticleSpeed)
                LevelSelector("设备视差", profile.parallaxLevel, ThemeLevels.parallax, vm::updateParallax)
                LevelSelector("偶发事件", profile.eventLevel, ThemeLevels.events, vm::updateEvents)
                LevelSelector("空间流线", profile.contourLevel, ThemeLevels.intensity, vm::updateContour)
                LevelSelector("颗粒质感", profile.grainLevel, ThemeLevels.intensity, vm::updateGrain)
                LevelSelector("边缘暗角", profile.vignetteLevel, ThemeLevels.intensity, vm::updateVignette)
            }
        }

        item {
            ThemeSectionTitle("Chrome", "Chrome 与 Scene 独立；修改 Scene 色不会再把 Chrome 预设误标成自定义。")
            LazyRow(contentPadding = PaddingValues(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ThemePreset.entries.filter { it != ThemePreset.CUSTOM }, key = { it.id }) { preset ->
                    FilterChip(selected = profile.presetId == preset.id, onClick = { vm.applyPreset(preset) }, label = { Text(preset.title) })
                }
            }
        }

        item {
            StudioPanel("交互与性能", "交互特效只作用于 UI/Scene，不对图片和视频做 Tint。") {
                LevelSelector("点击特效", profile.interactionEffectLevel, ThemeLevels.interaction, vm::updateInteractionEffects)
                LevelSelector("触觉反馈", profile.hapticLevel, ThemeLevels.haptics, vm::updateHaptics)
                LevelSelector("性能模式", profile.performanceMode, ThemeLevels.performance, vm::updatePerformanceMode)
            }
        }

        item {
            ThemeSectionTitle("Color Studio", "拖动即时预览；松手才写入一次。Scene、文字和语义色不会被设成透明。")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 10.dp)) {
                items(ColorCategory.entries, key = { it.name }) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item.title) })
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rolesFor(category), key = { it.key }) { role ->
                    ColorRoleChip(role, Color(profile.color(role)), selectedRole == role) {
                        vm.cancelColorPreview()
                        selectedRole = role
                    }
                }
            }
        }

        item {
            ThemeColorPicker(
                role = selectedRole,
                argb = profile.color(selectedRole),
                onPreview = { vm.previewColor(selectedRole, it) },
                onCommit = { vm.commitColor(selectedRole, it) },
                onDragState = onSceneBusyChange,
            )
        }

        item {
            Surface(
                shape = WallpaperTokens.ShapeLg,
                color = visuals.surfaceHigh.copy(alpha = 0.76f),
                contentColor = visuals.textPrimary,
                border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.66f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("场景安全规则", style = MaterialTheme.typography.titleMedium, color = visuals.textPrimary)
                    Text(
                        "基地美术和动态 Scene 永远位于媒体之后；小姐姐图片与视频保持原色。高亮天体避开主要 UI 安全区，文字只用局部 Scrim/Glass 保护。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = visuals.textSecondary,
                    )
                    Button(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("恢复默认 Scene Theme") }
                }
            }
        }
    }
}

@Composable
private fun CurrentSceneSummary(profile: ThemeProfile) {
    val visuals = LocalWallpaperVisuals.current
    val scene = ScenePreset.fromId(profile.sceneId)
    Surface(
        shape = WallpaperTokens.ShapeLg,
        color = visuals.surface.copy(alpha = 0.50f),
        contentColor = visuals.textPrimary,
        border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.42f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(modifier = Modifier.size(46.dp), shape = CircleShape, color = visuals.accent.copy(alpha = 0.20f), contentColor = visuals.accent) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Palette, contentDescription = null) }
            }
            Column(Modifier.weight(1f)) {
                Text(scene.title, style = MaterialTheme.typography.titleMedium, color = visuals.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(scene.summary, style = MaterialTheme.typography.bodySmall, color = visuals.textSecondary)
            }
            Text("LIVE", color = visuals.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SceneCard(scene: ScenePreset, selected: Boolean, onClick: () -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 154.dp, height = 116.dp),
        shape = WallpaperTokens.ShapeLg,
        color = Color.Transparent,
        contentColor = visuals.textPrimary,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) visuals.selected else visuals.stroke.copy(alpha = 0.50f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = scene.artworkRes(),
                contentDescription = scene.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.06f),
                            0.52f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(scene.title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(scene.summary, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f), maxLines = 2)
            }
        }
    }
}

@Composable
private fun ColorRoleChip(role: ThemeColorRole, color: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(modifier = Modifier.size(18.dp), shape = CircleShape, color = color, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))) {}
                Text(role.title)
            }
        },
    )
}

@Composable
private fun StudioPanel(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    Surface(
        shape = WallpaperTokens.ShapeLg,
        color = visuals.surface.copy(alpha = 0.62f),
        contentColor = visuals.textPrimary,
        border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.50f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = visuals.textPrimary)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = visuals.textSecondary)
            content()
        }
    }
}

@Composable
private fun ThemeSectionTitle(title: String, summary: String) {
    val visuals = LocalWallpaperVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = visuals.textPrimary)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = visuals.textSecondary)
    }
}

@Composable
private fun LevelSelector(title: String, selected: Int, options: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = visuals.textPrimary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(options, key = { it.first }) { option ->
                FilterChip(selected = selected == option.first, onClick = { onSelect(option.first) }, label = { Text(option.second) })
            }
        }
    }
}

private fun rolesFor(category: ColorCategory): List<ThemeColorRole> = when (category) {
    ColorCategory.SCENE -> sceneColorRoles
    ColorCategory.MATERIAL -> materialRoles
    ColorCategory.TEXT -> textRoles
    ColorCategory.INTERACTION -> interactionRoles
    ColorCategory.STATUS -> statusRoles
}
