package com.wallpaperhub.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.data.repo.MediaReviewDecisionStore
import com.wallpaperhub.data.repo.Round4DuplicateImportAudit
import com.wallpaperhub.data.repo.Round4ImportDeduplicator
import com.wallpaperhub.data.repo.Round4ImportGroupAssigner
import com.wallpaperhub.data.repo.Round4ImportUriCollector
import com.wallpaperhub.data.repo.WallpaperRepository
import com.wallpaperhub.playback.engine.PlaybackOrderCoordinator
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.playback.model.PlaybackSession
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import com.wallpaperhub.ui.library.GalleryViewMode
import com.wallpaperhub.ui.library.LibraryFilterState
import com.wallpaperhub.ui.library.matches
import com.wallpaperhub.ui.model.MediaMetadataFormatter
import com.wallpaperhub.wallpaper.VideoWallpaperSettings
import com.wallpaperhub.wallpaper.VideoWallpaperStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Wallpaper library + carousel presentation state. */
class LibraryViewModel(app: Application) : AndroidViewModel(app), SharedPreferences.OnSharedPreferenceChangeListener {
    private val repo = (app as WallpaperHubApp).repository
    private val playbackRepo = (app as WallpaperHubApp).playbackRepository
    private val appContext = app.applicationContext
    private val importDeduplicator = Round4ImportDeduplicator(appContext)
    private val importGroupAssigner = Round4ImportGroupAssigner(appContext)
    private val reviewDecisions = MediaReviewDecisionStore(appContext)

    enum class Tab { ALL, FAVORITE, PLAYLIST }

    private val _tab = MutableStateFlow(Tab.ALL)
    val tab: StateFlow<Tab> = _tab

    private val _libraryFilters = MutableStateFlow(LibraryFilterState())
    val libraryFilters: StateFlow<LibraryFilterState> = _libraryFilters

    private val _galleryViewMode = MutableStateFlow(GalleryViewMode.COMFORTABLE_GRID)
    val galleryViewMode: StateFlow<GalleryViewMode> = _galleryViewMode

