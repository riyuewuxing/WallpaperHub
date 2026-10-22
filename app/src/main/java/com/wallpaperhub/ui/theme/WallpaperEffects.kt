package com.wallpaperhub.ui.theme

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class TapBurst(val x: Float, val y: Float, val startedAtNanos: Long)

enum class WallpaperScene(
    val world: Float,
    val particles: Float,
    val contours: Float,
    val targetFps: Int,
    val detailScale: Float,
) {
    LIBRARY(0.82f, 0.76f, 0.72f, 60, 0.72f),
    FLOW(1.00f, 1.00f, 1.00f, 60, 1.00f),
    DETAIL(0.72f, 0.58f, 0.66f, 45, 0.62f),
    PREVIEW(0.40f, 0.22f, 0.30f, 30, 0.34f),
    SETTINGS(0.62f, 0.46f, 0.50f, 45, 0.50f),
    THEME_STUDIO(1.00f, 0.90f, 0.90f, 60, 0.82f),
    UTILITY(0.55f, 0.36f, 0.42f, 30, 0.42f),
}

@Composable
fun WallpaperBackdrop(
    profile: ThemeProfile,
    scene: WallpaperScene = WallpaperScene.LIBRARY,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable () -> Unit,
) {
    val visuals = LocalWallpaperVisuals.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val bursts = remember { mutableStateListOf<TapBurst>() }
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    var gestureBusy by remember { mutableStateOf(false) }
    var gestureReleaseEpoch by remember { mutableLongStateOf(0L) }

    val animatorScale = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
    }
    val systemMotionEnabled = animatorScale > 0f
    val renderBusy = busy || gestureBusy
    val effectiveMotion = if (!systemMotionEnabled) 0 else when (profile.performanceMode) {
        0 -> profile.backgroundMotionLevel.coerceAtMost(1)
        else -> profile.backgroundMotionLevel
    }
    val effectiveAmbient = if (!systemMotionEnabled) 0 else when (profile.performanceMode) {
        0 -> profile.ambientParticleLevel.coerceAtMost(1)
        1 -> profile.ambientParticleLevel.coerceAtMost(2)
        else -> profile.ambientParticleLevel
    }
    val effectiveInteraction = if (!systemMotionEnabled || !interactionEnabled || busy) 0 else when (profile.performanceMode) {
        0 -> profile.interactionEffectLevel.coerceAtMost(1)
        else -> profile.interactionEffectLevel
    }

    val worldStrength by animateFloatAsState(scene.world, tween(620), label = "scene_world")
    val particleStrength by animateFloatAsState(scene.particles, tween(620), label = "scene_particles")
    val contourStrength by animateFloatAsState(scene.contours, tween(620), label = "scene_contours")

    LaunchedEffect(bursts.size) {
        while (bursts.isNotEmpty()) {
            nowNanos = SystemClock.elapsedRealtimeNanos()
            bursts.removeAll { nowNanos - it.startedAtNanos > 760_000_000L }
            delay(16)
        }
    }

    LaunchedEffect(gestureReleaseEpoch) {
        if (gestureReleaseEpoch == 0L) return@LaunchedEffect
        delay(260)
        gestureBusy = false
    }

    val tapSlopPx = with(density) { 18.dp.toPx() }
    val tapObserver = if (!interactionEnabled || busy) Modifier else Modifier.pointerInput(effectiveInteraction, profile.hapticLevel) {
        val downPositions = mutableMapOf<Long, Offset>()
        val dragged = mutableSetOf<Long>()
        val consumedGestures = mutableSetOf<Long>()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                event.changes.forEach { change ->
                    val id = change.id.value
                    if (!change.previousPressed && change.pressed) {
                        downPositions[id] = change.position
                        dragged.remove(id)
                        if (change.isConsumed) consumedGestures += id else consumedGestures.remove(id)
                    } else if (change.pressed) {
                        if (change.isConsumed) consumedGestures += id
                        val down = downPositions[id]
                        if (down != null) {
                            val dx = change.position.x - down.x
                            val dy = change.position.y - down.y
                            if (dx * dx + dy * dy > tapSlopPx * tapSlopPx) {
                                // Lazy lists consume their scroll delta before the Final pass. Keep
                                // observing that consumed movement so real gallery scrolling still
                                // throttles the Scene, while the consumed flag only suppresses TapBurst.
                                dragged += id
                                gestureBusy = true
                            }
                        }
                    } else if (change.previousPressed && !change.pressed) {
                        if (change.isConsumed) consumedGestures += id
                        val down = downPositions.remove(id)
                        val wasDrag = dragged.remove(id)
                        val wasConsumed = consumedGestures.remove(id)
                        if (wasDrag) {
                            gestureBusy = true
                            gestureReleaseEpoch = SystemClock.elapsedRealtimeNanos()
                        } else if (down != null && !wasConsumed) {
                            val dx = change.position.x - down.x
                            val dy = change.position.y - down.y
                            if (dx * dx + dy * dy <= tapSlopPx * tapSlopPx) {
                                if (effectiveInteraction > 0) bursts += TapBurst(change.position.x, change.position.y, SystemClock.elapsedRealtimeNanos())
                                when (profile.hapticLevel) {
                                    1 -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    2 -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val sceneProfile = profile.copy(
        backgroundMotionLevel = effectiveMotion,
        ambientParticleLevel = effectiveAmbient,
        parallaxLevel = if (systemMotionEnabled && !renderBusy) profile.parallaxLevel else 0,
        eventLevel = if (systemMotionEnabled && !renderBusy) profile.eventLevel else 0,
    )
    val targetFps = if (renderBusy) minOf(scene.targetFps, 30) else scene.targetFps
    val detailScale = scene.detailScale * if (renderBusy) 0.54f else 1f
    val proceduralOverlayAlpha = ((0.22f + worldStrength * 0.20f) * if (renderBusy) 0.72f else 1f)
        .coerceIn(0.24f, 0.42f)
    val effectiveParticleStrength = particleStrength * when (effectiveAmbient) {
        0 -> 0f
        1 -> 0.52f
        2 -> 1f
        else -> 1.34f
    } * if (renderBusy) 0.55f else 1f
    // Dynamic renderer must not pay for static grain/vignette each tick.
    val dynamicProfile = sceneProfile.copy(grainLevel = 0, vignetteLevel = 0)
    val scenePreset = ScenePreset.fromId(sceneProfile.sceneId)

    Box(modifier = modifier.fillMaxSize().then(tapObserver)) {
        // Artwork is the Scene's visual foundation. It stays independent from user wallpaper media.
        WallpaperSceneArtwork(
            profile = sceneProfile,
            sceneStrength = worldStrength,
            modifier = Modifier.fillMaxSize(),
        )

        // White Dwarf's raster art already owns the hero star. The old procedural renderer drew a
        // second star on the left, so this scene uses a dust-only motion layer instead.
        if (scenePreset == ScenePreset.WHITE_DWARF) {
            WhiteDwarfAmbientCanvas(
                particleStrength = effectiveParticleStrength,
                targetFps = targetFps,
                detailScale = detailScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = proceduralOverlayAlpha },
            )
        } else {
            // Existing procedural engine becomes a translucent dynamic overlay: particles, corona,
            // accretion motion, light shafts, contours and irregular events remain alive above the art.
            WallpaperSceneCanvas(
                profile = dynamicProfile,
                sceneStrength = worldStrength,
                particleStrength = effectiveParticleStrength,
                contourStrength = contourStrength * if (renderBusy) 0.60f else 1f,
                targetFps = targetFps,
                detailScale = detailScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = proceduralOverlayAlpha },
            )
        }

        WallpaperStaticAtmosphere(
            profile = sceneProfile,
            detailScale = detailScale,
            modifier = Modifier.fillMaxSize(),
        )

        // Local contrast protection: keep status/title and dock zones readable without dimming the
        // artwork's entire middle field.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.31f),
                    0.14f to Color.Black.copy(alpha = 0.06f),
                    0.30f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.25f),
                ),
            )
        }

        if (bursts.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                bursts.forEach { burst ->
                    val age = ((nowNanos - burst.startedAtNanos).coerceAtLeast(0L) / 760_000_000f).coerceIn(0f, 1f)
                    val fade = 1f - age
                    val bloomRadius = 54.dp.toPx() + age * 148.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(listOf(visuals.glow.copy(alpha = fade * 0.24f), Color.Transparent), Offset(burst.x, burst.y), bloomRadius),
                        radius = bloomRadius,
                        center = Offset(burst.x, burst.y),
                    )
                }
            }
        }

        content()

        if (bursts.isNotEmpty() && effectiveInteraction > 0) {
            Canvas(Modifier.fillMaxSize()) {
                bursts.forEach { burst ->
                    val age = ((nowNanos - burst.startedAtNanos).coerceAtLeast(0L) / 760_000_000f).coerceIn(0f, 1f)
                    val fade = 1f - age
                    val origin = Offset(burst.x, burst.y)
                    val ringRadius = 18.dp.toPx() + age * 64.dp.toPx()
                    drawCircle(visuals.ripple.copy(alpha = fade * 0.40f), ringRadius, origin, style = Stroke((1.4f + fade * 1.5f).dp.toPx()))
                    if (age < 0.42f) {
                        val focusFade = 1f - age / 0.42f
                        drawCircle(visuals.focus.copy(alpha = focusFade * 0.48f), 9.dp.toPx() + age * 26.dp.toPx(), origin, style = Stroke(1.5.dp.toPx()))
                    }
                    val particleCount = when (effectiveInteraction) { 1 -> 3; 2 -> 6; else -> 10 }
                    repeat(particleCount) { index ->
                        val angle = (2.0 * PI * index / particleCount) + (index % 2) * 0.31
                        val distance = age * (34.dp.toPx() + (index % 3) * 17.dp.toPx())
                        val px = origin.x + cos(angle).toFloat() * distance
                        val py = origin.y + sin(angle).toFloat() * distance
                        val radius = (1.3f + (index % 3) * 0.55f).dp.toPx() * fade.coerceAtLeast(0.22f)
                        val color = if (index % 3 == 0) visuals.particleSecondary else visuals.particlePrimary
                        drawCircle(color.copy(alpha = fade * 0.82f), radius, Offset(px, py))
                    }
                }
            }
        }
    }
}
