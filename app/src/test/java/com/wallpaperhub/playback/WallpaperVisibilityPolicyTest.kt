package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.WallpaperVisibilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperVisibilityPolicyTest {
    @Test
    fun transientAppVisibilityDoesNotPauseTheDecoder() {
        assertFalse(WallpaperVisibilityPolicy.shouldPauseVideoDecoder(screenOn = true, powerSave = false))
    }

    @Test
    fun screenOffAndPowerSaveStillPauseTheDecoder() {
        assertTrue(WallpaperVisibilityPolicy.shouldPauseVideoDecoder(screenOn = false, powerSave = false))
        assertTrue(WallpaperVisibilityPolicy.shouldPauseVideoDecoder(screenOn = true, powerSave = true))
    }
}
