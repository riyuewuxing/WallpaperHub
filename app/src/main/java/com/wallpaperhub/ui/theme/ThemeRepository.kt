package com.wallpaperhub.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wallpaperThemeDataStore by preferencesDataStore(name = "wallpaperhub_theme")

class ThemeRepository(context: Context) {
    private val dataStore = context.applicationContext.wallpaperThemeDataStore

    val profile: Flow<ThemeProfile> = dataStore.data.map(::decode)

    suspend fun migrateIfNeeded() {
        dataStore.edit { prefs ->
            val storedSchema = prefs[KEY_SCHEMA] ?: 1
            if (storedSchema >= ThemeProfile.CURRENT_SCHEMA_VERSION) return@edit

            val storedPreset = ThemePreset.fromId(prefs[KEY_PRESET])
            var migrated = if (storedPreset == ThemePreset.CUSTOM) {
                ThemePreset.AQUA_NOIR.profile().copy(presetId = ThemePreset.CUSTOM.id)
            } else {
                storedPreset.profile().copy(presetId = storedPreset.id)
            }
            ThemeColorRole.entries.filterNot(::isSceneColorRole).forEach { role ->
                prefs[colorKey(role)]?.let { migrated = migrated.withColor(role, sanitizeArgb(role, it)) }
            }
            migrated = ScenePreset.EVENT_HORIZON.applyScenePalette(migrated).copy(
                schemaVersion = ThemeProfile.CURRENT_SCHEMA_VERSION,
                backgroundMotionLevel = prefs[KEY_BACKGROUND_MOTION] ?: migrated.backgroundMotionLevel,
                ambientParticleLevel = prefs[KEY_AMBIENT_PARTICLES] ?: migrated.ambientParticleLevel,
                interactionEffectLevel = prefs[KEY_INTERACTION_EFFECTS] ?: migrated.interactionEffectLevel,
                hapticLevel = prefs[KEY_HAPTICS] ?: migrated.hapticLevel,
                performanceMode = prefs[KEY_PERFORMANCE] ?: migrated.performanceMode,
                auroraIntensityLevel = prefs[KEY_AURORA_INTENSITY] ?: migrated.auroraIntensityLevel,
                contourLevel = prefs[KEY_CONTOUR] ?: migrated.contourLevel,
                grainLevel = prefs[KEY_GRAIN] ?: migrated.grainLevel,
                vignetteLevel = prefs[KEY_VIGNETTE] ?: migrated.vignetteLevel,
                particleSpeedLevel = prefs[KEY_PARTICLE_SPEED] ?: migrated.particleSpeedLevel,
            )
            prefs.clear()
            writeProfileInto(prefs, migrated)
        }
    }

    /** Chrome preset is an atomic transformation over the latest persisted profile. */
    suspend fun applyPreset(preset: ThemePreset) {
        if (preset == ThemePreset.CUSTOM) return
        dataStore.edit { prefs ->
            val current = decode(prefs)
            val presetProfile = preset.profile()
            val next = presetProfile.copy(
                sceneId = current.sceneId,
                backgroundArgb = current.backgroundArgb,
                backgroundMidArgb = current.backgroundMidArgb,
                backgroundBottomArgb = current.backgroundBottomArgb,
                gradientAArgb = current.gradientAArgb,
                gradientBArgb = current.gradientBArgb,
                gradientCArgb = current.gradientCArgb,
                contourArgb = current.contourArgb,
                backgroundMotionLevel = current.backgroundMotionLevel,
                ambientParticleLevel = current.ambientParticleLevel,
                auroraIntensityLevel = current.auroraIntensityLevel,
                contourLevel = current.contourLevel,
                grainLevel = current.grainLevel,
                vignetteLevel = current.vignetteLevel,
                particleSpeedLevel = current.particleSpeedLevel,
                sceneSpeedLevel = current.sceneSpeedLevel,
                parallaxLevel = current.parallaxLevel,
                eventLevel = current.eventLevel,
                interactionEffectLevel = current.interactionEffectLevel,
                hapticLevel = current.hapticLevel,
                performanceMode = current.performanceMode,
            )
            prefs.clear()
            writeProfileInto(prefs, next)
        }
    }

