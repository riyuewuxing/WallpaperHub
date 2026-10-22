package com.wallpaperhub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val UiFontFamily = FontFamily.SansSerif

private val GalleryTypography = Typography(
    displaySmall = TextStyle(fontFamily = UiFontFamily, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5f).sp),
    headlineLarge = TextStyle(fontFamily = UiFontFamily, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3f).sp),
    headlineMedium = TextStyle(fontFamily = UiFontFamily, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = UiFontFamily, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = UiFontFamily, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = UiFontFamily, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = UiFontFamily, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = UiFontFamily, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.35.sp),
)

private val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(19.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

data class WallpaperVisuals(
    val accent: Color,
    val background: Color,
    val backgroundMid: Color,
    val backgroundBottom: Color,
    val gradientA: Color,
    val gradientB: Color,
    val gradientC: Color,
    val contour: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val navigation: Color,
    val primaryButton: Color,
    val primaryButtonContent: Color,
    val secondaryButton: Color,
    val selected: Color,
    val stroke: Color,
    val focus: Color,
    val glow: Color,
    val ripple: Color,
    val particlePrimary: Color,
    val particleSecondary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

private fun contrastRatio(a: Color, b: Color): Float {
    val aa = a.copy(alpha = 1f)
    val bb = b.copy(alpha = 1f)
    val lighter = max(aa.luminance(), bb.luminance()) + 0.05f
    val darker = min(aa.luminance(), bb.luminance()) + 0.05f
    return lighter / darker
}

private fun readableAcross(preferred: Color, backgrounds: List<Color>, target: Float): Color {
    val opaquePreferred = preferred.copy(alpha = 1f)
    if (backgrounds.all { contrastRatio(opaquePreferred, it) >= target }) return opaquePreferred
    val light = Color(0xFFF7FBFB)
    val dark = Color(0xFF06100F)
    val lightFloor = backgrounds.minOf { contrastRatio(light, it) }
    val darkFloor = backgrounds.minOf { contrastRatio(dark, it) }
    return if (lightFloor >= darkFloor) light else dark
}

private fun readableForeground(background: Color, preferred: Color): Color =
    readableAcross(preferred, listOf(background.copy(alpha = 1f)), 4.5f)

private fun visualsFrom(profile: ThemeProfile): WallpaperVisuals {
    val background = Color(profile.backgroundArgb)
    val backgroundMid = Color(profile.backgroundMidArgb)
    val backgroundBottom = Color(profile.backgroundBottomArgb)
    val surface = Color(profile.surfaceArgb)
    val surfaceHigh = Color(profile.surfaceHighArgb)
    val sceneBases = listOf(background, backgroundMid, backgroundBottom).map { it.copy(alpha = 1f) }
    val effectiveSurfaces = buildList {
        sceneBases.forEach { base ->
            add(surface.compositeOver(base))
            add(surfaceHigh.compositeOver(base))
            add(Color(profile.navigationArgb).compositeOver(base))
        }
    }
    val primary = readableAcross(Color(profile.textPrimaryArgb), sceneBases + effectiveSurfaces, 7f)
    val secondary = readableAcross(Color(profile.textSecondaryArgb), sceneBases + effectiveSurfaces, 4.5f)

    return WallpaperVisuals(
        accent = Color(profile.accentArgb),
        background = background,
        backgroundMid = backgroundMid,
        backgroundBottom = backgroundBottom,
        gradientA = Color(profile.gradientAArgb),
        gradientB = Color(profile.gradientBArgb),
        gradientC = Color(profile.gradientCArgb),
        contour = Color(profile.contourArgb),
        surface = surface,
        surfaceHigh = surfaceHigh,
        textPrimary = primary,
        textSecondary = secondary,
        navigation = Color(profile.navigationArgb),
        primaryButton = Color(profile.primaryButtonArgb),
        primaryButtonContent = Color(profile.primaryButtonContentArgb),
        secondaryButton = Color(profile.secondaryButtonArgb),
        selected = Color(profile.selectedArgb),
        stroke = Color(profile.strokeArgb),
        focus = Color(profile.focusArgb),
        glow = Color(profile.glowArgb),
        ripple = Color(profile.rippleArgb),
        particlePrimary = Color(profile.particlePrimaryArgb),
        particleSecondary = Color(profile.particleSecondaryArgb),
        success = Color(profile.successArgb),
        warning = Color(profile.warningArgb),
        error = Color(profile.errorArgb),
    )
}

/** Dynamic locals track actual readers instead of invalidating the whole subtree. */
val LocalWallpaperVisuals = compositionLocalOf { visualsFrom(ThemeProfile.default()) }
val LocalWallpaperThemeProfile = compositionLocalOf { ThemeProfile.default() }

private fun themeScheme(v: WallpaperVisuals): ColorScheme = darkColorScheme(
    primary = v.primaryButton,
    onPrimary = readableForeground(v.primaryButton, v.primaryButtonContent),
    primaryContainer = v.secondaryButton,
    onPrimaryContainer = readableForeground(v.secondaryButton.copy(alpha = 1f), v.textPrimary),
    secondary = v.accent,
    onSecondary = readableForeground(v.accent, v.primaryButtonContent),
    secondaryContainer = v.surfaceHigh,
    onSecondaryContainer = v.textPrimary,
    tertiary = v.selected,
    onTertiary = readableForeground(v.selected, v.primaryButtonContent),
    tertiaryContainer = v.secondaryButton,
    onTertiaryContainer = v.textPrimary,
    background = Color.Transparent,
    onBackground = v.textPrimary,
    surface = v.surface,
    onSurface = v.textPrimary,
    surfaceVariant = v.surfaceHigh,
    onSurfaceVariant = v.textSecondary,
    outline = v.stroke,
    outlineVariant = v.stroke.copy(alpha = 0.45f),
    error = v.error,
    onError = readableForeground(v.error, Color.White),
    errorContainer = v.error.copy(alpha = 0.18f),
    onErrorContainer = Color.White,
    surfaceTint = v.accent,
)

@Composable
fun WallpaperHubTheme(
    darkTheme: Boolean = true,
    accentColor: Color? = null,
    profile: ThemeProfile = ThemeProfile.default(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val compatibilityOnly = accentColor
    @Suppress("UNUSED_VARIABLE") val compatibilityDark = darkTheme
    val visuals = visualsFrom(profile)
    CompositionLocalProvider(
        LocalWallpaperVisuals provides visuals,
        LocalWallpaperThemeProfile provides profile,
    ) {
        MaterialTheme(
            colorScheme = themeScheme(visuals),
            typography = GalleryTypography,
            shapes = GalleryShapes,
            content = content,
        )
    }
}
