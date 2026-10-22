package com.wallpaperhub.playback

import com.wallpaperhub.playback.engine.ServiceApplyRecoveryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceApplyRecoveryPolicyTest {
    @Test
    fun missingCommandIsRecreatedForThePendingSystemWallpaper() {
        assertTrue(
            ServiceApplyRecoveryPolicy.shouldSubmit(
                desiredUri = "file:///selected.mp4",
                appliedUri = "file:///old.mp4",
                latestCommandTargetUri = null,
                serviceUri = "file:///selected.mp4",
            ),
        )
        assertFalse(
            ServiceApplyRecoveryPolicy.shouldSubmit(
                desiredUri = "file:///selected.mp4",
                appliedUri = "file:///selected.mp4",
                latestCommandTargetUri = null,
                serviceUri = "file:///selected.mp4",
            ),
        )
        assertFalse(
            ServiceApplyRecoveryPolicy.shouldSubmit(
                desiredUri = "file:///selected.mp4",
                appliedUri = "file:///old.mp4",
                latestCommandTargetUri = "file:///selected.mp4",
                serviceUri = "file:///selected.mp4",
            ),
        )
    }
}
