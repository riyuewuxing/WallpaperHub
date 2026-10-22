package com.wallpaperhub.preview

import com.wallpaperhub.ui.designsystem.VideoPreviewEvent
import com.wallpaperhub.ui.designsystem.VideoPreviewState
import com.wallpaperhub.ui.designsystem.VideoPreviewStateReducer
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreviewStateTest {

    @Test
    fun previewShowsPosterWhilePreparingThenRevealsTheFirstFrame() {
        val loading = VideoPreviewStateReducer.reduce(
            VideoPreviewState.POSTER,
            VideoPreviewEvent.PREPARE,
        )
        val playing = VideoPreviewStateReducer.reduce(
            loading,
            VideoPreviewEvent.FIRST_FRAME,
        )

        assertEquals(VideoPreviewState.LOADING, loading)
        assertEquals(VideoPreviewState.PLAYING, playing)
    }

    @Test
    fun playbackFailureHasARecoverableErrorState() {
        val failed = VideoPreviewStateReducer.reduce(
            VideoPreviewState.LOADING,
            VideoPreviewEvent.FAILED,
        )
        val retrying = VideoPreviewStateReducer.reduce(
            failed,
            VideoPreviewEvent.RETRY,
        )

        assertEquals(VideoPreviewState.ERROR, failed)
        assertEquals(VideoPreviewState.LOADING, retrying)
    }
}
