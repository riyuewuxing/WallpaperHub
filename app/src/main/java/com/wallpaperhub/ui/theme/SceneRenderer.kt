package com.wallpaperhub.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class SceneParallax(val x: Float, val y: Float)

@Composable
internal fun WallpaperSceneCanvas(
    profile: ThemeProfile,
    sceneStrength: Float,
    particleStrength: Float,
    contourStrength: Float,
    targetFps: Int,
    detailScale: Float,
    modifier: Modifier = Modifier,
) {
    val visuals = LocalWallpaperVisuals.current
    val context = LocalContext.current
    val lifecycleActive = rememberLifecycleActive()
    val motionEnabled = profile.backgroundMotionLevel > 0 && profile.performanceMode > 0 && lifecycleActive
    var seconds by remember(profile.sceneId, motionEnabled) { mutableFloatStateOf(0f) }

    LaunchedEffect(profile.sceneId, motionEnabled, targetFps) {
        if (!motionEnabled) {
            seconds = 0f
            return@LaunchedEffect
        }
        val fps = targetFps.coerceIn(15, 60)
        val intervalNanos = 1_000_000_000L / fps
        var origin = 0L
        var lastPublished = 0L
        while (true) {
            withFrameNanos { frame ->
                if (origin == 0L) origin = frame
                if (lastPublished == 0L || frame - lastPublished >= intervalNanos) {
                    lastPublished = frame
                    seconds = (frame - origin) / 1_000_000_000f
                }
            }
        }
    }

    val parallax = rememberSceneParallax(
        context = context,
        enabled = profile.parallaxLevel > 0 && profile.performanceMode > 0 && lifecycleActive,
        level = profile.parallaxLevel,
    )

    Canvas(modifier) {
        val speed = when (profile.sceneSpeedLevel) { 0 -> 0.48f; 1 -> 0.72f; 3 -> 1.36f; else -> 1f }
        val t = seconds * speed
        val detail = detailScale.coerceIn(0.25f, 1f)
        val intensity = when (profile.auroraIntensityLevel) { 0 -> 0.36f; 1 -> 0.62f; 3 -> 1.18f; else -> 0.88f } * sceneStrength
        val particleScale = when (profile.performanceMode) { 0 -> 0.42f; 2 -> 1.18f; else -> 0.82f } * particleStrength * (0.72f + detail * 0.28f)

        drawRect(brush = Brush.verticalGradient(0f to visuals.background, 0.52f to visuals.backgroundMid, 1f to visuals.backgroundBottom))

        when (ScenePreset.fromId(profile.sceneId)) {
            ScenePreset.EVENT_HORIZON -> drawEventHorizon(visuals, t, intensity, particleScale, profile, parallax, detail)
            ScenePreset.ABYSS -> drawAbyss(visuals, t, intensity, particleScale, profile, parallax, detail)
            ScenePreset.SOLAR_CROWN -> drawSolarCrown(visuals, t, intensity, particleScale, profile, parallax, detail)
            ScenePreset.WHITE_DWARF -> drawWhiteDwarf(visuals, t, intensity, particleScale, profile, parallax, detail)
            ScenePreset.DESERT_ECLIPSE -> drawDesertEclipse(visuals, t, intensity, particleScale, profile, parallax, detail)
            ScenePreset.NEBULA_RIFT -> drawNebulaRift(visuals, t, intensity, particleScale, profile, parallax, detail)
        }

        drawSharedContours(visuals, t, profile, contourStrength, detail)
        drawSharedGrain(profile, detail)
        drawSharedVignette(profile)
    }
}

@Composable
private fun rememberLifecycleActive(): Boolean {
    val owner = LocalLifecycleOwner.current
    var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return active
}