    val allWallpapers: StateFlow<List<WallpaperEntity>> = repo.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val libraryWallpapers: StateFlow<List<WallpaperEntity>> = combine(
        allWallpapers,
        _libraryFilters,
    ) { items, filters -> items.filter { it.matches(filters) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val wallpapers: StateFlow<List<WallpaperEntity>> = allWallpapers

    val playlistQueue: StateFlow<List<WallpaperEntity>> = repo.observePlaylist().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val playbackSession: StateFlow<PlaybackSession> = playbackRepo.observeSession().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaybackSession(),
    )

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _playlistSettings = MutableStateFlow(
        VideoWallpaperSettings.Settings(
            rotateIntervalSeconds = 0,
            randomEnabled = false,
            playbackMode = VideoWallpaperSettings.PlaybackMode.SEQUENTIAL,
            displayGravity = VideoWallpaperSettings.DisplayGravity.CENTER,
        )
    )
    val playlistSettings: StateFlow<VideoWallpaperSettings.Settings> = _playlistSettings

    private val _currentWallpaperUri = MutableStateFlow<String?>(null)
    val currentWallpaperUri: StateFlow<String?> = _currentWallpaperUri

    private val _adjustedCropUris = MutableStateFlow<Set<String>>(emptySet())
    val adjustedCropUris: StateFlow<Set<String>> = _adjustedCropUris

    private val _localCopyStats = MutableStateFlow<WallpaperRepository.LocalCopyStats?>(null)
    val localCopyStats: StateFlow<WallpaperRepository.LocalCopyStats?> = _localCopyStats

    private val _mediaAlbums = MutableStateFlow<List<WallpaperRepository.MediaAlbum>>(emptyList())
    val mediaAlbums: StateFlow<List<WallpaperRepository.MediaAlbum>> = _mediaAlbums

    private val _cacheSize = MutableStateFlow("0.0 B")
    val cacheSize: StateFlow<String> = _cacheSize

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _parallaxStrength = MutableStateFlow(1.0f)
    val parallaxStrength: StateFlow<Float> = _parallaxStrength

    private val _transitionType = MutableStateFlow("crossfade")
    val transitionType: StateFlow<String> = _transitionType

    fun updateParallaxStrength(strength: Float) {
        VideoWallpaperSettings.prefs(appContext).edit().putFloat(VideoWallpaperSettings.KEY_PARALLAX_STRENGTH, strength).apply()
        _parallaxStrength.value = strength
    }

    fun updateTransitionType(type: String) {
        VideoWallpaperSettings.prefs(appContext).edit().putString(VideoWallpaperSettings.KEY_TRANSITION_TYPE, type).apply()
        _transitionType.value = type
    }

    fun toggleSelection(id: String) {
        val current = _selectedIds.value
        val next = if (id in current) current - id else current + id
        _selectedIds.value = next
        if (next.isEmpty()) _isSelectionMode.value = false
    }

    fun startSelection(id: String) {
        _isSelectionMode.value = true
        _selectedIds.value = _selectedIds.value + id
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun selectAll(ids: Set<String>) {
        _selectedIds.value = ids
        _isSelectionMode.value = ids.isNotEmpty()
    }

    init {
        VideoWallpaperSettings.prefs(appContext).registerOnSharedPreferenceChangeListener(this)
        _galleryViewMode.value = GalleryViewMode.fromPersisted(
            VideoWallpaperSettings.prefs(appContext).getString(KEY_GALLERY_VIEW_MODE, null),
        )
        refreshAllSettings()
        syncLocalImportDir(showMessage = false)
        refreshCacheSize()

        viewModelScope.launch {
            playbackRepo.observeSession().collect { session -> _currentWallpaperUri.value = session.appliedUri }
        }
    }

    fun refreshAllSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentUri = playbackRepo.getSession().appliedUri
            val settings = VideoWallpaperSettings.read(appContext)
            val cropUris = repo.allSnapshot().filter { it.cropRevision > 0L }.map { it.uri }.toSet()
            val prefs = VideoWallpaperSettings.prefs(appContext)
            val parallax = prefs.getFloat(VideoWallpaperSettings.KEY_PARALLAX_STRENGTH, 1.0f)
            val transition = prefs.getString(VideoWallpaperSettings.KEY_TRANSITION_TYPE, "crossfade") ?: "crossfade"
            launch(Dispatchers.Main) {
                _currentWallpaperUri.value = currentUri
                _playlistSettings.value = settings
                _adjustedCropUris.value = cropUris
                _parallaxStrength.value = parallax
                _transitionType.value = transition
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != null && key.startsWith(VideoWallpaperSettings.KEY_CROP_OFFSET_PREFIX)) {
            viewModelScope.launch(Dispatchers.IO) {
                val cropUris = repo.allSnapshot().filter { it.cropRevision > 0L }.map { it.uri }.toSet()
                launch(Dispatchers.Main) { _adjustedCropUris.value = cropUris }
            }
        }
        if (key == VideoWallpaperSettings.KEY_PLAYBACK_MODE || key == VideoWallpaperSettings.KEY_SEQUENTIAL_REVERSED || key == VideoWallpaperSettings.KEY_ROTATE_INTERVAL_SECONDS) {
            _playlistSettings.value = VideoWallpaperSettings.read(appContext)
        }
        if (key == VideoWallpaperSettings.KEY_PARALLAX_STRENGTH || key == VideoWallpaperSettings.KEY_TRANSITION_TYPE) {
            viewModelScope.launch(Dispatchers.IO) {
                val prefs = VideoWallpaperSettings.prefs(appContext)
                val parallax = prefs.getFloat(VideoWallpaperSettings.KEY_PARALLAX_STRENGTH, 1.0f)
                val transition = prefs.getString(VideoWallpaperSettings.KEY_TRANSITION_TYPE, "crossfade") ?: "crossfade"
                launch(Dispatchers.Main) {
                    _parallaxStrength.value = parallax
                    _transitionType.value = transition
                }
            }
        }
    }

    override fun onCleared() {
        VideoWallpaperSettings.prefs(appContext).unregisterOnSharedPreferenceChangeListener(this)
        super.onCleared()
    }

    fun selectTab(tab: Tab) { _tab.value = tab }
    fun updateLibraryFilters(transform: (LibraryFilterState) -> LibraryFilterState) { _libraryFilters.value = transform(_libraryFilters.value) }

    fun updateGalleryViewMode(viewMode: GalleryViewMode) {
        _galleryViewMode.value = viewMode
        VideoWallpaperSettings.prefs(appContext).edit().putString(KEY_GALLERY_VIEW_MODE, viewMode.persistedValue).apply()
    }

    fun clearLibraryFilters() { _libraryFilters.value = LibraryFilterState() }

    fun importMedia(uris: List<Uri>, groupTag: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val preflight = importDeduplicator.preflight(repo, uris)
            if (preflight.acceptedUris.isEmpty()) return@launch
            val before = libraryIdsSnapshot()
            val raw = repo.importFromUris(preflight.acceptedUris)
            if (preflight.duplicateCount > 0) importDeduplicator.resolveBatchTargets(repo, preflight, before)
            val r = raw.copy(skipped = raw.skipped + preflight.duplicateCount)
            importGroupAssigner.assignFresh(before, groupTag)
            val autoAdded = autoAddNewImports(before)
            _events.send(importMessage(r, autoAdded, preflight.duplicateCount, groupTag))
        }
    }

