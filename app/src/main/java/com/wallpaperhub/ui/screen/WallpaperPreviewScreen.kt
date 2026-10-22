package com.wallpaperhub.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.preview.model.CropGeometry
import com.wallpaperhub.preview.model.CropSelectionPolicy
import com.wallpaperhub.preview.model.CropTransform
import com.wallpaperhub.preview.model.MediaScaleMode
import com.wallpaperhub.preview.model.MediaTransformPolicy
import com.wallpaperhub.preview.model.PreviewApplyState
import com.wallpaperhub.ui.designsystem.MediaFitMode
import com.wallpaperhub.ui.designsystem.WallpaperMediaPreview
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.viewmodel.WallpaperPreviewViewModel

private const val WALLPAPER_VIEWPORT_WIDTH = 900f
private const val WALLPAPER_VIEWPORT_HEIGHT = 1950f

@Composable
fun WallpaperPreviewScreen(
    wallpaperId: String,
    onBack: () -> Unit,
    vm: WallpaperPreviewViewModel = viewModel(),
) {
    LaunchedEffect(wallpaperId) { vm.load(wallpaperId) }
    val entity by vm.entity.collectAsStateWithLifecycle()
    val initialOffset by vm.initialOffset.collectAsStateWithLifecycle()
    val initialScale by vm.initialScale.collectAsStateWithLifecycle()
    val applyState by vm.applyState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val accent = MaterialTheme.colorScheme.primary
    var mediaPreviewActive by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(applyState) {
        WallpaperDebugLog.i(
            "preview_ui",
            "apply_state_rendered",
            "state" to applyState,
            "wallpaperId" to wallpaperId.take(8),
        )
    }
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            WallpaperDebugLog.d(
                "preview_ui",
                "lifecycle",
                "eventName" to event.name,
                "wallpaperId" to wallpaperId.take(8),
            )
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mediaPreviewActive = true
                    vm.verifyApplyResult()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> mediaPreviewActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val item = entity
    if (item == null) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = accent)
        }
        return
    }

    var scale by rememberSaveable(item.id, initialScale) { mutableStateOf(initialScale) }
    var offsetX by rememberSaveable(item.id, initialOffset.first) { mutableStateOf(initialOffset.first) }
    var offsetY by rememberSaveable(item.id, initialOffset.second) { mutableStateOf(initialOffset.second) }
    var previewMode by rememberSaveable(item.id) { mutableStateOf("clean") }
    var scaleModeValue by rememberSaveable(item.id, item.cropScaleMode) {
        mutableStateOf(MediaScaleMode.fromPersisted(item.cropScaleMode).persistedValue)
    }
    val scaleMode = MediaScaleMode.fromPersisted(scaleModeValue)
    val busy = applyState in setOf(
        PreviewApplyState.SAVING,
        PreviewApplyState.OPENING_SYSTEM_CONFIRMATION,
        PreviewApplyState.WAITING_FOR_SYSTEM_CONFIRMATION,
        PreviewApplyState.VERIFYING_APPLIED_WALLPAPER,
    )
    val resetCrop = {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            PreviewTopBar(
                title = "调整构图",
                showOverlay = previewMode != "clean",
                onBack = {
                    // Drop the ExoPlayer subtree before navigation starts. On the captured 4K/60 HEVC
                    // path, waiting for NavHost disposal left codec/surface teardown overlapping Library.
                    mediaPreviewActive = false
                    onBack()
                },
                onReset = resetCrop,
                resetEnabled = !busy,
            )
            PreviewModeSwitch(mode = previewMode, onModeChange = { previewMode = it })

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(WallpaperTokens.GallerySurface),
            ) {
                val density = LocalDensity.current
                val workspaceWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val workspaceHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val mediaWidth = item.width?.takeIf { it > 0 }?.toFloat()
                    ?: WALLPAPER_VIEWPORT_WIDTH
                val mediaHeight = item.height?.takeIf { it > 0 }?.toFloat()
                    ?: WALLPAPER_VIEWPORT_HEIGHT
                val mediaRatio = mediaWidth / mediaHeight
                val workspaceRatio = workspaceWidthPx / workspaceHeightPx

                val displayedMediaWidthPx: Float
                val displayedMediaHeightPx: Float
                if (mediaRatio >= workspaceRatio) {
                    displayedMediaWidthPx = workspaceWidthPx
                    displayedMediaHeightPx = workspaceWidthPx / mediaRatio
                } else {
                    displayedMediaHeightPx = workspaceHeightPx
                    displayedMediaWidthPx = workspaceHeightPx * mediaRatio
                }
                val mediaLeftPx = (workspaceWidthPx - displayedMediaWidthPx) / 2f
                val mediaTopPx = (workspaceHeightPx - displayedMediaHeightPx) / 2f

                val transform = CropGeometry.clamp(
                    CropTransform(scale, offsetX, offsetY, scaleMode),
                )
                val output = MediaTransformPolicy.calculate(
                    viewportWidth = WALLPAPER_VIEWPORT_WIDTH,
                    viewportHeight = WALLPAPER_VIEWPORT_HEIGHT,
                    mediaWidth = item.width,
                    mediaHeight = item.height,
                    transform = transform,
                )
                val selection = CropSelectionPolicy.fromTransform(output)
                val selectionLeftPx = mediaLeftPx + selection.left * displayedMediaWidthPx
                val selectionTopPx = mediaTopPx + selection.top * displayedMediaHeightPx
                val selectionWidthPx = (selection.width * displayedMediaWidthPx).coerceAtLeast(1f)
                val selectionHeightPx = (selection.height * displayedMediaHeightPx).coerceAtLeast(1f)
                val selectionRightPx = selectionLeftPx + selectionWidthPx
                val selectionBottomPx = selectionTopPx + selectionHeightPx

                WallpaperMediaPreview(
                    entity = item,
                    fitMode = MediaFitMode.FIT,
                    active = mediaPreviewActive,
                    modifier = Modifier.fillMaxSize(),
                )

                Canvas(Modifier.fillMaxSize()) {
                    val dim = Color.Black.copy(alpha = 0.52f)
                    if (selectionTopPx > mediaTopPx) {
                        drawRect(
                            color = dim,
                            topLeft = Offset(mediaLeftPx, mediaTopPx),
                            size = Size(displayedMediaWidthPx, selectionTopPx - mediaTopPx),
                        )
                    }
                    if (selectionBottomPx < mediaTopPx + displayedMediaHeightPx) {
                        drawRect(
                            color = dim,
                            topLeft = Offset(mediaLeftPx, selectionBottomPx),
                            size = Size(
                                displayedMediaWidthPx,
                                mediaTopPx + displayedMediaHeightPx - selectionBottomPx,
                            ),
                        )
                    }
                    if (selectionLeftPx > mediaLeftPx) {
                        drawRect(
                            color = dim,
                            topLeft = Offset(mediaLeftPx, selectionTopPx),
                            size = Size(selectionLeftPx - mediaLeftPx, selectionHeightPx),
                        )
                    }
                    if (selectionRightPx < mediaLeftPx + displayedMediaWidthPx) {
                        drawRect(
                            color = dim,
                            topLeft = Offset(selectionRightPx, selectionTopPx),
                            size = Size(
                                mediaLeftPx + displayedMediaWidthPx - selectionRightPx,
                                selectionHeightPx,
                            ),
                        )
                    }
                    drawRoundRect(
                        color = accent.copy(alpha = 0.96f),
                        topLeft = Offset(selectionLeftPx, selectionTopPx),
                        size = Size(selectionWidthPx, selectionHeightPx),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                if (previewMode != "clean") {
                    val leftDp = with(density) { selectionLeftPx.toDp() }
                    val topDp = with(density) { selectionTopPx.toDp() }
                    val widthDp = with(density) { selectionWidthPx.toDp() }
                    val heightDp = with(density) { selectionHeightPx.toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = leftDp, y = topDp)
                            .width(widthDp)
                            .height(heightDp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        DeviceReferenceOverlay(previewMode)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(item.id, busy, scaleMode, displayedMediaWidthPx, displayedMediaHeightPx) {
                            if (busy || scaleMode == MediaScaleMode.FIT) return@pointerInput
                            detectTransformGestures { _, pan, zoom, _ ->
                                val currentTransform = CropGeometry.clamp(
                                    CropTransform(scale, offsetX, offsetY, scaleMode),
                                )
                                val currentOutput = MediaTransformPolicy.calculate(
                                    viewportWidth = WALLPAPER_VIEWPORT_WIDTH,
                                    viewportHeight = WALLPAPER_VIEWPORT_HEIGHT,
                                    mediaWidth = item.width,
                                    mediaHeight = item.height,
                                    transform = currentTransform,
                                )
                                val currentSelection = CropSelectionPolicy.fromTransform(currentOutput)

                                val nextScaleOnly = CropGeometry.clamp(
                                    currentTransform.copy(scale = currentTransform.scale * zoom),
                                )
                                val nextOutput = MediaTransformPolicy.calculate(
                                    viewportWidth = WALLPAPER_VIEWPORT_WIDTH,
                                    viewportHeight = WALLPAPER_VIEWPORT_HEIGHT,
                                    mediaWidth = item.width,
                                    mediaHeight = item.height,
                                    transform = nextScaleOnly,
                                )
                                val targetCenterX = (
                                    currentSelection.centerX + pan.x / displayedMediaWidthPx
                                ).coerceIn(0f, 1f)
                                val targetCenterY = (
                                    currentSelection.centerY + pan.y / displayedMediaHeightPx
                                ).coerceIn(0f, 1f)
                                val nextTransform = CropGeometry.clamp(
                                    nextScaleOnly.copy(
                                        offsetX = CropSelectionPolicy.offsetForCenter(
                                            targetCenterX,
                                            nextOutput.rendererScaleX,
                                        ),
                                        offsetY = CropSelectionPolicy.offsetForCenter(
                                            targetCenterY,
                                            nextOutput.rendererScaleY,
                                        ),
                                    ),
                                )
                                scale = nextTransform.scale
                                offsetX = nextTransform.offsetX
                                offsetY = nextTransform.offsetY
                            }
                        },
                )
            }

            WallpaperPreviewControls(
                scale = scale,
                scaleMode = scaleMode,
                applyState = applyState,
                busy = busy,
                onScaleModeChange = { mode ->
                    if (mode != scaleMode) {
                        scaleModeValue = mode.persistedValue
                        resetCrop()
                        WallpaperDebugLog.i(
                            "preview_ui",
                            "scale_mode_changed",
                            "wallpaperId" to wallpaperId.take(8),
                            "mode" to mode.persistedValue,
                        )
                    }
                },
                onReset = resetCrop,
                onSave = { vm.saveCrop(offsetX, offsetY, scale, scaleMode) },
                onApply = { vm.saveAndApply(offsetX, offsetY, scale, scaleMode) },
            )
        }
    }
}
