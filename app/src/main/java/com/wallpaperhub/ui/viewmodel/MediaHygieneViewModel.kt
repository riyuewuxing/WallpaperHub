package com.wallpaperhub.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.analysis.DuplicateKind
import com.wallpaperhub.analysis.DuplicateMember
import com.wallpaperhub.analysis.DuplicatePolicy
import com.wallpaperhub.analysis.ExactDuplicateScanner
import com.wallpaperhub.analysis.MediaDigest
import com.wallpaperhub.analysis.MediaSignalExtractor
import com.wallpaperhub.analysis.QualityFlag
import com.wallpaperhub.analysis.VisualDuplicateScanner
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.CURRENT_MEDIA_ANALYSIS_VERSION
import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.MediaAnalysisStatus
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.repo.MediaReviewDecisionStore
import com.wallpaperhub.data.repo.VisualScanSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaHygieneMode { EXACT, VISUAL, QUALITY }

data class DuplicateReviewGroup(
    val fingerprint: String,
    val members: List<WallpaperEntity>,
    val keeperId: String,
    val suggestedIds: Set<String>,
    val protectedIds: Set<String>,
    val liveIds: Set<String>,
    val totalBytes: Long,
    val kind: DuplicateKind = DuplicateKind.EXACT,
    val similarity: Double? = null,
)

data class QualityReviewItem(
    val entity: WallpaperEntity,
    val analysis: MediaAnalysisEntity,
    val protected: Boolean,
    val highConfidence: Boolean,
)

sealed interface MediaHygieneState {
    data object Idle : MediaHygieneState
    data class Scanning(val current: Int, val total: Int) : MediaHygieneState
    data class Ready(
        val groups: List<DuplicateReviewGroup>,
        val selectedIds: Set<String>,
        val scannedCount: Int,
        val failedCount: Int,
    ) : MediaHygieneState
    data class Failed(val message: String) : MediaHygieneState
}

sealed interface VisualHygieneState {
    data object Idle : VisualHygieneState
    data class Scanning(val current: Int, val total: Int) : VisualHygieneState
    data class Ready(
        val groups: List<DuplicateReviewGroup>,
        val selectedIds: Set<String>,
        val scannedCount: Int,
        val failedCount: Int,
    ) : VisualHygieneState
    data class Failed(val message: String) : VisualHygieneState
}

sealed interface QualityHygieneState {
    data object Idle : QualityHygieneState
    data class Scanning(val current: Int, val total: Int) : QualityHygieneState
    data class Ready(
        val items: List<QualityReviewItem>,
        val selectedIds: Set<String>,
        val scannedCount: Int,
        val failedCount: Int,
    ) : QualityHygieneState
    data class Failed(val message: String) : QualityHygieneState
}

class MediaHygieneViewModel(app: Application) : AndroidViewModel(app) {
    private val wallpaperHubApp = app as WallpaperHubApp
    private val wallpaperRepo = wallpaperHubApp.repository
    private val playbackRepo = wallpaperHubApp.playbackRepository
    private val analysisDao = AppDatabase.getInstance(app).mediaAnalysisDao()
    private val exactScanner = ExactDuplicateScanner(app.applicationContext, analysisDao)
    private val signalExtractor = MediaSignalExtractor(app.applicationContext, analysisDao)
    private val visualScanner = VisualDuplicateScanner(signalExtractor)
    private val reviewDecisions = MediaReviewDecisionStore(app.applicationContext)
    private val visualSnapshots = VisualScanSnapshotStore(app.applicationContext)
    private var visualRestoreStarted = false

    private val _mode = MutableStateFlow(MediaHygieneMode.EXACT)
    val mode: StateFlow<MediaHygieneMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow<MediaHygieneState>(MediaHygieneState.Idle)
    val state: StateFlow<MediaHygieneState> = _state.asStateFlow()

    private val _visualState = MutableStateFlow<VisualHygieneState>(VisualHygieneState.Idle)
    val visualState: StateFlow<VisualHygieneState> = _visualState.asStateFlow()

    private val _qualityState = MutableStateFlow<QualityHygieneState>(QualityHygieneState.Idle)
    val qualityState: StateFlow<QualityHygieneState> = _qualityState.asStateFlow()