    fun importMediaCopied(uris: List<Uri>, groupTag: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val preflight = importDeduplicator.preflight(repo, uris)
            if (preflight.acceptedUris.isEmpty()) return@launch
            val before = libraryIdsSnapshot()
            val raw = repo.importFromUrisCopied(preflight.acceptedUris)
            if (preflight.duplicateCount > 0) importDeduplicator.resolveBatchTargets(repo, preflight, before)
            val r = raw.copy(skipped = raw.skipped + preflight.duplicateCount)
            importGroupAssigner.assignFresh(before, groupTag)
            val autoAdded = autoAddNewImports(before)
            _events.send(importMessage(r, autoAdded, preflight.duplicateCount, groupTag))
        }
    }

    /** Explicit one-shot override: bypass preflight because the user already reviewed this exact match. */
    fun importDuplicateAnyway(match: Round4ImportDeduplicator.DuplicateMatch) {
        viewModelScope.launch {
            val before = libraryIdsSnapshot()
            val raw = repo.importFromUrisCopied(listOf(match.sourceUri))
            if (raw.total <= 0) {
                _events.send("仍然导入失败：${match.sourceName}")
                return@launch
            }
            val fresh = repo.allSnapshot().filter { it.id !in before }
            val imported = fresh.firstOrNull()
            val existing = match.existingWallpaper
            if (imported != null && existing != null) {
                reviewDecisions.dismissPair(MediaReviewDecisionStore.PairKind.EXACT, imported.id, existing.id)
            }
            val autoAdded = autoAddNewImports(before)
            Round4DuplicateImportAudit.resolve(match)
            _events.send(
                "已按你的决定导入：${match.sourceName}${if (autoAdded > 0) "；已加入轮播" else ""}。这对已复核副本不会在完全重复清理中反复提示"
            )
        }
    }

    fun confirmDuplicateSkip(match: Round4ImportDeduplicator.DuplicateMatch) {
        Round4DuplicateImportAudit.resolve(match)
    }

    fun importFolder(treeUri: Uri?, groupTag: String? = null) {
        if (treeUri == null) return
        viewModelScope.launch {
            importCollectedUris(Round4ImportUriCollector.treeMedia(appContext, treeUri), groupTag)
        }
    }

    fun importFolderCopied(treeUri: Uri?, groupTag: String? = null) {
        if (treeUri == null) return
        viewModelScope.launch {
            importCollectedUris(Round4ImportUriCollector.treeMedia(appContext, treeUri), groupTag)
        }
    }

    fun refreshMediaAlbums() {
        viewModelScope.launch {
            val albums = repo.mediaAlbums()
            _mediaAlbums.value = albums
            if (albums.isEmpty()) _events.send("未读取到相册，请检查照片和视频权限")
        }
    }

    fun importAlbum(album: WallpaperRepository.MediaAlbum, copyToLocal: Boolean, groupTag: String? = null) {
        viewModelScope.launch {
            importCollectedUris(Round4ImportUriCollector.albumMedia(appContext, album.bucketId), groupTag)
        }
    }

    fun delete(entity: WallpaperEntity) {
        deleteMany(listOf(entity))
    }

    fun deleteMany(entities: List<WallpaperEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            runCatching { deleteManyWithRuntimeHandoff(entities) }
                .onSuccess { result ->
                    when {
                        result.keptActive > 0 && result.deleted > 0 -> _events.send(
                            "已删除 ${result.deleted} 项；${result.keptActive} 项正在使用，因未确认新壁纸接管而保留，避免桌面黑屏"
                        )
                        result.keptActive > 0 -> _events.send(
                            "当前壁纸正在使用，尚未确认新的壁纸已接管，因此没有删除，避免桌面黑屏"
                        )
                        else -> _events.send("已从壁纸库删除：${result.deleted} 项")
                    }
                }
                .onFailure { error ->
                    Log.e("LibraryViewModel", "Safe delete failed", error)
                    _events.send("删除失败：${error.message ?: "未知错误"}")
                }
        }
    }

    fun toggleFavorite(entity: WallpaperEntity) { viewModelScope.launch { repo.toggleFavorite(entity) } }

    fun togglePlaylist(entity: WallpaperEntity) {
        viewModelScope.launch {
            repo.togglePlaylist(entity)
            _events.send(if (entity.inPlaylist) "已从播放列表移除" else "已加入播放列表")
        }
    }

    fun addToPlaylist(entities: List<WallpaperEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            repo.addToPlaylist(entities)
            _events.send("已加入播放列表：${entities.size} 项")
        }
    }

    fun removeFromPlaylist(entities: List<WallpaperEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            repo.removeFromPlaylist(entities)
            _events.send("已从播放列表移除：${entities.size} 项")
        }
    }

    fun reorderPlaylist(ordered: List<WallpaperEntity>) { viewModelScope.launch { repo.reorderPlaylist(ordered) } }

    fun updateRotateInterval(seconds: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            VideoWallpaperSettings.updateRotateInterval(appContext, seconds)
            val settings = VideoWallpaperSettings.read(appContext)
            launch(Dispatchers.Main) { _playlistSettings.value = settings }
        }
    }

    fun updateRandomEnabled(enabled: Boolean) {
        updatePlaybackMode(if (enabled) VideoWallpaperSettings.PlaybackMode.RANDOM else VideoWallpaperSettings.PlaybackMode.SEQUENTIAL)
    }

    fun updatePlaybackMode(mode: VideoWallpaperSettings.PlaybackMode) {
        viewModelScope.launch(Dispatchers.IO) {
            PlaybackOrderCoordinator.switchMode(appContext, mode, sequentialReversed = if (mode == VideoWallpaperSettings.PlaybackMode.SEQUENTIAL) false else null)
            val settings = VideoWallpaperSettings.read(appContext)
            launch(Dispatchers.Main) { _playlistSettings.value = settings }
        }
    }

    fun applyPlaylistWallpaper() {
        viewModelScope.launch(Dispatchers.IO) {
            val firstItem = repo.playlistSnapshot().firstOrNull()
            if (firstItem == null) {
                _events.send("播放列表是空的，请先导入图片或视频")
                return@launch
            }
            val result = VideoWallpaperStarter.openSystemLiveWallpaperPage(appContext, Uri.parse(firstItem.uri), forceOpenChooser = true)
            _events.send(result.message)
        }
    }

    fun forceOpenLiveWallpaperChooser() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentUriStr = VideoWallpaperStarter.loadVideoUri(appContext)?.toString()
            val uri = if (currentUriStr != null) Uri.parse(currentUriStr) else {
                val firstItem = repo.playlistSnapshot().firstOrNull()
                if (firstItem == null) {
                    _events.send("播放列表是空的，请先导入图片或视频")
                    return@launch
                }
                Uri.parse(firstItem.uri)
            }
            _events.send(VideoWallpaperStarter.openSystemLiveWallpaperPage(appContext, uri, forceOpenChooser = true).message)
        }
    }

    fun showPreviousWallpaper() { viewModelScope.launch(Dispatchers.IO) { playbackRepo.submitPrevious(source = "library") } }
    fun showNextWallpaper() { viewModelScope.launch(Dispatchers.IO) { playbackRepo.submitNext(source = "library") } }

    fun applyWallpaper(entity: WallpaperEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val resultPlan = PlaybackOrderCoordinator.restartFrom(appContext, entity)
            val result = VideoWallpaperStarter.openSystemLiveWallpaperPage(appContext, Uri.parse(entity.uri), forceOpenChooser = true)
            val settings = VideoWallpaperSettings.read(appContext)
            val planDescription = when (resultPlan.mode) {
                VideoWallpaperSettings.PlaybackMode.SEQUENTIAL -> if (settings.sequentialReversed) "以所选壁纸为第一项，已生成旧→新的完整列表" else "以所选壁纸为第一项，已生成新→旧的完整列表"
                VideoWallpaperSettings.PlaybackMode.SHUFFLE -> "以所选壁纸为第一项，已生成完整洗牌列表"
                VideoWallpaperSettings.PlaybackMode.RANDOM -> "以所选壁纸为第一项，已生成完整随机列表"
            }
            _events.send("$planDescription（${resultPlan.queueSize} 项）。${result.message}")
        }
    }

    fun migrateIndexWallpapersToLocal() {
        viewModelScope.launch(Dispatchers.IO) {
            _events.send("正在迁移外部索引壁纸至本地副本...")
            val count = repo.migrateAllIndexToLocal { current, total -> viewModelScope.launch { _events.send("迁移进度: $current / $total") } }
            _events.send("迁移完成！共将 $count 张壁纸复制到本地存储。")
            refreshLocalCopyStats()
        }
    }

    fun jumpToPlaylistWallpaper(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playbackRepo.submitApply(uri, source = "playlist")
            _events.send("正在应用选中壁纸…")
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            repo.clearPlaylist()
            _events.send("已清空播放列表")
        }
    }

    fun refreshLocalCopyStats() {
        viewModelScope.launch {
            val before = libraryIdsSnapshot()
            repo.syncLocalImportDir()
            autoAddNewImports(before)
            _localCopyStats.value = repo.localCopyStats()
        }
    }

    fun syncLocalImportDir(showMessage: Boolean = true) {
        viewModelScope.launch {
            val before = libraryIdsSnapshot()
            val result = repo.syncLocalImportDir()
            val autoAdded = autoAddNewImports(before)
            _localCopyStats.value = repo.localCopyStats()
            if (showMessage && (result.addedTotal > 0 || result.removedMissing > 0)) {
                _events.send("已同步复制保存目录：新增 ${result.addedTotal} 项，自动加入轮播 $autoAdded 项，移除失效 ${result.removedMissing} 项")
            }
        }
    }

    fun openLocalCopyDirectory() {
        viewModelScope.launch {
            val opened = repo.openLocalCopyDirectory()
            val stats = repo.localCopyStats()
            _localCopyStats.value = stats
            _events.send(if (opened) "已尝试打开复制保存目录" else "系统文件管理器无法直接打开，请手动查看：${stats.directoryPath}")
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            var totalBytes = 0L
            val cacheDirs = listOf(appContext.cacheDir, appContext.externalCacheDir).filterNotNull()
            for (dir in cacheDirs) totalBytes += dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
            _cacheSize.value = formatBytes(totalBytes)
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearAppCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loader = appContext.imageLoader
                loader.diskCache?.clear()
                loader.memoryCache?.clear()
            } catch (e: Throwable) {
                Log.w("LibraryViewModel", "Coil cache clean failed", e)
            }
            try {
                appContext.cacheDir.deleteRecursively()
                appContext.externalCacheDir?.deleteRecursively()
            } catch (e: Throwable) {
                Log.w("LibraryViewModel", "Cache dir clean failed", e)
            }
            refreshCacheSize()
            _events.send("运行缓存已清理完毕")
        }
    }

    private suspend fun importCollectedUris(uris: List<Uri>, groupTag: String?) {
        if (uris.isEmpty()) {
            _events.send("未读取到可导入的图片或视频")
            return
        }
        val preflight = importDeduplicator.preflight(repo, uris)
        if (preflight.acceptedUris.isEmpty()) return
        val before = libraryIdsSnapshot()
        val raw = repo.importFromUrisCopied(preflight.acceptedUris)
        if (preflight.duplicateCount > 0) importDeduplicator.resolveBatchTargets(repo, preflight, before)
        val result = raw.copy(skipped = raw.skipped + preflight.duplicateCount)
        importGroupAssigner.assignFresh(before, groupTag)
        val autoAdded = autoAddNewImports(before)
        _events.send(importMessage(result, autoAdded, preflight.duplicateCount, groupTag))
    }

    private data class DeleteResult(val deleted: Int, val keptActive: Int)

    /**
     * Never remove the backing file of the renderer-visible/desired wallpaper until another item has
     * actually reached APPLIED. This turns deletion of the current item into a renderer-confirmed
     * handoff instead of a storage race that can leave the live wallpaper black.
     */
    private suspend fun deleteManyWithRuntimeHandoff(requested: List<WallpaperEntity>): DeleteResult {
        val requestedIds = requested.mapTo(linkedSetOf()) { it.id }
        val all = repo.allSnapshot()
        val fresh = all.filter { it.id in requestedIds }
        if (fresh.isEmpty()) return DeleteResult(0, 0)

        val session = playbackRepo.getSession()
        val liveUris = setOfNotNull(session.appliedUri, session.desiredUri)
        val activeTargets = fresh.filter { it.uri in liveUris }
        if (activeTargets.isEmpty()) {
            return DeleteResult(repo.deleteMany(fresh), 0)
        }

        val deletingUris = fresh.mapTo(hashSetOf()) { it.uri }
        val remaining = all.filter { it.uri !in deletingUris }
        val fallback = remaining.firstOrNull { it.inPlaylist } ?: remaining.firstOrNull()
        if (fallback == null) {
            val safeOnly = fresh.filterNot { it.uri in liveUris }
            val deleted = if (safeOnly.isEmpty()) 0 else repo.deleteMany(safeOnly)
            WallpaperDebugLog.w(
                "library_delete",
                "active_delete_blocked_no_fallback",
                "requested" to fresh.size,
                "activeKept" to activeTargets.size,
                "applied" to WallpaperDebugLog.uri(session.appliedUri),
                "desired" to WallpaperDebugLog.uri(session.desiredUri),
            )
            return DeleteResult(deleted, activeTargets.size)
        }

        WallpaperDebugLog.i(
            "library_delete",
            "active_delete_handoff_start",
            "requested" to fresh.size,
            "activeTargets" to activeTargets.size,
            "from" to WallpaperDebugLog.uri(session.appliedUri),
            "fallback" to WallpaperDebugLog.uri(fallback.uri),
        )
        PlaybackOrderCoordinator.restartFrom(appContext, fallback)
        playbackRepo.submitApply(fallback.uri, source = "library_delete_handoff")
        val confirmed = withTimeoutOrNull(DELETE_HANDOFF_TIMEOUT_MS) {
            playbackRepo.observeSession().first { state ->
                state.appliedUri == fallback.uri && state.phase == PlaybackPhase.APPLIED
            }
        }

        if (confirmed == null) {
            val latest = playbackRepo.getSession()
            val latestLive = setOfNotNull(latest.appliedUri, latest.desiredUri)
            val safeOnly = fresh.filterNot { it.uri in latestLive }
            val deleted = if (safeOnly.isEmpty()) 0 else repo.deleteMany(safeOnly)
            val kept = fresh.size - safeOnly.size
            WallpaperDebugLog.w(
                "library_delete",
                "active_delete_handoff_timeout",
                "timeoutMs" to DELETE_HANDOFF_TIMEOUT_MS,
                "fallback" to WallpaperDebugLog.uri(fallback.uri),
                "latestApplied" to WallpaperDebugLog.uri(latest.appliedUri),
                "latestDesired" to WallpaperDebugLog.uri(latest.desiredUri),
                "deletedSafe" to deleted,
                "kept" to kept,
            )
            return DeleteResult(deleted, kept)
        }

        WallpaperDebugLog.i(
            "library_delete",
            "active_delete_handoff_confirmed",
            "fallback" to WallpaperDebugLog.uri(fallback.uri),
            "phase" to confirmed.phase,
            "revision" to confirmed.queueRevision,
        )
        return DeleteResult(repo.deleteMany(fresh), 0)
    }

    private suspend fun libraryIdsSnapshot(): Set<String> = repo.allSnapshot().mapTo(HashSet()) { it.id }

    private suspend fun reconcileExactImports(
        beforeIds: Set<String>,
        raw: WallpaperRepository.ImportResult,
    ): Pair<WallpaperRepository.ImportResult, Int> {
        if (raw.total <= 0) return raw to 0
        val decision = importDeduplicator.inspectNewImports(repo, beforeIds)
        if (decision.exactDuplicates.isEmpty()) return raw to 0
        repo.deleteMany(decision.exactDuplicates)
        val duplicateImages = decision.exactDuplicates.count { it.type == WallpaperType.STATIC }
        val duplicateVideos = decision.exactDuplicates.size - duplicateImages
        val adjusted = raw.copy(
            addedImage = (raw.addedImage - duplicateImages).coerceAtLeast(0),
            addedVideo = (raw.addedVideo - duplicateVideos).coerceAtLeast(0),
            skipped = raw.skipped + decision.exactDuplicates.size,
        )
        return adjusted to decision.exactDuplicates.size
    }

    private suspend fun autoAddNewImports(beforeIds: Set<String>): Int {
        val fresh = repo.allSnapshot().filter { it.id !in beforeIds }
        if (fresh.isEmpty()) return 0
        val result = PlaybackOrderCoordinator.addMembers(appContext, fresh)
        WallpaperDebugLog.i(
            "import", "batch_auto_added_to_carousel", "imported" to fresh.size, "queueSize" to result.queueSize,
            "current" to WallpaperDebugLog.uri(result.currentUri), "first" to WallpaperDebugLog.uri(result.firstUri), "revision" to result.revision,
        )
        return fresh.size
    }

    private fun duplicateImportMessage(preflight: Round4ImportDeduplicator.Preflight): String {
        val names = preflight.duplicateNames.take(3).joinToString("、")
        val suffix = if (preflight.duplicateCount > 3) " 等 ${preflight.duplicateCount} 项" else ""
        return "发现重复壁纸：$names$suffix；与图库已有内容完全相同，已在复制和入库前跳过"
    }

    private fun importMessage(
        r: WallpaperRepository.ImportResult,
        autoAdded: Int = 0,
        exactDuplicates: Int = 0,
        groupTag: String? = null,
    ): String {
        val parts = buildList {
            if (r.addedImage > 0) add("图片 ${r.addedImage}")
            if (r.addedVideo > 0) add("视频 ${r.addedVideo}")
        }
        val otherSkipped = (r.skipped - exactDuplicates).coerceAtLeast(0)
        val suffix = buildList {
            if (exactDuplicates > 0) add("完全重复 $exactDuplicates 个")
            if (otherSkipped > 0) add("其他跳过 $otherSkipped 个")
        }.joinToString("，")
        val base = when {
            r.total == 0 && suffix.isNotBlank() -> "未新增；$suffix"
            r.total == 0 -> "未导入任何资源"
            suffix.isNotBlank() -> "已导入 ${parts.joinToString(" / ")}；$suffix"
            else -> "已导入 ${parts.joinToString(" / ")}"
        }
        val grouped = groupTag?.trim()?.takeIf { it.isNotBlank() }?.let { "$base；已归入「${it.take(48)}」" } ?: base
        return if (autoAdded > 0) "$grouped；已自动加入轮播并重生成完整列表 $autoAdded 项" else grouped
    }

    private fun formatBytes(bytes: Long): String = MediaMetadataFormatter.formatBytes(bytes)

    private companion object {
        const val KEY_GALLERY_VIEW_MODE = "gallery_view_mode"
        const val DELETE_HANDOFF_TIMEOUT_MS = 5_000L
    }
}
