package com.wallpaperhub.ui.flow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.playback.engine.PlaybackConfirmationPolicy
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.designsystem.mediaSummary
import com.wallpaperhub.wallpaper.PrivateFileUriHelper
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Compact three-card viewport over the complete materialized playback plan. */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CarouselRail(
    queue: List<WallpaperEntity>,
    appliedUri: String?,
    desiredUri: String?,
    phase: PlaybackPhase,
    storedIndex: Int,
    allowReorder: Boolean,
    onApply: (WallpaperEntity) -> Unit,
    onMove: (List<WallpaperEntity>) -> Unit,
    onOpen: (WallpaperEntity) -> Unit,
    queueRevision: Long = 0L,
) {
    if (queue.isEmpty()) return
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val queueUris = remember(queue, queueRevision) { queue.map { it.uri } }
    val focusedUri = CarouselRailPolicy.focusUri(queueUris, appliedUri, desiredUri, phase)

    val focusIsCoherent = remember(queueUris, focusedUri, storedIndex) {
        when {
            focusedUri == null -> true
            storedIndex !in queueUris.indices -> false
            else -> queueUris[storedIndex] == focusedUri
        }
    }
    val resolvedFocusedIndex = remember(queueUris, focusedUri, storedIndex, focusIsCoherent) {
        when {
            focusIsCoherent && storedIndex in queueUris.indices -> storedIndex
            focusedUri == null -> storedIndex.takeIf { it in queueUris.indices } ?: 0
            else -> focusedUri.let { queueUris.indexOf(it) }.takeIf { it >= 0 } ?: 0
        }
    }
    val initialIndex = if (focusIsCoherent) resolvedFocusedIndex else 0
    val pagerState = rememberPagerState(
        initialPage = CarouselRailPolicy.pagerPageForQueueIndex(initialIndex),
    ) { queue.size + 2 }
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusedUri, resolvedFocusedIndex, focusIsCoherent, queueRevision, queue.size) {
        if (!focusIsCoherent) {
            WallpaperDebugLog.d(
                "flow_ui",
                "carousel_focus_waiting_for_coherent_queue",
                "revision" to queueRevision,
                "focused" to WallpaperDebugLog.uri(focusedUri),
                "storedIndex" to storedIndex,
                "storedUri" to WallpaperDebugLog.uri(queueUris.getOrNull(storedIndex)),
                "resolvedOldIndex" to resolvedFocusedIndex,
                "queueSize" to queue.size,
            )
            return@LaunchedEffect
        }
        val focusedPage = CarouselRailPolicy.pagerPageForQueueIndex(resolvedFocusedIndex)
        if (focusedPage != pagerState.currentPage) {
            val previousPage = pagerState.currentPage
            pagerState.scrollToPage(focusedPage)
            WallpaperDebugLog.i(
                "flow_ui",
                "carousel_focus_synced",
                "revision" to queueRevision,
                "focused" to WallpaperDebugLog.uri(focusedUri),
                "focusedIndex" to resolvedFocusedIndex,
                "pageFrom" to previousPage,
                "pageTo" to focusedPage,
                "queueSize" to queue.size,
            )
        }
    }

    LaunchedEffect(pagerState.currentPage, queue.size) {
        val settledPage = CarouselRailPolicy.settledPagerPage(pagerState.currentPage, queue.size)
        if (settledPage != pagerState.currentPage) pagerState.scrollToPage(settledPage)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageWidth = (maxWidth * 0.52f).coerceIn(174.dp, 214.dp)
            val sidePadding = ((maxWidth - pageWidth) / 2).coerceAtLeast(0.dp)
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(pageWidth),
                modifier = Modifier.fillMaxWidth().height(190.dp),
                contentPadding = PaddingValues(horizontal = sidePadding),
                pageSpacing = 8.dp,
                beyondViewportPageCount = 1,
            ) { page ->
                val queueIndex = CarouselRailPolicy.queueIndexForPagerPage(page, queue.size)
                val entity = queue[queueIndex]
                val imageRequest = remember(entity.uri) {
                    ImageRequest.Builder(context)
                        .data(PrivateFileUriHelper.safeUri(context, entity.uri))
                        .size(540, 540)
                        .crossfade(false)
                        .build()
                }
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
                val center = pageOffset < 0.08f
                val isActive = center && (entity.uri == appliedUri || entity.uri == desiredUri)

                Surface(
                    onClick = { onOpen(entity) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = 1f - pageOffset * 0.10f
                            scaleY = 1f - pageOffset * 0.10f
                            alpha = 1f - pageOffset * 0.48f
                        },
                    shape = WallpaperTokens.ShapeLg,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        if (isActive) 1.5.dp else 1.dp,
                        if (isActive) accent.copy(alpha = 0.82f) else WallpaperTokens.StrokeSubtle,
                    ),
                    tonalElevation = 0.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = "轮播第 ${queueIndex + 1} 项：${entity.name}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.06f),
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.80f),
                                ),
                            ),
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                            color = WallpaperTokens.NavigationChrome.copy(alpha = 0.82f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
                        ) {
                            Text(
                                "${queueIndex + 1} / ${queue.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (isActive) {
                            val label = if (PlaybackConfirmationPolicy.shouldShowAppliedLabel(entity.uri, appliedUri)) {
                                "当前桌面"
                            } else {
                                "正在切换"
                            }
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = accent,
                                shape = CircleShape,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (label == "当前桌面") {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.height(14.dp))
                                    }
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (center) {
                            Column(
                                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    entity.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White,
                                )
                                Text(
                                    mediaSummary(entity),
                                    color = Color.White.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        val selectedIndex = CarouselRailPolicy.queueIndexForPagerPage(pagerState.currentPage, queue.size)
        val selected = queue[selectedIndex]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    WallpaperDebugLog.d(
                        "flow_ui",
                        "carousel_previous_clicked",
                        "fromIndex" to selectedIndex,
                        "queueSize" to queue.size,
                    )
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                enabled = queue.size > 1,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "浏览上一项")
            }
            Button(
                onClick = { onApply(selected) },
                modifier = Modifier.weight(1.55f).height(48.dp),
            ) {
                Text(if (selected.uri == appliedUri) "重新开始" else "从此项开始", maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    WallpaperDebugLog.d(
                        "flow_ui",
                        "carousel_next_clicked",
                        "fromIndex" to selectedIndex,
                        "queueSize" to queue.size,
                    )
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                enabled = queue.size > 1,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "浏览下一项")
            }
        }
        Text(
            "左右浏览不会改变桌面；只有“从此项开始”才会切换轮播起点。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
