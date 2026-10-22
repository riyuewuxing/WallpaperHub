package com.wallpaperhub.playback.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperVisibilityPolicyTest {
    @Test
    fun visibleFlagDoesNotMeanActuallyVisibleWhenScreenIsOff() {
        assertFalse(WallpaperVisibilityPolicy.isActuallyVisible(visible = true, screenOn = false))
        assertTrue(WallpaperVisibilityPolicy.isActuallyVisible(visible = true, screenOn = true))
    }

    @Test
    fun screenOffAllowsOneHiddenCatchUpEvenWhenOemKeepsEngineVisible() {
        assertTrue(
            WallpaperVisibilityPolicy.canRunHiddenCatchUp(
                visible = true,
                screenOn = false,
                powerSave = false,
                isPreview = false,
                engineDestroyed = false,
            )
        )
    }

    @Test
    fun actuallyVisibleWallpaperNeverRunsHiddenCatchUp() {
        assertFalse(
            WallpaperVisibilityPolicy.canRunHiddenCatchUp(
                visible = true,
                screenOn = true,
                powerSave = false,
                isPreview = false,
                engineDestroyed = false,
            )
        )
    }

    @Test
    fun screenOnRuntimeOverlayDefersDecoderPause() {
        assertTrue(
            WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                visible = false,
                screenOn = true,
                powerSave = false,
                isPreview = false,
            )
        )
    }

    @Test
    fun previewScreenOffAndPowerSaveNeverDeferDecoderPause() {
        assertFalse(
            WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                visible = false,
                screenOn = true,
                powerSave = false,
                isPreview = true,
            )
        )
        assertFalse(
            WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                visible = false,
                screenOn = false,
                powerSave = false,
                isPreview = false,
            )
        )
        assertFalse(
            WallpaperVisibilityPolicy.shouldDeferVideoDecoderPause(
                visible = false,
                screenOn = true,
                powerSave = true,
                isPreview = false,
            )
        )
    }

    @Test
    fun continuousRenderingRequiresActualVisibilityAndNormalPowerMode() {
        assertTrue(
            WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                visible = true,
                screenOn = true,
                powerSave = false,
            )
        )
        assertFalse(
            WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                visible = true,
                screenOn = false,
                powerSave = false,
            )
        )
        assertFalse(
            WallpaperVisibilityPolicy.shouldRunContinuousRendering(
                visible = true,
                screenOn = true,
                powerSave = true,
            )
        )
    }
}
