package com.wallpaperhub.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneThemeTest {
    @Test
    fun sceneIdsAreUnique() {
        assertEquals(ScenePreset.entries.size, ScenePreset.entries.map { it.id }.toSet().size)
    }

    @Test
    fun applyingSceneChangesWorldButPreservesChromeAndSafetyRoles() {
        val base = ThemePreset.ROSE_NOIR.profile().copy(
            accentArgb = 0xFF123456.toInt(),
            primaryButtonArgb = 0xFF654321.toInt(),
            successArgb = 0xFF11AA55.toInt(),
            errorArgb = 0xFFEE3355.toInt(),
        )
        val next = ScenePreset.EVENT_HORIZON.applyScenePalette(base)

        assertEquals(base.accentArgb, next.accentArgb)
        assertEquals(base.primaryButtonArgb, next.primaryButtonArgb)
        assertEquals(base.successArgb, next.successArgb)
        assertEquals(base.errorArgb, next.errorArgb)
        assertNotEquals(base.backgroundMidArgb, next.backgroundMidArgb)
        assertEquals(ScenePreset.EVENT_HORIZON.id, next.sceneId)
    }

    @Test
    fun defaultSceneHasVisibleMotionAndParticles() {
        val profile = ThemeProfile.default()
        assertTrue(profile.backgroundMotionLevel > 0)
        assertTrue(profile.sceneSpeedLevel > 0)
        assertTrue(profile.ambientParticleLevel > 0)
        assertTrue(profile.particleSpeedLevel > 0)
        assertTrue(profile.eventLevel > 0)
    }

    @Test
    fun coreSceneTextAndSemanticRolesCannotBecomeTransparent() {
        val transparent = 0x00123456
        listOf(
            ThemeColorRole.BACKGROUND,
            ThemeColorRole.BACKGROUND_MID,
            ThemeColorRole.BACKGROUND_BOTTOM,
            ThemeColorRole.TEXT_PRIMARY,
            ThemeColorRole.TEXT_SECONDARY,
            ThemeColorRole.SUCCESS,
            ThemeColorRole.WARNING,
            ThemeColorRole.ERROR,
        ).forEach { role ->
            assertFalse(ThemeRepository.allowsAlpha(role))
            assertEquals(0xFF000000.toInt(), ThemeRepository.sanitizeArgb(role, transparent) and 0xFF000000.toInt())
        }
    }

    @Test
    fun materialAndEffectRolesMayKeepAlpha() {
        val translucent = 0x44123456
        listOf(
            ThemeColorRole.SURFACE,
            ThemeColorRole.SURFACE_HIGH,
            ThemeColorRole.NAVIGATION,
            ThemeColorRole.STROKE,
            ThemeColorRole.GLOW,
            ThemeColorRole.RIPPLE,
        ).forEach { role ->
            assertTrue(ThemeRepository.allowsAlpha(role))
            assertEquals(translucent, ThemeRepository.sanitizeArgb(role, translucent))
        }
    }

    @Test
    fun highRefreshDisplayNeverForcesSceneAboveSixtyFps() {
        WallpaperScene.entries.forEach { scene ->
            assertTrue("${scene.name} exceeded Scene budget", scene.targetFps <= 60)
            assertTrue(scene.targetFps >= 30)
            assertTrue(scene.detailScale in 0.25f..1f)
        }
    }
}
