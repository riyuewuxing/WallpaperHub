package com.wallpaperhub.ui.theme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class ColorDraft(val role: ThemeColorRole, val argb: Int)

class ThemeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ThemeRepository(app)
    private val persistedProfile = repository.profile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeProfile.default(),
    )
    private val draft = MutableStateFlow<ColorDraft?>(null)

    init {
        viewModelScope.launch { repository.migrateIfNeeded() }
    }

    /**
     * Picker motion may arrive at 120Hz. WallpaperHub only republishes live theme previews at about
     * 30Hz, which still looks immediate while avoiding whole-app theme invalidation on every pixel.
     */
    @OptIn(FlowPreview::class)
    private val sampledDraft = draft.sample(32L).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val profile: StateFlow<ThemeProfile> = combine(persistedProfile, sampledDraft) { base, preview ->
        if (preview == null) base else base.withColor(preview.role, preview.argb)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeProfile.default(),
    )

    fun previewColor(role: ThemeColorRole, argb: Int) {
        draft.value = ColorDraft(role, ThemeRepository.sanitizeArgb(role, argb))
    }

    /** One persistent write at gesture end / debounced text commit. */
    fun commitColor(role: ThemeColorRole, argb: Int) {
        val sanitized = ThemeRepository.sanitizeArgb(role, argb)
        val pending = ColorDraft(role, sanitized)
        draft.value = pending
        viewModelScope.launch {
            repository.updateColor(role, sanitized)
            if (draft.value == pending) draft.value = null
        }
    }

    /** Compatibility for discrete color taps. */
    fun updateColor(role: ThemeColorRole, argb: Int) = commitColor(role, argb)

    fun cancelColorPreview() { draft.value = null }

    fun applyPreset(preset: ThemePreset) {
        draft.value = null
        viewModelScope.launch { repository.applyPreset(preset) }
    }

    fun applyScene(scene: ScenePreset) {
        draft.value = null
        viewModelScope.launch { repository.applyScene(scene) }
    }

    fun reset() {
        draft.value = null
        viewModelScope.launch { repository.reset() }
    }

    fun updateBackgroundMotion(level: Int) { viewModelScope.launch { repository.updateBackgroundMotion(level) } }
    fun updateAmbientParticles(level: Int) { viewModelScope.launch { repository.updateAmbientParticles(level) } }
    fun updateInteractionEffects(level: Int) { viewModelScope.launch { repository.updateInteractionEffects(level) } }
    fun updateHaptics(level: Int) { viewModelScope.launch { repository.updateHaptics(level) } }
    fun updatePerformanceMode(mode: Int) { viewModelScope.launch { repository.updatePerformanceMode(mode) } }
    fun updateAuroraIntensity(level: Int) { viewModelScope.launch { repository.updateAuroraIntensity(level) } }
    fun updateContour(level: Int) { viewModelScope.launch { repository.updateContour(level) } }
    fun updateGrain(level: Int) { viewModelScope.launch { repository.updateGrain(level) } }
    fun updateVignette(level: Int) { viewModelScope.launch { repository.updateVignette(level) } }
    fun updateParticleSpeed(level: Int) { viewModelScope.launch { repository.updateParticleSpeed(level) } }
    fun updateSceneSpeed(level: Int) { viewModelScope.launch { repository.updateSceneSpeed(level) } }
    fun updateParallax(level: Int) { viewModelScope.launch { repository.updateParallax(level) } }
    fun updateEvents(level: Int) { viewModelScope.launch { repository.updateEvents(level) } }
}
