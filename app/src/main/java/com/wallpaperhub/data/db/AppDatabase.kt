package com.wallpaperhub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WallpaperEntity::class,
        FolderPermissionEntity::class,
        WeatherInfoEntity::class,
        PlaybackCommandEntity::class,
        PlaybackSessionEntity::class,
        PlaylistStateEntity::class,
        MediaAnalysisEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wallpaperDao(): WallpaperDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun mediaAnalysisDao(): MediaAnalysisDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `wallpapers` ADD COLUMN `inPlaylist` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `wallpapers` ADD COLUMN `playlistOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wallpapers_uri` ON `wallpapers` (`uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_type` ON `wallpapers` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_isFavorite` ON `wallpapers` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_inPlaylist_playlistOrder` ON `wallpapers` (`inPlaylist`, `playlistOrder`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_addedAt` ON `wallpapers` (`addedAt`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN width INTEGER")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN height INTEGER")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN duration INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `folder_permissions` (`uri` TEXT NOT NULL, `lastAccessedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_type_addedAt` ON `wallpapers` (`type`, `addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wallpapers_isFavorite_addedAt` ON `wallpapers` (`isFavorite`, `addedAt`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `weather_info` (`id` TEXT NOT NULL, `condition` TEXT NOT NULL, `tempC` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN cropScale REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN cropOffsetX REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN cropOffsetY REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN cropRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_commands` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `targetUri` TEXT, `playlistRevision` INTEGER NOT NULL, `requestedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_commands_requestedAt` ON `playback_commands` (`requestedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_sessions` (`id` TEXT NOT NULL, `desiredUri` TEXT, `appliedUri` TEXT, `commandId` TEXT, `phase` TEXT NOT NULL, `queueRevision` INTEGER NOT NULL, `queueJson` TEXT NOT NULL, `currentIndex` INTEGER NOT NULL, `errorCode` TEXT, `errorMessage` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_state` (`id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `queueJson` TEXT NOT NULL, `currentUri` TEXT, `currentIndex` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_sessions ADD COLUMN shuffleRoundJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE playback_sessions ADD COLUMN nextPreviewUri TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN cropScaleMode TEXT NOT NULL DEFAULT 'fill'")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist_state ADD COLUMN queueFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE playback_sessions SET queueJson = '[]', shuffleRoundJson = '[]'")
                db.execSQL("UPDATE playlist_state SET queueJson = '[]'")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpapers ADD COLUMN playlistBaseOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallpapers_inPlaylist_playlistBaseOrder` " +
                        "ON `wallpapers` (`inPlaylist`, `playlistBaseOrder`)"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_analysis` (
                        `wallpaperId` TEXT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `sourceSizeBytes` INTEGER NOT NULL,
                        `sourceLastModified` INTEGER NOT NULL,
                        `sha256` TEXT,
                        `visualHashSequence` TEXT NOT NULL,
                        `width` INTEGER,
                        `height` INTEGER,
                        `durationMs` INTEGER,
                        `bitrateBitsPerSecond` INTEGER,
                        `frameRate` REAL,
                        `detailScore` REAL,
                        `upscaleFactor` REAL,
                        `qualityScore` INTEGER,
                        `qualityFlags` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `analysisVersion` INTEGER NOT NULL,
                        `analyzedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`wallpaperId`),
                        FOREIGN KEY(`wallpaperId`) REFERENCES `wallpapers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_analysis_sha256` ON `media_analysis` (`sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_analysis_qualityScore` ON `media_analysis` (`qualityScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_analysis_status` ON `media_analysis` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_analysis_analysisVersion` ON `media_analysis` (`analysisVersion`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wallpaperhub.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
