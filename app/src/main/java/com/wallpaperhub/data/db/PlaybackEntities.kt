package com.wallpaperhub.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "playback_commands", indices = [Index(value = ["requestedAt"])])
data class PlaybackCommandEntity(
    @androidx.room.PrimaryKey val id: String,
    val type: String,
    val targetUri: String?,
    val playlistRevision: Long,
    val requestedAt: Long,
    val source: String,
)

@Entity(tableName = "playback_sessions")
data class PlaybackSessionEntity(
    @androidx.room.PrimaryKey val id: String = ACTIVE_SESSION_ID,
    val desiredUri: String?,
    val appliedUri: String?,
    val commandId: String?,
    val phase: String,
    val queueRevision: Long,
    val queueJson: String = "[]",
    val currentIndex: Int,
    val shuffleRoundJson: String = "[]",
    val nextPreviewUri: String? = null,
    val errorCode: String?,
    val errorMessage: String?,
    val updatedAt: Long,
)

@Entity(tableName = "playlist_state")
data class PlaylistStateEntity(
    @androidx.room.PrimaryKey val id: String = MAIN_PLAYLIST_ID,
    val revision: Long = 0L,
    val queueJson: String = "[]",
    val queueFingerprint: String = "",
    val currentUri: String? = null,
    val currentIndex: Int = -1,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Lightweight read model that deliberately excludes the legacy JSON queue columns.
 * A queue can contain thousands of URIs, which must never be materialized as one CursorWindow row.
 */
data class PlaybackSessionRecord(
    val desiredUri: String?,
    val appliedUri: String?,
    val commandId: String?,
    val phase: String,
    val queueRevision: Long,
    val currentIndex: Int,
    val nextPreviewUri: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val updatedAt: Long,
)

/** Metadata only; the normalized queue remains authoritative in the wallpapers table. */
data class PlaylistStateRecord(
    val revision: Long,
    val queueFingerprint: String,
    val currentUri: String?,
    val currentIndex: Int,
    val updatedAt: Long,
)

const val ACTIVE_SESSION_ID = "active"
const val MAIN_PLAYLIST_ID = "main"
