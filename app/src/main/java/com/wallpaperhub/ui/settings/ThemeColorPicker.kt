package com.wallpaperhub.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.theme.LocalWallpaperVisuals
import com.wallpaperhub.ui.theme.ThemeColorRole
import com.wallpaperhub.ui.theme.ThemeRepository
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private enum class ColorModel(val label: String) { HEX("HEX"), RGB("RGB"), HSV("HSV") }

@Composable
internal fun ThemeColorPicker(
    role: ThemeColorRole,
    argb: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onDragState: (Boolean) -> Unit = {},
) {
    val visuals = LocalWallpaperVisuals.current
    var localArgb by remember(role) { mutableIntStateOf(argb) }
    var dragging by remember(role) { mutableStateOf(false) }
    var model by remember { mutableStateOf(ColorModel.HEX) }

    LaunchedEffect(argb, role, dragging) {
        if (!dragging) localArgb = argb
    }

    fun preview(next: Int) {
        val sanitized = ThemeRepository.sanitizeArgb(role, next)
        localArgb = sanitized
        onPreview(sanitized)
    }
    fun commit(next: Int) {
        val sanitized = ThemeRepository.sanitizeArgb(role, next)
        localArgb = sanitized
        onCommit(sanitized)
    }
    fun dragState(active: Boolean) {
        dragging = active
        onDragState(active)
    }

    val hsv = remember(localArgb) { FloatArray(3).also { AndroidColor.colorToHSV(localArgb, it) } }
    val alpha = AndroidColor.alpha(localArgb)

    Surface(
        shape = WallpaperTokens.ShapeLg,
        color = visuals.surface.copy(alpha = 0.72f),
        contentColor = visuals.textPrimary,
        border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.58f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = Color(localArgb), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.34f))) {}
                Column(Modifier.weight(1f)) {
                    Text(role.title, style = MaterialTheme.typography.titleMedium, color = visuals.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(role.summary, style = MaterialTheme.typography.bodySmall, color = visuals.textSecondary)
                }
                Text(argbToHex(localArgb), style = MaterialTheme.typography.labelLarge, color = visuals.textSecondary)
            }

            HueSaturationWheel(
                hue = hsv[0], saturation = hsv[1], value = hsv[2],
                onPreview = { hue, saturation -> preview(AndroidColor.HSVToColor(alpha, floatArrayOf(hue, saturation, hsv[2]))) },
                onCommit = { hue, saturation -> commit(AndroidColor.HSVToColor(alpha, floatArrayOf(hue, saturation, hsv[2]))) },
                onDragState = ::dragState,
            )

            ValueSlider(
                hue = hsv[0], saturation = hsv[1], value = hsv[2],
                onPreview = { value -> preview(AndroidColor.HSVToColor(alpha, floatArrayOf(hsv[0], hsv[1], value))) },
                onCommit = { value -> commit(AndroidColor.HSVToColor(alpha, floatArrayOf(hsv[0], hsv[1], value))) },
                onDragState = ::dragState,
            )

            if (ThemeRepository.allowsAlpha(role)) {
                AlphaSlider(
                    color = AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2])), alpha = alpha,
                    onPreview = { nextAlpha -> preview(AndroidColor.HSVToColor(nextAlpha, floatArrayOf(hsv[0], hsv[1], hsv[2]))) },
                    onCommit = { nextAlpha -> commit(AndroidColor.HSVToColor(nextAlpha, floatArrayOf(hsv[0], hsv[1], hsv[2]))) },
                    onDragState = ::dragState,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorModel.entries.forEach { item ->
                    FilterChip(selected = model == item, onClick = { model = item }, label = { Text(item.label) })
                }
            }

            when (model) {
                ColorModel.HEX -> HexEditor(localArgb, ::preview, ::commit)
                ColorModel.RGB -> RgbEditor(localArgb, ::preview, ::commit)
                ColorModel.HSV -> HsvEditor(localArgb, ::preview, ::commit)
            }

            Text("常用色", style = MaterialTheme.typography.labelLarge, color = visuals.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    0xFFFFFFFF.toInt(), 0xFF0A0D10.toInt(), visuals.accent.toArgbCompat(),
                    visuals.selected.toArgbCompat(), visuals.glow.toArgbCompat(), visuals.error.toArgbCompat(),
                ).forEach { color ->
                    Surface(
                        onClick = {
                            val next = if (ThemeRepository.allowsAlpha(role)) (alpha shl 24) or (color and 0x00FFFFFF) else color or 0xFF000000.toInt()
                            preview(next); commit(next)
                        },
                        modifier = Modifier.size(34.dp), shape = CircleShape, color = Color(color),
                        border = BorderStroke(1.dp, visuals.stroke.copy(alpha = 0.62f)),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onPreview: (Float, Float) -> Unit,
    onCommit: (Float, Float) -> Unit,
    onDragState: (Boolean) -> Unit,
) {
    val visuals = LocalWallpaperVisuals.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(262.dp)
            .drawWithCache {
                val radius = min(size.width, size.height) * 0.44f
                val center = Offset(size.width / 2f, size.height / 2f)
                val wheel = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center)
                val saturationBrush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.02f), Color.Transparent), center, radius)
                onDrawBehind {
                    drawCircle(wheel, radius, center)
                    drawCircle(saturationBrush, radius, center)
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onDragState(true)
                    var lastHue = hue
                    var lastSaturation = saturation
                    fun update(position: Offset) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = position.x - center.x
                        val dy = position.y - center.y
                        val radius = min(size.width, size.height) * 0.44f
                        val distance = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
                        var angle = atan2(dy, dx) * 180f / PI.toFloat()
                        if (angle < 0f) angle += 360f
                        lastHue = angle
                        lastSaturation = (distance / radius).coerceIn(0f, 1f)
                        onPreview(lastHue, lastSaturation)
                    }
                    update(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            onCommit(lastHue, lastSaturation)
                            change.consume()
                            break
                        }
                        update(change.position)
                        change.consume()
                    }
                    onDragState(false)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) * 0.44f
            val center = Offset(size.width / 2f, size.height / 2f)
            if (value < 1f) drawCircle(Color.Black.copy(alpha = (1f - value) * 0.86f), radius, center)
            val angle = hue / 180f * PI.toFloat()
            val marker = Offset(center.x + cos(angle) * radius * saturation, center.y + sin(angle) * radius * saturation)
            drawCircle(Color.Black.copy(alpha = 0.62f), 10.dp.toPx(), marker)
            drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
            drawCircle(visuals.focus.copy(alpha = 0.86f), 11.dp.toPx(), marker, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun ValueSlider(hue: Float, saturation: Float, value: Float, onPreview: (Float) -> Unit, onCommit: (Float) -> Unit, onDragState: (Boolean) -> Unit) {
    val full = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)))
    SliderCanvas(value, Brush.horizontalGradient(listOf(Color.Black, full)), onPreview, onCommit, onDragState)
}

