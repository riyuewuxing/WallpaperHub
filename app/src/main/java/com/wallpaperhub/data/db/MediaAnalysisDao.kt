package com.wallpaperhub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaAnalysisEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MediaAnalysisEntity>)

    @Query("SELECT * FROM media_analysis WHERE wallpaperId = :wallpaperId LIMIT 1")
    suspend fun findByWallpaperId(wallpaperId: String): MediaAnalysisEntity?

    @Query("SELECT * FROM media_analysis")
    suspend fun allRows(): List<MediaAnalysisEntity>

    @Query(
        "SELECT * FROM media_analysis " +
            "WHERE sha256 = :sha256 AND sha256 IS NOT NULL AND sha256 != '' " +
            "ORDER BY qualityScore DESC, analyzedAt ASC"
    )
    suspend fun findBySha256(sha256: String): List<MediaAnalysisEntity>

    @Query(
        """
        SELECT * FROM media_analysis
        WHERE sha256 IS NOT NULL
          AND sha256 != ''
          AND sha256 IN (
              SELECT sha256 FROM media_analysis
              WHERE sha256 IS NOT NULL AND sha256 != ''
              GROUP BY sha256
              HAVING COUNT(*) > 1
          )
        ORDER BY sha256 ASC, analyzedAt ASC
        """
    )
    fun observeExactDuplicateRows(): Flow<List<MediaAnalysisEntity>>

    @Query(
        """
        SELECT * FROM media_analysis
        WHERE status = 'READY'
          AND qualityScore IS NOT NULL
          AND qualityScore <= :maxScore
        ORDER BY qualityScore ASC, analyzedAt ASC
        """
    )
    fun observeLowQuality(maxScore: Int): Flow<List<MediaAnalysisEntity>>

    @Query("DELETE FROM media_analysis WHERE wallpaperId IN (:wallpaperIds)")
    suspend fun deleteByWallpaperIds(wallpaperIds: List<String>): Int

    @Query("DELETE FROM media_analysis")
    suspend fun clearAll(): Int
}
