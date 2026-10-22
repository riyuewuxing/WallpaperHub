package com.wallpaperhub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: PlaybackCommandEntity)

    @Query("SELECT * FROM playback_commands ORDER BY requestedAt DESC LIMIT 1")
    suspend fun latestCommand(): PlaybackCommandEntity?

    @Query("SELECT * FROM playback_commands ORDER BY requestedAt DESC LIMIT 1")
    fun observeLatestCommand(): Flow<PlaybackCommandEntity?>

    @Query("DELETE FROM playback_commands WHERE id != :keepId")
    suspend fun deleteOtherCommands(keepId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: PlaybackSessionEntity)

    @Query(
        """SELECT desiredUri, appliedUri, commandId, phase, queueRevision, currentIndex,
            nextPreviewUri, errorCode, errorMessage, updatedAt
            FROM playback_sessions WHERE id = 'active' LIMIT 1"""
    )
    fun observeSession(): Flow<PlaybackSessionRecord?>

    @Query(
        """SELECT desiredUri, appliedUri, commandId, phase, queueRevision, currentIndex,
            nextPreviewUri, errorCode, errorMessage, updatedAt
            FROM playback_sessions WHERE id = 'active' LIMIT 1"""
    )
    suspend fun getSession(): PlaybackSessionRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistState(state: PlaylistStateEntity)

    @Query(
        """SELECT revision, queueFingerprint, currentUri, currentIndex, updatedAt
            FROM playlist_state WHERE id = 'main' LIMIT 1"""
    )
    suspend fun getPlaylistState(): PlaylistStateRecord?

    @Query(
        """SELECT revision, queueFingerprint, currentUri, currentIndex, updatedAt
            FROM playlist_state WHERE id = 'main' LIMIT 1"""
    )
    fun observePlaylistState(): Flow<PlaylistStateRecord?>
}
