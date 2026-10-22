package com.wallpaperhub.ui.designsystem

import com.wallpaperhub.playback.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatusLabelTest {
    @Test
    fun idleDoesNotClaimThatAConfirmationIsPending() {
        assertEquals("尚未设为壁纸", playbackStatusLabel(PlaybackPhase.IDLE))
    }

    @Test
    fun inProgressPhasesUseAnActiveProgressLabel() {
        assertEquals("正在准备壁纸", playbackStatusLabel(PlaybackPhase.PREPARING))
        assertEquals("正在准备壁纸", playbackStatusLabel(PlaybackPhase.TRANSITIONING))
    }

    @Test
    fun failureKeepsTheSpecificRendererMessage() {
        assertEquals("首帧超时", playbackStatusLabel(PlaybackPhase.FAILED, "首帧超时"))
    }
}