@Composable
private fun rememberSceneParallax(context: Context, enabled: Boolean, level: Int): SceneParallax {
    val owner = LocalLifecycleOwner.current
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context, owner, enabled, level) {
        if (!enabled) {
            x = 0f; y = 0f
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }

        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        val gain = when (level) { 1 -> 0.018f; 2 -> 0.032f; else -> 0.048f }
        var basePitch: Float? = null
        var baseRoll: Float? = null
        var lastSensorNanos = 0L
        var registered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (lastSensorNanos != 0L && event.timestamp - lastSensorNanos < 33_000_000L) return
                lastSensorNanos = event.timestamp
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                if (basePitch == null || baseRoll == null) {
                    basePitch = orientation[1]
                    baseRoll = orientation[2]
                    return
                }
                val targetX = ((orientation[2] - (baseRoll ?: 0f)) * gain).coerceIn(-0.055f, 0.055f)
                val targetY = ((orientation[1] - (basePitch ?: 0f)) * gain).coerceIn(-0.055f, 0.055f)
                if (abs(targetX - x) > 0.0008f) x += (targetX - x) * 0.16f
                if (abs(targetY - y) > 0.0008f) y += (targetY - y) * 0.16f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun register() {
            if (registered) return
            basePitch = null; baseRoll = null; lastSensorNanos = 0L
            registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        fun unregister() {
            if (!registered) return
            manager.unregisterListener(listener)
            registered = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { unregister(); x = 0f; y = 0f }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose { owner.lifecycle.removeObserver(observer); unregister() }
    }
    return SceneParallax(x, y)
}

private fun DrawScope.drawEventHorizon(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    val center = Offset(size.width * (0.70f + parallax.x * 0.40f), size.height * (0.28f + parallax.y * 0.30f))
    val holeR = minSide * 0.16f
    val driftA = sin(t * 2f * PI / 8.7f).toFloat()
    val driftB = cos(t * 2f * PI / 11.4f).toFloat()
    val nebulaA = Offset(size.width * (0.18f + driftA * 0.08f), size.height * 0.18f)
    val nebulaB = Offset(size.width * (0.84f + driftB * 0.05f), size.height * 0.72f)
    drawCircle(Brush.radialGradient(listOf(v.gradientB.copy(alpha = 0.25f * intensity), Color.Transparent), nebulaA, minSide * 0.92f), minSide * 0.92f, nebulaA)
    drawCircle(Brush.radialGradient(listOf(v.gradientC.copy(alpha = 0.20f * intensity), Color.Transparent), nebulaB, minSide * 0.82f), minSide * 0.82f, nebulaB)

    repeat(if (detail > 0.55f) 3 else 2) { ring ->
        drawCircle(if (ring == 0) v.gradientB.copy(alpha = 0.16f * intensity) else v.gradientA.copy(alpha = 0.08f * intensity), holeR * (1.34f + ring * 0.15f), center, style = Stroke((2.2f - ring * 0.45f).dp.toPx()))
    }

    val diskRings = (5 + 9 * detail).toInt().coerceIn(5, 14)
    val spin = t * 0.95f
    repeat(diskRings) { index ->
        val p = if (diskRings == 1) 0f else index / (diskRings - 1f)
        val phase = spin + index * 0.42f
        val width = holeR * (3.35f - p * 0.72f)
        val height = holeR * (0.54f + p * 0.10f)
        val shift = sin(phase).toFloat() * holeR * 0.14f
        val alpha = (0.045f + (1f - p) * 0.075f) * intensity
        drawOval(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, v.gradientA.copy(alpha = alpha * 0.55f), Color(0xFFFFE0A4).copy(alpha = alpha * 1.45f), v.gradientA.copy(alpha = alpha), Color.Transparent), center.x - width, center.x + width),
            topLeft = Offset(center.x - width / 2f + shift, center.y - height / 2f), size = Size(width, height),
            style = Stroke((1.4f + (index % 3) * 0.7f).dp.toPx()),
        )
    }
    drawCircle(Color.Black.copy(alpha = 0.98f), holeR, center)
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.2f), v.gradientA.copy(alpha = 0.18f * intensity), Color.Transparent), center, holeR * 1.26f), holeR * 1.26f, center)
    drawSceneParticles(t, (54 * particles).toInt(), particleSpeed(profile), listOf(v.gradientB, v.gradientC, Color(0xFFEAF7FF)), ParticleFlow.ORBIT, center)
    drawOccasionalEvent(t, profile.eventLevel, v.gradientA, 0.16f, seed = 11)
}