    fun setMode(mode: MediaHygieneMode) {
        _mode.value = mode
        if (mode == MediaHygieneMode.VISUAL) ensureVisualState()
    }

    fun scanExactDuplicates() {
        viewModelScope.launch {
            runCatching {
                val wallpapers = withContext(Dispatchers.IO) { wallpaperRepo.allSnapshot() }
                val session = withContext(Dispatchers.IO) { playbackRepo.getSession() }
                val scan = exactScanner.scan(wallpapers) { current, total ->
                    _state.value = MediaHygieneState.Scanning(current, total)
                }
                val byId = wallpapers.associateBy { it.id }
                val groups = scan.groups.mapNotNull { exactGroup ->
                    val members = exactGroup.wallpaperIds.mapNotNull(byId::get)
                    if (members.size < 2) return@mapNotNull null
                    if (reviewDecisions.isGroupDismissed(MediaReviewDecisionStore.PairKind.EXACT, members)) return@mapNotNull null
                    val liveIds = liveProtectedIds(members, session.appliedUri, session.desiredUri)
                    val protectedIds = hardProtectedIds(members, liveIds)
                    val perFileBytes = exactGroup.totalBytes / members.size.coerceAtLeast(1)
                    val policyMembers = members.map { entity ->
                        DuplicateMember(
                            wallpaperId = entity.id,
                            sha256 = exactGroup.fingerprint,
                            width = entity.width,
                            height = entity.height,
                            fileSizeBytes = perFileBytes,
                            isCurrent = entity.id in liveIds,
                            inPlaylist = entity.inPlaylist,
                            isFavorite = entity.isFavorite,
                            cropRevision = entity.cropRevision,
                            addedAt = entity.addedAt,
                        )
                    }
                    val decision = DuplicatePolicy.recommendForGroup(policyMembers, DuplicateKind.EXACT)
                        ?: return@mapNotNull null
                    DuplicateReviewGroup(
                        fingerprint = exactGroup.fingerprint,
                        members = members,
                        keeperId = decision.keeperId,
                        suggestedIds = decision.suggestedDeleteIds,
                        protectedIds = protectedIds,
                        liveIds = liveIds,
                        totalBytes = exactGroup.totalBytes,
                        kind = DuplicateKind.EXACT,
                    )
                }
                MediaHygieneState.Ready(groups, emptySet(), scan.analyzed + scan.reused, scan.failed)
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = MediaHygieneState.Failed(it.message ?: "重复内容扫描失败") }
        }
    }

    fun scanVisualDuplicates() {
        viewModelScope.launch {
            runCatching {
                val wallpapers = withContext(Dispatchers.IO) { wallpaperRepo.allSnapshot() }
                val session = withContext(Dispatchers.IO) { playbackRepo.getSession() }
                val scan = visualScanner.scan(wallpapers) { current, total ->
                    _visualState.value = VisualHygieneState.Scanning(current, total)
                }
                val scannedCount = scan.analyzed + scan.reused
                withContext(Dispatchers.IO) {
                    val signature = visualSnapshots.librarySignature(wallpapers, scan.analysisRows)
                    visualSnapshots.save(signature, scan.groups, scannedCount, scan.failed)
                }
                val groups = buildVisualReviewGroups(
                    rawGroups = scan.groups,
                    wallpapers = wallpapers,
                    analysisRows = scan.analysisRows,
                    appliedUri = session.appliedUri,
                    desiredUri = session.desiredUri,
                )
                VisualHygieneState.Ready(groups, emptySet(), scannedCount, scan.failed)
            }.onSuccess { _visualState.value = it }
                .onFailure { _visualState.value = VisualHygieneState.Failed(it.message ?: "相似内容扫描失败") }
        }
    }

