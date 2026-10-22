package com.wallpaperhub.playback.model

import java.util.UUID

/** The navigation policy attached to one already-materialized playback plan. */
enum class PlaybackMode {
    SEQUENTIAL,
    LOOP,
    SHUFFLE,
    RANDOM,
}

enum class PlaybackCommandType {
    APPLY,
    NEXT,
    PREVIOUS,
    SET_QUEUE,
    UPDATE_SETTINGS,
    RETRY,
}

enum class PlaybackPhase {
    IDLE,
    REQUESTED,
    RESOLVING,
    PREPARING,
    READY,
    TRANSITIONING,
    APPLIED,
    FAILED,
}

data class PlaybackCommand(
    val id: String = UUID.randomUUID().toString(),
    val type: PlaybackCommandType,
    val targetUri: String? = null,
    val playlistRevision: Long = 0L,
    val requestedAt: Long = System.currentTimeMillis(),
    val source: String = "ui",
)

data class PlaybackSession(
    val desiredUri: String? = null,
    val appliedUri: String? = null,
    val commandId: String? = null,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val queueRevision: Long = 0L,
    val queue: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleRound: List<String> = emptyList(),
    val nextPreviewUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