private fun DrawScope.drawAbyss(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    val shaftCount = (2 + 3 * detail).toInt().coerceIn(2, 5)
    repeat(shaftCount) { index ->
        val drift = sin(t * 2f * PI / (7.2f + index * 0.9f) + index).toFloat()
        val topX = size.width * (0.06f + index * (0.88f / max(1, shaftCount - 1)) + parallax.x * 0.30f)
        val path = Path().apply {
            moveTo(topX - minSide * 0.06f, -20f); lineTo(topX + minSide * (0.08f + drift * 0.02f), -20f)
            lineTo(topX + minSide * (0.27f + drift * 0.05f), size.height); lineTo(topX - minSide * (0.14f - drift * 0.03f), size.height); close()
        }
        drawPath(path, Brush.verticalGradient(listOf(v.gradientA.copy(alpha = 0.11f * intensity), v.gradientB.copy(alpha = 0.045f * intensity), Color.Transparent)))
    }
    val pulse = 0.78f + 0.22f * sin(t * 2f * PI / 6.5f).toFloat()
    val hazeCenter = Offset(size.width * (0.72f + parallax.x * 0.2f), size.height * 0.68f)
    drawCircle(Brush.radialGradient(listOf(v.gradientA.copy(alpha = 0.18f * intensity * pulse), Color.Transparent), hazeCenter, minSide * 0.78f), minSide * 0.78f, hazeCenter)

    val jellyCount = (1 + 2 * detail).toInt().coerceIn(1, 3)
    repeat(jellyCount) { index ->
        val x = size.width * (0.22f + index * 0.31f) + sin(t / (2.8f + index) + index).toFloat() * 18.dp.toPx()
        val y = size.height * (0.44f + index * 0.19f) - ((t * (3.2f + index).dp.toPx()) % (size.height * 0.26f))
        val r = (7f + index * 2.2f).dp.toPx()
        drawCircle(v.gradientA.copy(alpha = 0.09f * intensity), r, Offset(x, y))
        if (detail > 0.55f) repeat(3) { tentacle ->
            val path = Path().apply {
                moveTo(x + (tentacle - 1) * r * 0.38f, y + r * 0.5f)
                cubicTo(x + (tentacle - 1) * r * 0.2f, y + r * 1.8f, x + sin(t + tentacle).toFloat() * r, y + r * 2.1f, x + (tentacle - 1) * r * 0.24f, y + r * 3.1f)
            }
            drawPath(path, v.gradientA.copy(alpha = 0.055f * intensity), style = Stroke(0.8.dp.toPx()))
        }
    }
    drawSceneParticles(t, (68 * particles).toInt(), particleSpeed(profile), listOf(v.gradientA, v.gradientB, Color(0xFFA7F7FF)), ParticleFlow.RISE, Offset(size.width * 0.5f, size.height * 0.5f))
    drawOccasionalEvent(t, profile.eventLevel, v.gradientA, 0.76f, bubbleMode = true, seed = 23)
}