    fun scanQuality() {
        viewModelScope.launch {
            runCatching {
                val wallpapers = withContext(Dispatchers.IO) { wallpaperRepo.allSnapshot() }
                val session = withContext(Dispatchers.IO) { playbackRepo.getSession() }
                val scan = signalExtractor.scan(wallpapers, requireVisualFingerprint = false) { current, total ->
                    _qualityState.value = QualityHygieneState.Scanning(current, total)
                }
                val byId = wallpapers.associateBy { it.id }
                val liveUris = setOfNotNull(session.appliedUri, session.desiredUri)
                val items = scan.rows.asSequence()
                    .filter { it.status == MediaAnalysisStatus.READY && it.qualityScore != null && it.qualityFlags.isNotBlank() }
                    .mapNotNull { row ->
                        val entity = byId[row.wallpaperId] ?: return@mapNotNull null
                        if (reviewDecisions.isQualityDismissed(entity, row)) return@mapNotNull null
                        QualityReviewItem(entity, row, entity.uri in liveUris, isHighConfidenceQualityRisk(row))
                    }
                    .sortedWith(compareByDescending<QualityReviewItem> { it.highConfidence }
                        .thenBy { it.analysis.qualityScore ?: 100 }
                        .thenByDescending { it.analysis.upscaleFactor ?: 1.0 })
                    .toList()
                QualityHygieneState.Ready(items, emptySet(), scan.analyzed + scan.reused, scan.failed)
            }.onSuccess { _qualityState.value = it }
                .onFailure { _qualityState.value = QualityHygieneState.Failed(it.message ?: "画质分析失败") }
        }
    }

    fun toggleSelected(wallpaperId: String) {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        val keeperIds = ready.groups.mapTo(hashSetOf()) { it.keeperId }
        val protectedIds = ready.groups.flatMapTo(hashSetOf()) { it.protectedIds }
        if (wallpaperId in keeperIds || wallpaperId in protectedIds) return
        val next = if (wallpaperId in ready.selectedIds) ready.selectedIds - wallpaperId else ready.selectedIds + wallpaperId
        _state.value = ready.copy(selectedIds = next)
    }

    fun selectAllSuggested() {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        val selectable = ready.groups.flatMapTo(linkedSetOf()) { group ->
            group.suggestedIds.filter { it != group.keeperId && it !in group.protectedIds }
        }
        _state.value = ready.copy(selectedIds = selectable)
    }

    fun clearSelection() {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        _state.value = ready.copy(selectedIds = emptySet())
    }

    fun setKeeper(fingerprint: String, keeperId: String) {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        val updated = updateKeeper(ready.groups, fingerprint, keeperId)
        _state.value = ready.copy(groups = updated, selectedIds = retainValidSelection(updated, ready.selectedIds))
    }

    fun dismissExactGroup(fingerprint: String) {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        val group = ready.groups.firstOrNull { it.fingerprint == fingerprint } ?: return
        reviewDecisions.dismissGroup(MediaReviewDecisionStore.PairKind.EXACT, group.members)
        val updated = ready.groups.filterNot { it.fingerprint == fingerprint }
        _state.value = ready.copy(groups = updated, selectedIds = retainValidSelection(updated, ready.selectedIds))
    }

    fun toggleVisualSelected(wallpaperId: String) {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        val keeperIds = ready.groups.mapTo(hashSetOf()) { it.keeperId }
        val protectedIds = ready.groups.flatMapTo(hashSetOf()) { it.protectedIds }
        if (wallpaperId in keeperIds || wallpaperId in protectedIds) return
        val next = if (wallpaperId in ready.selectedIds) ready.selectedIds - wallpaperId else ready.selectedIds + wallpaperId
        _visualState.value = ready.copy(selectedIds = next)
    }

    fun selectAllVisualSuggested() {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        val selectable = ready.groups.flatMapTo(linkedSetOf()) { group ->
            group.suggestedIds.filter { it != group.keeperId && it !in group.protectedIds }
        }
        _visualState.value = ready.copy(selectedIds = selectable)
    }

    fun clearVisualSelection() {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        _visualState.value = ready.copy(selectedIds = emptySet())
    }

    fun setVisualKeeper(fingerprint: String, keeperId: String) {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        val updated = updateKeeper(ready.groups, fingerprint, keeperId, recomputeSuggestions = false)
        _visualState.value = ready.copy(groups = updated, selectedIds = retainValidSelection(updated, ready.selectedIds))
    }

