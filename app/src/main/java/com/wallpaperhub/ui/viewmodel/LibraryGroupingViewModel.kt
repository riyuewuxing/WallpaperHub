package com.wallpaperhub.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.repo.Round4ImportDeduplicator
import com.wallpaperhub.playback.engine.PlaybackOrderCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Round 4 grouping metadata; does not write playback session/crop state. */
class LibraryGroupingViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val database = AppDatabase.getInstance(app)
    private val dao = database.wallpaperDao()
    private val analysisDao = database.mediaAnalysisDao()
    private val repository = (app as WallpaperHubApp).repository
    private val importDeduplicator = Round4ImportDeduplicator(appContext)

    val wallpapers: StateFlow<List<WallpaperEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupTags = wallpapers
        .map { items ->
            items.asSequence()
                .mapNotNull { it.groupTag?.trim()?.takeIf(String::isNotBlank) }
                .distinctBy { it.lowercase() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun assignGroup(wallpaperIds: Set<String>, rawTag: String?) {
        if (wallpaperIds.isEmpty()) return
        val normalized = rawTag?.trim()?.takeIf { it.isNotBlank() }?.take(48)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fresh = buildList<WallpaperEntity> {
                    for (id in wallpaperIds) {
                        dao.findById(id)?.let(::add)
                    }
                }
                if (fresh.isNotEmpty()) {
                    dao.updateAll(fresh.map { entity -> entity.copy(groupTag = normalized) })
                }
            }
        }
    }

    fun importIntoGroup(uris: List<Uri>, rawTag: String) {
        if (uris.isEmpty()) return
        val group = rawTag.trim().take(48)
        if (group.isBlank()) {
            _importMessage.value = "请先输入或选择目标分组"
            return
        }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val beforeIds = repository.allSnapshot().mapTo(hashSetOf()) { it.id }
                importDeduplicator.prepareBaseline(repository, beforeIds)
                val raw = repository.importFromUrisCopied(uris)
                val decision = if (raw.total > 0) {
                    importDeduplicator.inspectNewImports(repository, beforeIds)
                } else {
                    Round4ImportDeduplicator.Decision(emptyList())
                }

                if (decision.exactDuplicates.isNotEmpty()) {
                    repository.deleteMany(decision.exactDuplicates)
                    analysisDao.deleteByWallpaperIds(decision.exactDuplicates.map { it.id })
                }

                val fresh = repository.allSnapshot().filter { it.id !in beforeIds }
                if (fresh.isNotEmpty()) {
                    val grouped = fresh.map { it.copy(groupTag = group) }
                    dao.updateAll(grouped)
                    PlaybackOrderCoordinator.addMembers(appContext, grouped)
                }

                buildString {
                    append("已导入到「").append(group).append("」：新增 ").append(fresh.size).append(" 项")
                    if (decision.exactDuplicates.isNotEmpty()) {
                        append("，拦截完全重复 ").append(decision.exactDuplicates.size).append(" 项")
                    }
                    if (raw.skipped > 0) {
                        append("，其他跳过 ").append(raw.skipped).append(" 项")
                    }
                }
            }
            _importMessage.value = message
        }
    }
}