@Composable
private fun AlphaSlider(color: Int, alpha: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit, onDragState: (Boolean) -> Unit) {
    SliderCanvas(
        progress = alpha / 255f,
        brush = Brush.horizontalGradient(listOf(Color(color).copy(alpha = 0f), Color(color))),
        onPreview = { onPreview((it * 255f).toInt().coerceIn(0, 255)) },
        onCommit = { onCommit((it * 255f).toInt().coerceIn(0, 255)) },
        onDragState = onDragState,
        checker = true,
    )
}

@Composable
private fun SliderCanvas(
    progress: Float,
    brush: Brush,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onDragState: (Boolean) -> Unit,
    checker: Boolean = false,
) {
    Canvas(
        Modifier.fillMaxWidth().height(34.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onDragState(true)
                var last = (down.position.x / size.width).coerceIn(0f, 1f)
                onPreview(last); down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = (change.position.x / size.width).coerceIn(0f, 1f)
                    if (!change.pressed) {
                        onCommit(last); change.consume(); break
                    }
                    onPreview(last); change.consume()
                }
                onDragState(false)
            }
        },
    ) {
        if (checker) {
            val cell = 8.dp.toPx(); var row = 0; var y = 0f
            while (y < size.height) {
                var col = 0; var x = 0f
                while (x < size.width) {
                    drawRect(if ((row + col) % 2 == 0) Color(0xFF34383D) else Color(0xFF1D2024), Offset(x, y), Size(cell, cell))
                    x += cell; col++
                }
                y += cell; row++
            }
        }
        drawRoundRect(brush = brush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        val x = size.width * progress.coerceIn(0f, 1f)
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), Offset(x, size.height / 2f))
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun HexEditor(argb: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit) {
    val visuals = LocalWallpaperVisuals.current
    var text by remember(argb) { mutableStateOf(argbToHex(argb)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(argb, focused) { if (!focused) text = argbToHex(argb) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val normalized = raw.uppercase().filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }.take(9)
            text = normalized
            parseArgb(normalized)?.let(onPreview)
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            val wasFocused = focused; focused = state.isFocused
            if (wasFocused && !focused) parseArgb(text)?.let(onCommit)
        },
        singleLine = true, label = { Text("HEX / ARGB") }, colors = pickerFieldColors(visuals),
    )
}