    fun dismissVisualGroup(fingerprint: String) {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        val group = ready.groups.firstOrNull { it.fingerprint == fingerprint } ?: return
        reviewDecisions.dismissGroup(MediaReviewDecisionStore.PairKind.VISUAL, group.members)
        val updated = ready.groups.filterNot { it.fingerprint == fingerprint }
        _visualState.value = ready.copy(groups = updated, selectedIds = retainValidSelection(updated, ready.selectedIds))
    }

    fun toggleQualitySelected(wallpaperId: String) {
        val ready = _qualityState.value as? QualityHygieneState.Ready ?: return
        val item = ready.items.firstOrNull { it.entity.id == wallpaperId } ?: return
        if (item.protected) return
        val next = if (wallpaperId in ready.selectedIds) ready.selectedIds - wallpaperId else ready.selectedIds + wallpaperId
        _qualityState.value = ready.copy(selectedIds = next)
    }

    fun clearQualitySelection() {
        val ready = _qualityState.value as? QualityHygieneState.Ready ?: return
        _qualityState.value = ready.copy(selectedIds = emptySet())
    }

    fun dismissQuality(wallpaperId: String) {
        val ready = _qualityState.value as? QualityHygieneState.Ready ?: return
        val item = ready.items.firstOrNull { it.entity.id == wallpaperId } ?: return
        reviewDecisions.dismissQuality(item.entity, item.analysis)
        _qualityState.value = ready.copy(
            items = ready.items.filterNot { it.entity.id == wallpaperId },
            selectedIds = ready.selectedIds - wallpaperId,
        )
    }

    fun removeSelected(onComplete: (Int) -> Unit = {}) {
        val ready = _state.value as? MediaHygieneState.Ready ?: return
        val protectedIds = ready.groups.flatMapTo(hashSetOf()) { it.protectedIds }
        val keeperIds = ready.groups.mapTo(hashSetOf()) { it.keeperId }
        val effectiveIds = ready.selectedIds - protectedIds - keeperIds
        val selectedEntities = ready.groups.flatMap { it.members }.distinctBy { it.id }.filter { it.id in effectiveIds }
        removeSafely(selectedEntities, onComplete) { scanExactDuplicates() }
    }

    fun removeVisualSelected(onComplete: (Int) -> Unit = {}) {
        val ready = _visualState.value as? VisualHygieneState.Ready ?: return
        val protectedIds = ready.groups.flatMapTo(hashSetOf()) { it.protectedIds }
        val keeperIds = ready.groups.mapTo(hashSetOf()) { it.keeperId }
        val effectiveIds = ready.selectedIds - protectedIds - keeperIds
        val selectedEntities = ready.groups.flatMap { it.members }.distinctBy { it.id }.filter { it.id in effectiveIds }
        removeSafely(selectedEntities, onComplete) { scanVisualDuplicates() }
    }

    fun removeQualitySelected(onComplete: (Int) -> Unit = {}) {
        val ready = _qualityState.value as? QualityHygieneState.Ready ?: return
        val selectedEntities = ready.items.filter { !it.protected && it.entity.id in ready.selectedIds }.map { it.entity }
        removeSafely(selectedEntities, onComplete) { scanQuality() }
    }

    private fun ensureVisualState() {
        if (_visualState.value !is VisualHygieneState.Idle || visualRestoreStarted) return
        visualRestoreStarted = true
        viewModelScope.launch {
            val shouldRefresh = runCatching {
                val wallpapers = withContext(Dispatchers.IO) { wallpaperRepo.allSnapshot() }
                val analysisRows = withContext(Dispatchers.IO) { analysisDao.allRows() }
                if (wallpapers.isEmpty()) {
                    _visualState.value = VisualHygieneState.Ready(emptyList(), emptySet(), 0, 0)
                    return@runCatching false
                }
                val signature = withContext(Dispatchers.IO) {
                    visualSnapshots.librarySignature(wallpapers, analysisRows)
                }
                val snapshot = withContext(Dispatchers.IO) { visualSnapshots.load() }
                if (snapshot != null && snapshot.librarySignature == signature) {
                    val session = withContext(Dispatchers.IO) { playbackRepo.getSession() }
                    val groups = buildVisualReviewGroups(
                        rawGroups = snapshot.groups,
                        wallpapers = wallpapers,
                        analysisRows = analysisRows,
                        appliedUri = session.appliedUri,
                        desiredUri = session.desiredUri,
                    )
                    _visualState.value = VisualHygieneState.Ready(
                        groups = groups,
                        selectedIds = emptySet(),
                        scannedCount = snapshot.scannedCount,
                        failedCount = snapshot.failedCount,
                    )
                    false
                } else {
                    hasPersistedVisualBaseline(wallpapers, analysisRows)
                }
            }.getOrDefault(false)
            visualRestoreStarted = false
            if (shouldRefresh && _visualState.value is VisualHygieneState.Idle) {
                scanVisualDuplicates()
            }
        }
    }

