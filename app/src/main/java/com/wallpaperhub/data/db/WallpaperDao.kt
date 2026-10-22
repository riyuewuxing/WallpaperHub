package com.wallpaperhub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wallpaperhub.data.model.WallpaperType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** Wallpaper table DAO. */
@Dao
interface WallpaperDao {

    /**
     * Observe a tiny scalar instead of materializing the entire library through one CursorWindow.
     * Room invalidates this query on every wallpapers-table mutation, even when the count itself is
     * unchanged (favorite/crop/playlist updates). Each signal is then expanded with bounded pages.
     */
    @Query("SELECT COUNT(*) FROM wallpapers")
    fun observeAllInvalidationSignal(): Flow<Int>

    fun observeAll(): Flow<List<WallpaperEntity>> =
        observeAllInvalidationSignal()
            .map { allSnapshot() }
            .retryWhen { cause, attempt ->
                if (cause is IllegalStateException) {
                    // A transient CursorWindow/SQLite read must never take down the app process and,
                    // with it, the live-wallpaper service. Restart from a fresh invalidation query.
                    delay(minOf(250L, 25L * (attempt + 1L)))
                    true
                } else {
                    false
                }
            }

    @Query("SELECT * FROM wallpapers ORDER BY addedAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun allPage(limit: Int, offset: Int): List<WallpaperEntity>

    suspend fun allSnapshot(): List<WallpaperEntity> =
        WallpaperPageReader.readAll { limit, offset -> allPage(limit, offset) }

    @Query("SELECT * FROM wallpapers WHERE type = :type ORDER BY addedAt DESC, id DESC")
    fun observeByType(type: WallpaperType): Flow<List<WallpaperEntity>>

    @Query("SELECT * FROM wallpapers WHERE isFavorite = 1 ORDER BY addedAt DESC, id DESC")
    fun observeFavorites(): Flow<List<WallpaperEntity>>

    /** Concrete full plan consumed by UI + WallpaperService. */
    @Query("SELECT * FROM wallpapers WHERE inPlaylist = 1 ORDER BY playlistOrder ASC, id ASC")
    fun observePlaylist(): Flow<List<WallpaperEntity>>

    @Query("SELECT uri FROM wallpapers WHERE inPlaylist = 1 ORDER BY playlistOrder ASC, id ASC")
    fun observePlaylistUris(): Flow<List<String>>

    @Query("SELECT * FROM wallpapers WHERE inPlaylist = 1 ORDER BY playlistOrder ASC, id ASC")
    suspend fun playlistSnapshot(): List<WallpaperEntity>

    @Query("SELECT uri FROM wallpapers WHERE inPlaylist = 1 ORDER BY playlistOrder ASC, id ASC")
    suspend fun playlistUriSnapshot(): List<String>

    /**
     * Canonical membership order is always import time newest -> oldest. playlistOrder is a runtime
     * plan and playlistBaseOrder is retained only for database/backward compatibility.
     */
    @Query("SELECT * FROM wallpapers WHERE inPlaylist = 1 ORDER BY addedAt DESC, id DESC")
    suspend fun canonicalPlaylistSnapshot(): List<WallpaperEntity>

    @Query("SELECT * FROM wallpapers WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WallpaperEntity?

    @Query("SELECT * FROM wallpapers WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): WallpaperEntity?

    @Query("SELECT * FROM wallpapers WHERE uri IN (:uris)")
    suspend fun findByUris(uris: List<String>): List<WallpaperEntity>

    @Query("SELECT uri FROM wallpapers")
    suspend fun allUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WallpaperEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<WallpaperEntity>): List<Long>

    @Update
    suspend fun update(entity: WallpaperEntity)

    @Update
    suspend fun updateAll(entities: List<WallpaperEntity>)

    @Query("DELETE FROM wallpapers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM wallpapers WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM wallpapers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderPermission(entity: FolderPermissionEntity)

    @Query("DELETE FROM folder_permissions WHERE uri = :uri")
    suspend fun deleteFolderPermission(uri: String)

    @Query("SELECT * FROM folder_permissions ORDER BY lastAccessedAt ASC")
    suspend fun getOldestFolderPermissions(): List<FolderPermissionEntity>

    @Query("SELECT * FROM folder_permissions")
    suspend fun allFolderPermissions(): List<FolderPermissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeatherInfo(entity: WeatherInfoEntity)

    @Query("SELECT * FROM weather_info WHERE id = :id LIMIT 1")
    suspend fun getWeatherInfo(id: String = "current"): WeatherInfoEntity?
}