@Composable
private fun RgbEditor(argb: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit) {
    val a = AndroidColor.alpha(argb)
    NumberTripleEditor(
        labels = listOf("R", "G", "B"), values = listOf(AndroidColor.red(argb), AndroidColor.green(argb), AndroidColor.blue(argb)), ranges = listOf(0..255, 0..255, 0..255),
        toColor = { v -> AndroidColor.argb(a, v[0], v[1], v[2]) }, onPreview = onPreview, onCommit = onCommit,
    )
}

@Composable
private fun HsvEditor(argb: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit) {
    val hsv = FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }
    val alpha = AndroidColor.alpha(argb)
    NumberTripleEditor(
        labels = listOf("H", "S", "V"), values = listOf(hsv[0].toInt(), (hsv[1] * 100).toInt(), (hsv[2] * 100).toInt()), ranges = listOf(0..360, 0..100, 0..100),
        toColor = { v -> AndroidColor.HSVToColor(alpha, floatArrayOf(v[0].toFloat(), v[1] / 100f, v[2] / 100f)) }, onPreview = onPreview, onCommit = onCommit,
    )
}

@Composable
private fun NumberTripleEditor(
    labels: List<String>, values: List<Int>, ranges: List<IntRange>, toColor: (List<Int>) -> Int,
    onPreview: (Int) -> Unit, onCommit: (Int) -> Unit,
) {
    val visuals = LocalWallpaperVisuals.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, value ->
            var text by remember(index, value) { mutableStateOf(value.toString()) }
            var focused by remember(index) { mutableStateOf(false) }
            LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw.filter(Char::isDigit).take(3)
                    text.toIntOrNull()?.let { parsed ->
                        val next = values.toMutableList(); next[index] = parsed.coerceIn(ranges[index].first, ranges[index].last)
                        onPreview(toColor(next))
                    }
                },
                modifier = Modifier.weight(1f).onFocusChanged { state ->
                    val wasFocused = focused; focused = state.isFocused
                    if (wasFocused && !focused) text.toIntOrNull()?.let { parsed ->
                        val next = values.toMutableList(); next[index] = parsed.coerceIn(ranges[index].first, ranges[index].last)
                        onCommit(toColor(next))
                    }
                },
                singleLine = true, label = { Text(labels[index]) }, colors = pickerFieldColors(visuals),
            )
        }
    }
}

@Composable
private fun pickerFieldColors(visuals: com.wallpaperhub.ui.theme.WallpaperVisuals) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = visuals.textPrimary, unfocusedTextColor = visuals.textPrimary, cursorColor = visuals.focus,
    focusedBorderColor = visuals.focus, unfocusedBorderColor = visuals.stroke,
    focusedLabelColor = visuals.focus, unfocusedLabelColor = visuals.textSecondary,
)

private fun argbToHex(argb: Int): String = String.format("#%08X", argb)
private fun parseArgb(raw: String): Int? {
    val value = raw.removePrefix("#")
    return runCatching { when (value.length) { 6 -> (0xFF000000L or value.toLong(16)).toInt(); 8 -> value.toLong(16).toInt(); else -> return null } }.getOrNull()
}
private fun Color.toArgbCompat(): Int = android.graphics.Color.argb((alpha * 255f).toInt().coerceIn(0,255),(red * 255f).toInt().coerceIn(0,255),(green * 255f).toInt().coerceIn(0,255),(blue * 255f).toInt().coerceIn(0,255))