    /**
     * Existing Room rows mean the expensive frame extraction has already happened. If a snapshot
     * file does not exist yet (for example the first install containing this fix), rebuild only the
     * lightweight grouping automatically; MediaSignalExtractor will reuse unchanged rows.
     */
    private fun hasPersistedVisualBaseline(
        wallpapers: List<WallpaperEntity>,
        analysisRows: List<MediaAnalysisEntity>,
    ): Boolean {
        val byId = analysisRows.associateBy { it.wallpaperId }
        return wallpapers.all { wallpaper ->
            val row = byId[wallpaper.id] ?: return@all false
            row.analysisVersion == CURRENT_MEDIA_ANALYSIS_VERSION &&
                row.uri == wallpaper.uri &&
                (row.status == MediaAnalysisStatus.FAILED ||
                    (row.status == MediaAnalysisStatus.READY && row.visualHashSequence.isNotBlank()))
        }
    }

    private fun buildVisualReviewGroups(
        rawGroups: List<VisualDuplicateScanner.Group>,
        wallpapers: List<WallpaperEntity>,
        analysisRows: List<MediaAnalysisEntity>,
        appliedUri: String?,
        desiredUri: String?,
    ): List<DuplicateReviewGroup> {
        val byId = wallpapers.associateBy { it.id }
        val analysisById = analysisRows.associateBy { it.wallpaperId }
        return rawGroups.mapNotNull { visualGroup ->
            val members = visualGroup.wallpaperIds.mapNotNull(byId::get)
            if (members.size < 2) return@mapNotNull null
            if (reviewDecisions.isGroupDismissed(MediaReviewDecisionStore.PairKind.VISUAL, members)) return@mapNotNull null
            val liveIds = liveProtectedIds(members, appliedUri, desiredUri)
            val protectedIds = hardProtectedIds(members, liveIds)
            val policyMembers = members.map { entity ->
                val analysis = analysisById[entity.id]
                DuplicateMember(
                    wallpaperId = entity.id,
                    sha256 = analysis?.sha256,
                    visualHashes = MediaDigest.decodeHashSequence(analysis?.visualHashSequence),
                    width = analysis?.width ?: entity.width,
                    height = analysis?.height ?: entity.height,
                    durationMs = analysis?.durationMs ?: entity.duration,
                    qualityScore = analysis?.qualityScore,
                    fileSizeBytes = analysis?.sourceSizeBytes?.coerceAtLeast(0L) ?: 0L,
                    isCurrent = entity.id in liveIds,
                    inPlaylist = entity.inPlaylist,
                    isFavorite = entity.isFavorite,
                    cropRevision = entity.cropRevision,
                    addedAt = entity.addedAt,
                )
            }
            val decision = DuplicatePolicy.recommendForGroup(policyMembers, visualGroup.kind)
            val keeperId = decision?.keeperId ?: fallbackKeeper(members, protectedIds, analysisById)
            DuplicateReviewGroup(
                fingerprint = visualGroup.fingerprint,
                members = members,
                keeperId = keeperId,
                suggestedIds = decision?.suggestedDeleteIds.orEmpty(),
                protectedIds = protectedIds,
                liveIds = liveIds,
                totalBytes = visualGroup.totalBytes,
                kind = visualGroup.kind,
                similarity = visualGroup.similarity,
            )
        }
    }

