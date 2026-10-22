package com.wallpaperhub.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.designsystem.NowPlayingHeroV2
import com.wallpaperhub.ui.designsystem.WallpaperTileV2
import com.wallpaperhub.ui.designsystem.WallpaperTokens
import com.wallpaperhub.ui.designsystem.mediaSummary
import com.wallpaperhub.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryV1(
    onOpenDetail: (String) -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val items by vm.libraryWallpapers.collectAsStateWithLifecycle()
    // Legacy presentation flow remains unfiltered because this screen no longer drives vm.Tab.
    // It gives the hero an all-library lookup even while the grid is filtered to Favorites/Carousel.
    val allItems by vm.wallpapers.collectAsStateWithLifecycle()
    val filters by vm.libraryFilters.collectAsStateWithLifecycle()
    val viewMode by vm.galleryViewMode.collectAsStateWithLifecycle()
    val session by vm.playbackSession.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by vm.isSelectionMode.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var importSheetOpen by rememberSaveable { mutableStateOf(false) }
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
    var moreSheetOpen by rememberSaveable { mutableStateOf(false) }
    var jumpToCurrentRequested by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> vm.importMediaCopied(uris) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.importMediaCopied(uris) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> vm.importFolderCopied(uri) }

    val selected = remember(items, selectedIds) { items.filter { it.id in selectedIds } }
    val current = remember(allItems, session.appliedUri) {
        session.appliedUri?.let { uri -> allItems.firstOrNull { it.uri == uri } }
    }
    val next = remember(allItems, session.nextPreviewUri) {
        session.nextPreviewUri?.let { uri -> allItems.firstOrNull { it.uri == uri } }
    }

    LaunchedEffect(jumpToCurrentRequested, items, session.appliedUri) {
        if (!jumpToCurrentRequested || session.appliedUri == null) return@LaunchedEffect
        val currentIndex = items.indexOfFirst { it.uri == session.appliedUri }
        if (currentIndex >= 0) {
            // Hero + segmented tabs + count row are three full-span items before media.
            val targetIndex = 3 + currentIndex
            WallpaperDebugLog.i(
                "library_ui",
                "locate_current_start",
                "target" to WallpaperDebugLog.uri(session.appliedUri),
                "mediaIndex" to currentIndex,
                "gridIndex" to targetIndex,
                "fromIndex" to gridState.firstVisibleItemIndex,
                "distance" to kotlin.math.abs(targetIndex - gridState.firstVisibleItemIndex),
            )
            gridState.scrollToItem(index = targetIndex)
            jumpToCurrentRequested = false
            WallpaperDebugLog.i(
                "library_ui",
                "locate_current_complete",
                "target" to WallpaperDebugLog.uri(session.appliedUri),
                "gridIndex" to targetIndex,
                "firstVisible" to gridState.firstVisibleItemIndex,
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val widthDp = maxWidth.value.toInt()
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(items.map { it.id }.toSet()) },
                )
            } else {
                LibraryTopBar(
                    searchOpen = searchOpen,
                    query = filters.query,
                    onSearchToggle = { searchOpen = !searchOpen },
                    onQueryChange = { query -> vm.updateLibraryFilters { it.copy(query = query) } },
                    onFilter = { filterSheetOpen = true },
                    onLocateCurrent = if (session.appliedUri != null) {
                        {
                            vm.clearLibraryFilters()
                            jumpToCurrentRequested = true
                        }
                    } else null,
                    onMore = { moreSheetOpen = true },
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(LibraryLayoutPolicy.columnsFor(widthDp, viewMode)),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NowPlayingHeroV2(
                            current = current,
                            next = next,
                            phase = session.phase,
                            errorMessage = session.errorMessage,
                            context = context,
                            onPrevious = vm::showPreviousWallpaper,
                            onPrimary = { current?.let { onOpenDetail(it.id) } },
                            onNext = vm::showNextWallpaper,
                            onOpenFlow = current?.let { entity -> { onOpenDetail(entity.id) } },
                            heroHeight = LibraryLayoutPolicy.heroHeightDp(widthDp).dp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibrarySegmentedTabs(
                            favoriteSelected = filters.favoriteOnly,
                            playlistSelected = filters.playlistOnly,
                            onAll = {
                                vm.updateLibraryFilters {
                                    it.copy(favoriteOnly = false, playlistOnly = false, notInPlaylistOnly = false)
                                }
                            },
                            onFavorite = {
                                vm.updateLibraryFilters {
                                    it.copy(favoriteOnly = true, playlistOnly = false, notInPlaylistOnly = false)
                                }
                            },
                            onPlaylist = {
                                vm.updateLibraryFilters {
                                    it.copy(favoriteOnly = false, playlistOnly = true, notInPlaylistOnly = false)
                                }
                            },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryCountRow(
                            total = items.size,
                            activeFilters = filters.activeCount(),
                            onFilter = { filterSheetOpen = true },
                        )
                    }

                    if (items.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibraryEmptyState(
                                hasFilters = filters.activeCount() > 0,
                                onClear = vm::clearLibraryFilters,
                                onImport = { importSheetOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        items(
                            items = items,
                            key = { it.id },
                            contentType = { viewMode },
                        ) { entity ->
                            if (viewMode == GalleryViewMode.LIST) {
                                WallpaperTextRow(
                                    entity = entity,
                                    selected = entity.id in selectedIds,
                                    current = entity.uri == session.appliedUri,
                                    onClick = {
                                        if (selectionMode) vm.toggleSelection(entity.id)
                                        else onOpenDetail(entity.id)
                                    },
                                    onLongClick = { vm.startSelection(entity.id) },
                                )
                            } else {
                                WallpaperTileV2(
                                    entity = entity,
                                    context = context,
                                    selected = entity.id in selectedIds,
                                    current = entity.uri == session.appliedUri,
                                    onClick = {
                                        if (selectionMode) vm.toggleSelection(entity.id)
                                        else onOpenDetail(entity.id)
                                    },
                                    onLongClick = { vm.startSelection(entity.id) },
                                )
                            }
                        }
                    }
                }

                if (selectionMode) {
                    SelectionActionBar(
                        count = selected.size,
                        onAddToPlaylist = { vm.addToPlaylist(selected); vm.clearSelection() },
                        onFavorite = {
                            selected.forEach(vm::toggleFavorite)
                            vm.clearSelection()
                        },
                        onDelete = { confirmDelete = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                } else {
                    ImportOrb(
                        onClick = { importSheetOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    if (filterSheetOpen) {
        FilterSheet(
            filters = filters,
            resultCount = items.size,
            onDismiss = { filterSheetOpen = false },
            onChange = vm::updateLibraryFilters,
            onReset = vm::clearLibraryFilters,
        )
    }
    if (moreSheetOpen) {
        LibraryMoreSheet(
            viewMode = viewMode,
            filters = filters,
            onDismiss = { moreSheetOpen = false },
            onViewMode = vm::updateGalleryViewMode,
            onScrollTop = {
                moreSheetOpen = false
                scope.launch {
                    WallpaperDebugLog.i("library_ui", "jump_top", "fromIndex" to gridState.firstVisibleItemIndex)
                    gridState.scrollToItem(0)
                }
            },
            onScrollBottom = {
                moreSheetOpen = false
                scope.launch {
                    val target = (items.size + 2).coerceAtLeast(0)
                    WallpaperDebugLog.i(
                        "library_ui",
                        "jump_bottom",
                        "fromIndex" to gridState.firstVisibleItemIndex,
                        "targetIndex" to target,
                    )
                    gridState.scrollToItem(target)
                }
            },
            onMediaFilter = { media -> vm.updateLibraryFilters { currentFilter -> currentFilter.copy(media = media) } },
            onToggleNotInPlaylist = {
                vm.updateLibraryFilters { currentFilter ->
                    currentFilter.copy(
                        notInPlaylistOnly = !currentFilter.notInPlaylistOnly,
                        playlistOnly = false,
                    )
                }
            },
            onClearFilters = vm::clearLibraryFilters,
        )
    }
    if (importSheetOpen) {
        ImportSheet(
            onDismiss = { importSheetOpen = false },
            onPhotos = {
                importSheetOpen = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFiles = {
                importSheetOpen = false
                filePicker.launch(arrayOf("image/*", "video/*"))
            },
            onFolder = {
                importSheetOpen = false
                folderPicker.launch(null)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除所选壁纸？") },
            text = { Text("将删除库中记录与应用内部副本，不影响外部原文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMany(selected)
                        vm.clearSelection()
                        confirmDelete = false
                    },
                ) { Text("删除", color = WallpaperTokens.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallpaperTextRow(
    entity: WallpaperEntity,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = WallpaperTokens.ShapeMd,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (current) accent.copy(alpha = 0.72f) else WallpaperTokens.StrokeSubtle,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entity.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(mediaSummary(entity), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            val state = when {
                current -> "当前桌面"
                entity.inPlaylist -> "轮播"
                entity.isFavorite -> "收藏"
                else -> ""
            }
            if (state.isNotEmpty()) {
                Text(state, color = accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryMoreSheet(
    viewMode: GalleryViewMode,
    filters: LibraryFilterState,
    onDismiss: () -> Unit,
    onViewMode: (GalleryViewMode) -> Unit,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
    onMediaFilter: (LibraryMediaFilter) -> Unit,
    onToggleNotInPlaylist: () -> Unit,
    onClearFilters: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("浏览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("显示密度", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = viewMode == mode,
                        onClick = { onViewMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            LibraryMoreAction("回到顶部", "直接跳到画廊顶部", onScrollTop)
            LibraryMoreAction("前往底部", "直接跳到最后一张，不穿过中间缩略图", onScrollBottom)
            LibraryMoreAction(
                title = "只看图片",
                subtitle = if (filters.media == LibraryMediaFilter.IMAGE) "已开启" else "隐藏视频",
                onClick = { onMediaFilter(LibraryMediaFilter.IMAGE) },
            )
            LibraryMoreAction(
                title = "只看视频",
                subtitle = if (filters.media == LibraryMediaFilter.VIDEO) "已开启" else "隐藏图片",
                onClick = { onMediaFilter(LibraryMediaFilter.VIDEO) },
            )
            LibraryMoreAction(
                title = "未加入轮播",
                subtitle = if (filters.notInPlaylistOnly) "已开启" else "只显示尚未加入的媒体",
                onClick = onToggleNotInPlaylist,
            )
            TextButton(onClick = onClearFilters, modifier = Modifier.align(Alignment.End)) {
                Text("清除全部筛选")
            }
        }
    }
}

@Composable
private fun LibraryMoreAction(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        shape = WallpaperTokens.ShapeMd,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImportOrb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(60.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = "导入壁纸", modifier = Modifier.size(27.dp))
        }
    }
}

@Composable
private fun LibraryTopBar(
    searchOpen: Boolean,
    query: String,
    onSearchToggle: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilter: () -> Unit,
    onLocateCurrent: (() -> Unit)?,
    onMore: () -> Unit,
) {
    if (searchOpen) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜索壁纸") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
            )
            IconButton(onClick = onSearchToggle) {
                Icon(Icons.Default.Close, contentDescription = "退出搜索")
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("图库", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "属于你的私人壁纸库",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onSearchToggle, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onFilter, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.FilterList, contentDescription = "筛选")
            }
            if (onLocateCurrent != null) {
                IconButton(onClick = onLocateCurrent, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MyLocation, contentDescription = "定位当前壁纸")
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreHoriz, contentDescription = "浏览选项")
            }
        }
    }
}

@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "退出选择")
        }
        Text(
            "已选择 $count 项",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SelectAll, contentDescription = "全选")
        }
    }
}

@Composable
private fun LibraryCountRow(total: Int, activeFilters: Int, onFilter: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$total 项", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f))
        if (activeFilters > 0) {
            Surface(
                onClick = onFilter,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    "$activeFilters 个筛选",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun LibrarySegmentedTabs(
    favoriteSelected: Boolean,
    playlistSelected: Boolean,
    onAll: () -> Unit,
    onFavorite: () -> Unit,
    onPlaylist: () -> Unit,
) {
    val selectedIndex = when {
        favoriteSelected -> 1
        playlistSelected -> 2
        else -> 0
    }
    val entries = listOf("全部" to onAll, "收藏" to onFavorite, "轮播" to onPlaylist)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).padding(4.dp)) {
            val segmentWidth = maxWidth / 3
            val indicatorX by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                label = "library_tab_indicator",
            )
            Surface(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(segmentWidth)
                    .fillMaxHeight(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 0.dp,
            ) {}
            Row(Modifier.fillMaxSize()) {
                entries.forEachIndexed { index, (label, action) ->
                    val contentColor by animateColorAsState(
                        targetValue = if (selectedIndex == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "library_tab_color_$index",
                    )
                    Surface(
                        onClick = action,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = CircleShape,
                        color = Color.Transparent,
                        contentColor = contentColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                color = contentColor,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    hasFilters: Boolean,
    onClear: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 52.dp, horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Text(
            if (hasFilters) "没有匹配结果" else "建立你的私人壁纸库",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            if (hasFilters) "调整筛选条件，或者恢复显示全部内容" else "导入图片或视频，内容会出现在这里",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
        if (hasFilters) {
            OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 20.dp)) { Text("清除筛选") }
        } else {
            Button(onClick = onImport, modifier = Modifier.padding(top = 20.dp)) { Text("导入壁纸") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filters: LibraryFilterState,
    resultCount: Int,
    onDismiss: () -> Unit,
    onChange: ((LibraryFilterState) -> LibraryFilterState) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("筛选壁纸", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FilterGroup("媒体") {
                LibraryMediaFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filters.media == option,
                        onClick = { onChange { it.copy(media = option) } },
                        label = { Text(mediaLabel(option)) },
                        leadingIcon = if (option == LibraryMediaFilter.VIDEO) {
                            { Icon(Icons.Default.VideoLibrary, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            FilterGroup("状态") {
                FilterChip(filters.favoriteOnly, { onChange { it.copy(favoriteOnly = !it.favoriteOnly) } }, label = { Text("收藏") })
                FilterChip(filters.playlistOnly, { onChange { it.copy(playlistOnly = !it.playlistOnly) } }, label = { Text("在轮播") })
                FilterChip(filters.croppedOnly, { onChange { it.copy(croppedOnly = !it.croppedOnly) } }, label = { Text("已调整") })
            }
            FilterGroup("方向") {
                LibraryOrientation.entries.forEach { option ->
                    FilterChip(
                        filters.orientation == option,
                        { onChange { it.copy(orientation = option) } },
                        label = { Text(orientationLabel(option)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("重置") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("查看 $resultCount 项") }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private fun mediaLabel(filter: LibraryMediaFilter) = when (filter) {
    LibraryMediaFilter.ALL -> "全部"
    LibraryMediaFilter.IMAGE -> "图片"
    LibraryMediaFilter.VIDEO -> "视频"
}

private fun orientationLabel(filter: LibraryOrientation) = when (filter) {
    LibraryOrientation.ALL -> "全部"
    LibraryOrientation.PORTRAIT -> "竖屏"
    LibraryOrientation.LANDSCAPE -> "横屏"
    LibraryOrientation.SQUARE -> "方形"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "导入壁纸",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("系统相册") },
                supportingContent = { Text("选择图片或视频") },
                leadingContent = { Icon(Icons.Default.Image, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Button(onClick = onPhotos, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("选择相册") }
            ListItem(
                headlineContent = { Text("文件") },
                supportingContent = { Text("支持多选，并复制到应用内部") },
                leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            OutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("选择文件") }
            ListItem(
                headlineContent = { Text("文件夹") },
                supportingContent = { Text("扫描其中的图片与视频") },
                leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            OutlinedButton(onClick = onFolder, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("选择文件夹") }
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onAddToPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = WallpaperTokens.NavigationChrome,
        shape = WallpaperTokens.DockShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$count 项",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.VideoLibrary, contentDescription = "加入轮播")
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SelectAll, contentDescription = "收藏")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = WallpaperTokens.Error)
            }
        }
    }
}
