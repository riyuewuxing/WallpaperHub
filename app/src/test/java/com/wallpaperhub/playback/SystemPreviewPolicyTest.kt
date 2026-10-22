package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.SystemPreviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPreviewPolicyTest {
    @Test
    fun previewEngineUsesFastPathAndDoesNotPublishRuntimeState() {
        assertTrue(SystemPreviewPolicy.useFastPath(isPreview = true))
        assertFalse(SystemPreviewPolicy.shouldObserveRuntime(isPreview = true))
        assertFalse(SystemPreviewPolicy.shouldPublishRuntimePosition(isPreview = true))
        assertFalse(SystemPreviewPolicy.shouldExtractWallpaperColors(isPreview = true))
    }

    @Test
    fun activeWallpaperEngineKeepsFullRuntimeBehavior() {
        assertFalse(SystemPreviewPolicy.useFastPath(isPreview = false))
        assertTrue(SystemPreviewPolicy.shouldObserveRuntime(isPreview = false))
        assertTrue(SystemPreviewPolicy.shouldPublishRuntimePosition(isPreview = false))
        assertTrue(SystemPreviewPolicy.shouldExtractWallpaperColors(isPreview = false))
    }
}