    /** Scene transformation is also atomic and never overwrites the Chrome preset identity. */
    suspend fun applyScene(scene: ScenePreset) {
        dataStore.edit { prefs ->
            val current = decode(prefs)
            val next = scene.applyScenePalette(current).copy(presetId = current.presetId)
            prefs.clear()
            writeProfileInto(prefs, next)
        }
    }

    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs.clear()
            writeProfileInto(prefs, ThemeProfile.default())
        }
    }

    /**
     * A committed color is one DataStore transaction. Editing a Scene color keeps the selected
     * Chrome preset; editing Chrome marks only Chrome as custom.
     */
    suspend fun updateColor(role: ThemeColorRole, argb: Int) {
        dataStore.edit { prefs ->
            val current = decode(prefs)
            val sanitized = sanitizeArgb(role, argb)
            var next = current.withColor(role, sanitized)
            if (!isSceneColorRole(role)) next = next.copy(presetId = ThemePreset.CUSTOM.id)
            prefs.clear()
            writeProfileInto(prefs, next)
        }
    }

    suspend fun updateBackgroundMotion(level: Int) = updateLevel(KEY_BACKGROUND_MOTION, level.coerceIn(0, 3))
    suspend fun updateAmbientParticles(level: Int) = updateLevel(KEY_AMBIENT_PARTICLES, level.coerceIn(0, 3))
    suspend fun updateInteractionEffects(level: Int) = updateLevel(KEY_INTERACTION_EFFECTS, level.coerceIn(0, 3))
    suspend fun updateHaptics(level: Int) = updateLevel(KEY_HAPTICS, level.coerceIn(0, 2))
    suspend fun updatePerformanceMode(mode: Int) = updateLevel(KEY_PERFORMANCE, mode.coerceIn(0, 2))
    suspend fun updateAuroraIntensity(level: Int) = updateLevel(KEY_AURORA_INTENSITY, level.coerceIn(0, 3))
    suspend fun updateContour(level: Int) = updateLevel(KEY_CONTOUR, level.coerceIn(0, 3))
    suspend fun updateGrain(level: Int) = updateLevel(KEY_GRAIN, level.coerceIn(0, 3))
    suspend fun updateVignette(level: Int) = updateLevel(KEY_VIGNETTE, level.coerceIn(0, 3))
    suspend fun updateParticleSpeed(level: Int) = updateLevel(KEY_PARTICLE_SPEED, level.coerceIn(0, 3))
    suspend fun updateSceneSpeed(level: Int) = updateLevel(KEY_SCENE_SPEED, level.coerceIn(0, 3))
    suspend fun updateParallax(level: Int) = updateLevel(KEY_PARALLAX, level.coerceIn(0, 3))
    suspend fun updateEvents(level: Int) = updateLevel(KEY_EVENTS, level.coerceIn(0, 3))

    private suspend fun updateLevel(key: Preferences.Key<Int>, value: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SCHEMA] = ThemeProfile.CURRENT_SCHEMA_VERSION
            prefs[key] = value
        }
    }

    private fun writeProfileInto(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        profile: ThemeProfile,
    ) {
        prefs[KEY_SCHEMA] = ThemeProfile.CURRENT_SCHEMA_VERSION
        prefs[KEY_PRESET] = profile.presetId
        prefs[KEY_SCENE] = profile.sceneId
        ThemeColorRole.entries.forEach { role -> prefs[colorKey(role)] = sanitizeArgb(role, profile.color(role)) }
        prefs[KEY_BACKGROUND_MOTION] = profile.backgroundMotionLevel
        prefs[KEY_AMBIENT_PARTICLES] = profile.ambientParticleLevel
        prefs[KEY_INTERACTION_EFFECTS] = profile.interactionEffectLevel
        prefs[KEY_HAPTICS] = profile.hapticLevel
        prefs[KEY_PERFORMANCE] = profile.performanceMode
        prefs[KEY_AURORA_INTENSITY] = profile.auroraIntensityLevel
        prefs[KEY_CONTOUR] = profile.contourLevel
        prefs[KEY_GRAIN] = profile.grainLevel
        prefs[KEY_VIGNETTE] = profile.vignetteLevel
        prefs[KEY_PARTICLE_SPEED] = profile.particleSpeedLevel
        prefs[KEY_SCENE_SPEED] = profile.sceneSpeedLevel
        prefs[KEY_PARALLAX] = profile.parallaxLevel
        prefs[KEY_EVENTS] = profile.eventLevel
    }

    private fun decode(prefs: Preferences): ThemeProfile {
        val storedSchema = prefs[KEY_SCHEMA] ?: 1
        val storedPreset = ThemePreset.fromId(prefs[KEY_PRESET])
        var profile = if (storedPreset == ThemePreset.CUSTOM) {
            ThemePreset.AQUA_NOIR.profile().copy(presetId = ThemePreset.CUSTOM.id)
        } else {
            storedPreset.profile().copy(presetId = storedPreset.id)
        }

        if (storedSchema < ThemeProfile.CURRENT_SCHEMA_VERSION) {
            ThemeColorRole.entries.filterNot(::isSceneColorRole).forEach { role ->
                prefs[colorKey(role)]?.let { profile = profile.withColor(role, sanitizeArgb(role, it)) }
            }
            profile = ScenePreset.EVENT_HORIZON.applyScenePalette(profile)
        } else {
            ThemeColorRole.entries.forEach { role ->
                prefs[colorKey(role)]?.let { profile = profile.withColor(role, sanitizeArgb(role, it)) }
            }
        }

        return profile.copy(
            schemaVersion = ThemeProfile.CURRENT_SCHEMA_VERSION,
            sceneId = if (storedSchema < ThemeProfile.CURRENT_SCHEMA_VERSION) profile.sceneId else prefs[KEY_SCENE] ?: profile.sceneId,
            backgroundMotionLevel = prefs[KEY_BACKGROUND_MOTION] ?: profile.backgroundMotionLevel,
            ambientParticleLevel = prefs[KEY_AMBIENT_PARTICLES] ?: profile.ambientParticleLevel,
            interactionEffectLevel = prefs[KEY_INTERACTION_EFFECTS] ?: profile.interactionEffectLevel,
            hapticLevel = prefs[KEY_HAPTICS] ?: profile.hapticLevel,
            performanceMode = prefs[KEY_PERFORMANCE] ?: profile.performanceMode,
            auroraIntensityLevel = prefs[KEY_AURORA_INTENSITY] ?: profile.auroraIntensityLevel,
            contourLevel = prefs[KEY_CONTOUR] ?: profile.contourLevel,
            grainLevel = prefs[KEY_GRAIN] ?: profile.grainLevel,
            vignetteLevel = prefs[KEY_VIGNETTE] ?: profile.vignetteLevel,
            particleSpeedLevel = prefs[KEY_PARTICLE_SPEED] ?: profile.particleSpeedLevel,
            sceneSpeedLevel = prefs[KEY_SCENE_SPEED] ?: profile.sceneSpeedLevel,
            parallaxLevel = prefs[KEY_PARALLAX] ?: profile.parallaxLevel,
            eventLevel = prefs[KEY_EVENTS] ?: profile.eventLevel,
        )
    }

    companion object {
        fun isSceneColorRole(role: ThemeColorRole): Boolean = when (role) {
            ThemeColorRole.BACKGROUND,
            ThemeColorRole.BACKGROUND_MID,
            ThemeColorRole.BACKGROUND_BOTTOM,
            ThemeColorRole.GRADIENT_A,
            ThemeColorRole.GRADIENT_B,
            ThemeColorRole.GRADIENT_C,
            ThemeColorRole.CONTOUR -> true
            else -> false
        }

        /** Alpha is meaningful only for material/effect roles. Core scene/text/semantic colors stay opaque. */
        fun allowsAlpha(role: ThemeColorRole): Boolean = when (role) {
            ThemeColorRole.SURFACE,
            ThemeColorRole.SURFACE_HIGH,
            ThemeColorRole.NAVIGATION,
            ThemeColorRole.SECONDARY_BUTTON,
            ThemeColorRole.STROKE,
            ThemeColorRole.GLOW,
            ThemeColorRole.RIPPLE,
            ThemeColorRole.PARTICLE_PRIMARY,
            ThemeColorRole.PARTICLE_SECONDARY -> true
            else -> false
        }

        fun sanitizeArgb(role: ThemeColorRole, argb: Int): Int = if (allowsAlpha(role)) argb else argb or 0xFF000000.toInt()

        private val KEY_SCHEMA = intPreferencesKey("schema_version")
        private val KEY_PRESET = stringPreferencesKey("preset_id")
        private val KEY_SCENE = stringPreferencesKey("scene_id")
        private val KEY_BACKGROUND_MOTION = intPreferencesKey("background_motion")
        private val KEY_AMBIENT_PARTICLES = intPreferencesKey("ambient_particles")
        private val KEY_INTERACTION_EFFECTS = intPreferencesKey("interaction_effects")
        private val KEY_HAPTICS = intPreferencesKey("haptics")
        private val KEY_PERFORMANCE = intPreferencesKey("performance_mode")
        private val KEY_AURORA_INTENSITY = intPreferencesKey("aurora_intensity")
        private val KEY_CONTOUR = intPreferencesKey("contour_level")
        private val KEY_GRAIN = intPreferencesKey("grain_level")
        private val KEY_VIGNETTE = intPreferencesKey("vignette_level")
        private val KEY_PARTICLE_SPEED = intPreferencesKey("particle_speed")
        private val KEY_SCENE_SPEED = intPreferencesKey("scene_speed")
        private val KEY_PARALLAX = intPreferencesKey("parallax_level")
        private val KEY_EVENTS = intPreferencesKey("event_level")

        private fun colorKey(role: ThemeColorRole) = intPreferencesKey("color_${role.key}")
    }
}
