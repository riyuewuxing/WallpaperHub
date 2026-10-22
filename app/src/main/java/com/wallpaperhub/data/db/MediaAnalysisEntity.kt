package com.wallpaperhub.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_analysis",
    foreignKeys = [
        ForeignKey(
            entity = WallpaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["wallpaperId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sha256"]),
        Index(value = ["qualityScore"]),
        Index(value = ["status"]),
        Index(value = ["analysisVersion"]),
    ],
)
data class MediaAnalysisEntity(
    @PrimaryKey val wallpaperId: String,
    val uri: String,
    val sourceSizeBytes: Long,
    val sourceLastModified: Long,
    val sha256: String? = null,
    val visualHashSequence: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val bitrateBitsPerSecond: Long? = null,
    val frameRate: Float? = null,
    val detailScore: Double? = null,
    val upscaleFactor: Double? = null,
    val qualityScore: Int? = null,
    val qualityFlags: String = "",
    val status: String = MediaAnalysisStatus.PENDING,
    val analysisVersion: Int = CURRENT_MEDIA_ANALYSIS_VERSION,
    val analyzedAt: Long = 0L,
)

object MediaAnalysisStatus {
    const val PENDING = "PENDING"
    const val HASHED = "HASHED"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

/** V2: original image dimensions + structured-edge sharpness + source-aware cache identity. */
const val CURRENT_MEDIA_ANALYSIS_VERSION = 2
