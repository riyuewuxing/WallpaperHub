package com.wallpaperhub.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wallpaperhub.WallpaperHubApp
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.playback.model.PlaybackSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页 ViewModel：根据 wallpaperId 加载条目，提供"应用为壁纸"操作。
 */
class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as WallpaperHubApp).repository
    private val playbackRepo = (app as WallpaperHubApp).playbackRepository

    private val _entity = MutableStateFlow<WallpaperEntity?>(null)
    val entity: StateFlow<WallpaperEntity?> = _entity

    private val _fileSize = MutableStateFlow<Long?>(null)
    val fileSize: StateFlow<Long?> = _fileSize

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    val playbackSession: StateFlow<PlaybackSession> = playbackRepo.observeSession().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaybackSession(),
    )

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun load(id: String) {
        viewModelScope.launch {
            val item = repo.findById(id)
            _entity.value = item
            _fileSize.value = item?.let { repo.resolveFileSize(it.uri) }
        }
    }

    fun toggleFavorite() {
        val e = _entity.value ?: return
        viewModelScope.launch {
            repo.toggleFavorite(e)
            _entity.value = repo.findById(e.id)
        }
    }

    fun togglePlaylist() {
        val e = _entity.value ?: return
        viewModelScope.launch {
            repo.togglePlaylist(e)
            val refreshed = repo.findById(e.id)
            _entity.value = refreshed
            _events.send(
                if (refreshed?.inPlaylist == true) "已加入播放列表"
                else "已从播放列表移除"
            )
        }
    }
}