private fun DrawScope.drawSolarCrown(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    // Keep the solar subject in the UI safe zone margin: only part of the disk enters the screen.
    val center = Offset(size.width * (1.02f + parallax.x * 0.16f), size.height * (0.11f + parallax.y * 0.12f))
    val sunR = minSide * 0.225f
    val pulse = 0.95f + 0.05f * sin(t * 2f * PI / 5.8f).toFloat()
    drawCircle(Brush.radialGradient(listOf(v.gradientC.copy(alpha = 0.17f * intensity), v.gradientA.copy(alpha = 0.11f * intensity), Color.Transparent), center, sunR * 2.6f), sunR * 2.6f, center)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C8), v.gradientA, v.gradientB), center, sunR), sunR * pulse, center)

    val coronaCount = (14 + 28 * detail).toInt().coerceIn(14, 42)
    repeat(coronaCount) { index ->
        val angle = index / coronaCount.toFloat() * 2f * PI + sin(t / 3.4f + index).toFloat() * 0.025f
        val inner = sunR * 1.02f
        val outer = sunR * (1.10f + 0.16f * abs(sin(index * 2.17 + t * 0.65)).toFloat())
        val a = Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner)
        val b = Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer)
        drawLine(v.gradientA.copy(alpha = 0.20f * intensity), a, b, strokeWidth = 1.1.dp.toPx(), cap = StrokeCap.Round)
    }
    if (detail > 0.45f) {
        val flare = Path().apply {
            moveTo(center.x - sunR * 0.18f, center.y + sunR * 0.78f)
            cubicTo(center.x - sunR * 0.92f, center.y + sunR * 0.56f, center.x - sunR * 0.74f, center.y - sunR * 0.28f, center.x - sunR * 0.30f, center.y - sunR * 0.42f)
        }
        drawPath(flare, v.gradientA.copy(alpha = 0.16f * intensity), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
    drawSceneParticles(t, (46 * particles).toInt(), particleSpeed(profile) * 1.12f, listOf(v.gradientA, v.gradientB, Color(0xFFFFE8B2)), ParticleFlow.RADIATE, center)
    drawOccasionalEvent(t, profile.eventLevel, v.gradientA, 0.38f, seed = 37)
}

private fun DrawScope.drawWhiteDwarf(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    val center = Offset(size.width * (0.32f + parallax.x * 0.35f), size.height * (0.30f + parallax.y * 0.28f))
    val starR = minSide * 0.105f
    val pulse = 0.92f + sin(t * 2f * PI / 4.6f).toFloat() * 0.08f
    val haloCount = (2 + 2 * detail).toInt().coerceIn(2, 4)
    repeat(haloCount) { ring ->
        val radius = starR * (2.5f + ring * 1.2f)
        drawCircle(Brush.radialGradient(listOf(v.gradientB.copy(alpha = (0.16f - ring * 0.025f) * intensity), Color.Transparent), center, radius), radius, center)
    }
    drawCircle(Color.White.copy(alpha = 0.96f), starR * pulse, center)
    drawCircle(v.gradientA.copy(alpha = 0.56f * intensity), starR * 1.22f, center, style = Stroke(2.dp.toPx()))
    val orbitCount = (1 + 2 * detail).toInt().coerceIn(1, 3)
    repeat(orbitCount) { index ->
        val w = minSide * (0.34f + index * 0.11f); val h = w * (0.20f + index * 0.025f)
        val offset = sin(t * (0.7f + index * 0.11f) + index).toFloat() * 8.dp.toPx()
        drawOval(v.gradientB.copy(alpha = (0.10f - index * 0.016f) * intensity), Offset(center.x - w / 2f + offset, center.y - h / 2f), Size(w, h), style = Stroke((1.2f - index * 0.2f).dp.toPx()))
    }
    drawSceneParticles(t, (60 * particles).toInt(), particleSpeed(profile) * 1.5f, listOf(Color.White, v.gradientB, v.gradientC), ParticleFlow.STREAK, center)
    drawOccasionalEvent(t, profile.eventLevel, v.gradientB, 0.58f, seed = 41)
}

private fun DrawScope.drawDesertEclipse(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    val eclipse = Offset(size.width * (0.78f + parallax.x * 0.14f), size.height * (0.18f + parallax.y * 0.12f))
    val eclipseR = minSide * 0.12f
    drawCircle(Brush.radialGradient(listOf(v.gradientA.copy(alpha = 0.55f * intensity), v.gradientB.copy(alpha = 0.12f * intensity), Color.Transparent), eclipse, eclipseR * 2.4f), eclipseR * 2.4f, eclipse)
    drawCircle(v.gradientA.copy(alpha = 0.92f), eclipseR * 1.06f, eclipse); drawCircle(Color(0xFF070607), eclipseR, eclipse)
    val duneColors = listOf(Color(0xFF5D2D18), Color(0xFF3F2116), Color(0xFF241616))
    val duneCount = if (detail < 0.45f) 2 else 3
    repeat(duneCount) { layer ->
        val yBase = size.height * (0.68f + layer * 0.09f)
        val drift = sin(t * 2f * PI / (16f + layer * 3f) + layer).toFloat() * 10.dp.toPx()
        val path = Path().apply {
            moveTo(-20f, size.height); lineTo(-20f, yBase)
            cubicTo(size.width * 0.22f, yBase - minSide * (0.16f - layer * 0.025f) + drift, size.width * 0.46f, yBase + minSide * 0.05f, size.width * 0.72f, yBase - minSide * (0.10f + layer * 0.012f) - drift)
            cubicTo(size.width * 0.88f, yBase - minSide * 0.05f, size.width * 1.05f, yBase + minSide * 0.04f, size.width + 20f, yBase)
            lineTo(size.width + 20f, size.height); close()
        }
        drawPath(path, duneColors[layer].copy(alpha = 0.92f))
    }
    drawSceneParticles(t, (56 * particles).toInt(), particleSpeed(profile) * 1.18f, listOf(v.gradientA, v.gradientB, Color(0xFFFFD29A)), ParticleFlow.WIND, eclipse)
    drawOccasionalEvent(t, profile.eventLevel, v.gradientA, 0.54f, seed = 53)
}

private fun DrawScope.drawNebulaRift(v: WallpaperVisuals, t: Float, intensity: Float, particles: Float, profile: ThemeProfile, parallax: SceneParallax, detail: Float) {
    val minSide = min(size.width, size.height)
    val a = sin(t * 2f * PI / 7.6f).toFloat(); val b = cos(t * 2f * PI / 10.8f).toFloat()
    val aCenter = Offset(size.width * (0.18f + a * 0.09f + parallax.x * 0.2f), size.height * 0.30f)
    val bCenter = Offset(size.width * (0.80f + b * 0.08f + parallax.x * 0.16f), size.height * 0.62f)
    val cCenter = Offset(size.width * 0.48f, size.height * (0.84f + a * 0.05f))
    drawCircle(Brush.radialGradient(listOf(v.gradientA.copy(alpha = 0.28f * intensity), Color.Transparent), aCenter, minSide * 0.88f), minSide * 0.88f, aCenter)
    drawCircle(Brush.radialGradient(listOf(v.gradientB.copy(alpha = 0.26f * intensity), Color.Transparent), bCenter, minSide * 0.92f), minSide * 0.92f, bCenter)
    if (detail > 0.42f) drawCircle(Brush.radialGradient(listOf(v.gradientC.copy(alpha = 0.18f * intensity), Color.Transparent), cCenter, minSide * 0.70f), minSide * 0.70f, cCenter)
    val rift = Path().apply {
        moveTo(size.width * (0.18f + parallax.x * 0.12f), -20f)
        cubicTo(size.width * (0.58f + a * 0.05f), size.height * 0.24f, size.width * (0.32f + b * 0.04f), size.height * 0.54f, size.width * (0.68f + a * 0.03f), size.height + 20f)
    }
    drawPath(rift, v.gradientA.copy(alpha = 0.075f * intensity), style = Stroke(minSide * (0.13f + 0.09f * detail), cap = StrokeCap.Round))
    if (detail > 0.50f) drawPath(rift, Color.White.copy(alpha = 0.14f * intensity), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    drawSceneParticles(t, (66 * particles).toInt(), particleSpeed(profile), listOf(v.gradientA, v.gradientB, v.gradientC, Color.White), ParticleFlow.DRIFT, Offset(size.width * 0.5f, size.height * 0.5f))
    drawOccasionalEvent(t, profile.eventLevel, v.gradientC, 0.24f, seed = 67)
}

private enum class ParticleFlow { DRIFT, RISE, WIND, ORBIT, RADIATE, STREAK }

private fun DrawScope.drawSceneParticles(t: Float, count: Int, speedMultiplier: Float, palette: List<Color>, flow: ParticleFlow, anchor: Offset) {
    if (count <= 0) return
    repeat(count) { index ->
        val depth = when { index < count * 0.52f -> 0; index < count * 0.86f -> 1; else -> 2 }
        val seedA = fract(index * 0.6180339f + 0.17f); val seedB = fract(index * 0.381966f + 0.43f)
        val baseSpeed = (0.018f + depth * 0.014f) * speedMultiplier
        val wave = sin(t * (0.6f + depth * 0.22f) + index * 1.73f).toFloat()
        var x = seedA * size.width; var y = seedB * size.height
        when (flow) {
            ParticleFlow.DRIFT -> { x = fract(seedA + t * baseSpeed * 0.16f) * size.width; y += wave * (7f + depth * 7f).dp.toPx() }
            ParticleFlow.RISE -> { y = fract(seedB - t * baseSpeed * 0.12f + 1f) * size.height; x += wave * (8f + depth * 8f).dp.toPx() }
            ParticleFlow.WIND -> { x = fract(seedA + t * baseSpeed * 0.34f) * size.width; y += sin(t * 0.9f + index).toFloat() * (5f + depth * 4f).dp.toPx() }
            ParticleFlow.ORBIT -> { val angle = seedA * 2f * PI + t * baseSpeed * (1.6f + depth); val radius = min(size.width, size.height) * (0.20f + seedB * 0.55f); x = anchor.x + cos(angle).toFloat() * radius; y = anchor.y + sin(angle).toFloat() * radius * 0.58f }
            ParticleFlow.RADIATE -> { val angle = seedA * 2f * PI; val distance = min(size.width, size.height) * fract(seedB + t * baseSpeed * 0.16f); x = anchor.x + cos(angle).toFloat() * distance; y = anchor.y + sin(angle).toFloat() * distance }
            ParticleFlow.STREAK -> { x = fract(seedA + t * baseSpeed * 0.52f) * size.width; y = fract(seedB + t * baseSpeed * 0.17f) * size.height }
        }
        val radiusDp = when (depth) { 0 -> 0.55f + (index % 3) * 0.17f; 1 -> 1.05f + (index % 4) * 0.27f; else -> 2.0f + (index % 3) * 0.62f }
        val alpha = when (depth) { 0 -> 0.13f; 1 -> 0.24f; else -> 0.38f }
        val twinkle = 0.72f + 0.28f * sin(t * (1.1f + depth * 0.35f) + index * 0.7f).toFloat()
        val color = palette[index % palette.size]
        if (depth == 2) drawCircle(color.copy(alpha = alpha * 0.15f), (radiusDp * 3.2f).dp.toPx(), Offset(x, y))
        drawCircle(color.copy(alpha = alpha * twinkle), radiusDp.dp.toPx(), Offset(x, y))
        if (flow == ParticleFlow.STREAK && depth >= 1) drawLine(color.copy(alpha = alpha * 0.35f), Offset(x - (10f + depth * 8f).dp.toPx(), y), Offset(x, y), strokeWidth = (0.5f + depth * 0.35f).dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Deterministic-but-irregular event scheduler: no short modulo loop that users can learn. */
private fun DrawScope.drawOccasionalEvent(t: Float, level: Int, color: Color, yBase: Float, bubbleMode: Boolean = false, seed: Int) {
    if (level <= 0) return
    val segment = when (level) { 1 -> 7.2f; 2 -> 5.8f; else -> 4.6f }
    val segmentIndex = kotlin.math.floor(t / segment).toInt()
    val local = t - segmentIndex * segment
    val chance = fract(sin((segmentIndex * 12.9898f + seed * 7.233f)).toFloat() * 43758.5453f)
    val threshold = when (level) { 1 -> 0.68f; 2 -> 0.48f; else -> 0.30f }
    if (chance < threshold) return
    val offset = 0.55f + fract(sin((segmentIndex * 4.123f + seed)).toFloat() * 21942.33f) * (segment - 1.8f)
    val duration = if (bubbleMode) 1.15f else 0.82f
    if (local < offset || local > offset + duration) return
    val p = ((local - offset) / duration).coerceIn(0f, 1f)
    if (bubbleMode) {
        val x = size.width * (0.18f + 0.64f * p); val y = size.height * (0.86f - 0.52f * p)
        repeat(4) { i -> drawCircle(color.copy(alpha = (1f - p) * 0.18f), (2.0f + i).dp.toPx(), Offset(x + i * 9.dp.toPx(), y + sin(i + t).toFloat() * 7.dp.toPx())) }
    } else {
        val start = Offset(size.width * (0.94f - p * 0.62f), size.height * (yBase + p * 0.20f)); val end = Offset(start.x + 74.dp.toPx(), start.y - 46.dp.toPx())
        drawLine(color.copy(alpha = (1f - p) * 0.46f), start, end, 1.5.dp.toPx(), StrokeCap.Round); drawCircle(color.copy(alpha = (1f - p) * 0.70f), 2.2.dp.toPx(), start)
    }
}

private fun DrawScope.drawSharedContours(v: WallpaperVisuals, t: Float, profile: ThemeProfile, strength: Float, detail: Float) {
    if (profile.contourLevel <= 0) return
    val alpha = (0.018f + profile.contourLevel * 0.012f) * strength
    val count = (1 + 2 * detail).toInt().coerceIn(1, 3)
    repeat(count) { index ->
        val path = Path().apply {
            moveTo(-size.width * 0.08f, size.height * (0.44f + index * 0.08f))
            cubicTo(size.width * 0.24f, size.height * (0.22f + index * 0.07f + sin(t / 7f + index).toFloat() * 0.025f), size.width * 0.62f, size.height * (0.64f + index * 0.035f), size.width * 1.08f, size.height * (0.34f + index * 0.06f))
        }
        drawPath(path, v.contour.copy(alpha = alpha), style = Stroke((0.65f + index * 0.22f).dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawSharedGrain(profile: ThemeProfile, detail: Float) {
    if (profile.grainLevel <= 0 || profile.performanceMode <= 0 || detail < 0.42f) return
    val count = ((44 + profile.grainLevel * 20) * detail).toInt().coerceIn(24, 100)
    val alpha = 0.012f + profile.grainLevel * 0.004f
    repeat(count) { index ->
        val x = fract(index * 0.7331f + 0.11f) * size.width; val y = fract(index * 0.4177f + 0.37f) * size.height
        drawCircle(Color.White.copy(alpha = alpha), (if (index % 4 == 0) 0.62f else 0.38f).dp.toPx(), Offset(x, y))
    }
}

private fun DrawScope.drawSharedVignette(profile: ThemeProfile) {
    if (profile.vignetteLevel <= 0) return
    val alpha = 0.06f + profile.vignetteLevel * 0.028f
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = alpha)), Offset(size.width * 0.50f, size.height * 0.46f), max(size.width, size.height) * 0.78f))
}

private fun particleSpeed(profile: ThemeProfile): Float = when (profile.particleSpeedLevel) { 0 -> 0.58f; 1 -> 0.82f; 3 -> 1.48f; else -> 1.08f }
private fun fract(value: Float): Float = value - kotlin.math.floor(value)