    private fun removeSafely(entities: List<WallpaperEntity>, onComplete: (Int) -> Unit, rescan: () -> Unit) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            val latestSession = withContext(Dispatchers.IO) { playbackRepo.getSession() }
            val liveUris = setOfNotNull(latestSession.appliedUri, latestSession.desiredUri)
            val safeEntities = entities.filterNot { it.uri in liveUris }
            if (safeEntities.isEmpty()) return@launch
            val removed = wallpaperRepo.deleteMany(safeEntities)
            onComplete(removed)
            rescan()
        }
    }

    private fun liveProtectedIds(members: List<WallpaperEntity>, appliedUri: String?, desiredUri: String?): Set<String> =
        members.asSequence()
            .filter { it.uri == appliedUri || it.uri == desiredUri }
            .map { it.id }
            .toCollection(linkedSetOf())

    /** Only the actually live/applied target is a hard lock. */
    private fun hardProtectedIds(members: List<WallpaperEntity>, liveIds: Set<String>): Set<String> {
        val memberIds = members.asSequence().map { it.id }.toHashSet()
        return liveIds.filterTo(linkedSetOf()) { it in memberIds }
    }

    private fun updateKeeper(
        groups: List<DuplicateReviewGroup>,
        fingerprint: String,
        keeperId: String,
        recomputeSuggestions: Boolean = true,
    ): List<DuplicateReviewGroup> = groups.map { group ->
        if (group.fingerprint != fingerprint || group.members.none { it.id == keeperId }) return@map group
        if (group.protectedIds.isNotEmpty() && keeperId !in group.protectedIds) return@map group
        val suggested = if (recomputeSuggestions) {
            group.members.asSequence().map { it.id }.filter { it != keeperId && it !in group.protectedIds }.toSet()
        } else {
            group.suggestedIds - keeperId
        }
        group.copy(keeperId = keeperId, suggestedIds = suggested)
    }

    private fun retainValidSelection(groups: List<DuplicateReviewGroup>, selected: Set<String>): Set<String> {
        val keeperIds = groups.mapTo(hashSetOf()) { it.keeperId }
        val protectedIds = groups.flatMapTo(hashSetOf()) { it.protectedIds }
        val validMemberIds = groups.flatMapTo(hashSetOf()) { it.members.map { entity -> entity.id } }
        return selected.filterTo(linkedSetOf()) { it in validMemberIds && it !in keeperIds && it !in protectedIds }
    }

    private fun fallbackKeeper(
        members: List<WallpaperEntity>,
        protectedIds: Set<String>,
        analysisById: Map<String, MediaAnalysisEntity>,
    ): String = members.maxWithOrNull(
        compareBy<WallpaperEntity> { if (it.id in protectedIds) 1 else 0 }
            .thenBy { if (it.cropRevision > 0L) 1 else 0 }
            .thenBy { if (it.inPlaylist) 1 else 0 }
            .thenBy { if (it.isFavorite) 1 else 0 }
            .thenBy { analysisById[it.id]?.qualityScore ?: 0 }
            .thenBy { (it.width?.toLong() ?: 0L) * (it.height?.toLong() ?: 0L) }
            .thenBy { -it.addedAt }
    )?.id ?: members.first().id

    private fun isHighConfidenceQualityRisk(row: MediaAnalysisEntity): Boolean {
        val flags = row.qualityFlags.split(',').mapNotNull { raw ->
            runCatching { QualityFlag.valueOf(raw.trim()) }.getOrNull()
        }.toSet()
        val upscale = row.upscaleFactor ?: 1.0
        val detail = row.detailScore
        val veryLowDetail = QualityFlag.VERY_LOW_DETAIL in flags
        val veryLowBitrate = QualityFlag.VERY_LOW_VIDEO_BITRATE in flags
        val extremeBlur = detail != null && detail < 0.10
        val severeUpscale = upscale >= 2.0
        val multiSignal = flags.count {
            it == QualityFlag.SEVERE_UPSCALE || it == QualityFlag.VERY_LOW_DETAIL || it == QualityFlag.VERY_LOW_VIDEO_BITRATE
        } >= 2
        return extremeBlur || severeUpscale ||
            (veryLowDetail && upscale >= 1.25) ||
            (veryLowBitrate && (veryLowDetail || upscale >= 1.25)) ||
            ((row.qualityScore ?: 100) <= 25 && multiSignal)
    }
}
