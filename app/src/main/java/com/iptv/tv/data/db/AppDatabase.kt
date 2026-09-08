package com.iptv.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        ChannelEntity::class,
        CategoryEntity::class,
        ProviderCategoryOverrideEntity::class,
        CustomGroupEntity::class,
        CustomGroupMemberEntity::class,
        FavouriteEntity::class,
        HiddenChannelEntity::class,
        HiddenItemEntity::class,
        ChannelEpgOverrideEntity::class,
        ViewHistoryEntity::class,
        ResumePositionEntity::class,
        EpgProgrammeEntity::class,
        ReminderEntity::class,
        RecordingEntity::class,
        ScheduledRecordingEntity::class,
        SubtitleSyncOffsetEntity::class,
        SubtitleSidecarEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryOverrideDao(): CategoryOverrideDao
    abstract fun customGroupDao(): CustomGroupDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun hiddenChannelDao(): HiddenChannelDao
    abstract fun hiddenItemDao(): HiddenItemDao
    abstract fun channelEpgOverrideDao(): ChannelEpgOverrideDao
    abstract fun viewHistoryDao(): ViewHistoryDao
    abstract fun resumeDao(): ResumeDao
    abstract fun epgDao(): EpgDao
    abstract fun reminderDao(): ReminderDao
    abstract fun recordingDao(): RecordingDao
    abstract fun scheduledRecordingDao(): ScheduledRecordingDao
    abstract fun subtitleSyncDao(): SubtitleSyncDao
    abstract fun subtitleSidecarDao(): SubtitleSidecarDao
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtitle_sidecars (
                        contentKey TEXT NOT NULL PRIMARY KEY,
                        filePath TEXT,
                        subtitleId TEXT NOT NULL,
                        language TEXT NOT NULL DEFAULT 'en',
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE view_history ADD COLUMN watchCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE categories_cache ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE view_history ADD COLUMN watchMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN containerExtension TEXT NOT NULL DEFAULT 'mp4'")
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE series_cache ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE view_history ADD COLUMN year TEXT")
                db.execSQL("ALTER TABLE view_history ADD COLUMN seriesId INTEGER")
                db.execSQL("ALTER TABLE view_history ADD COLUMN filename TEXT")
                db.execSQL("ALTER TABLE view_history ADD COLUMN containerExtension TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN source TEXT NOT NULL DEFAULT 'xc'")
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN streamUrl TEXT")
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN fallbackUrl TEXT")
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN referrer TEXT")
                db.execSQL("ALTER TABLE channels_cache ADD COLUMN userAgent TEXT")
                db.execSQL("ALTER TABLE categories_cache ADD COLUMN source TEXT NOT NULL DEFAULT 'xc'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_cache_source ON channels_cache(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_cache_externalId ON channels_cache(externalId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_groups ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_category_overrides ADD COLUMN mergedIntoId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hidden_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId INTEGER NOT NULL,
                        itemType TEXT NOT NULL,
                        itemId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_hidden_items_profileId_itemType_itemId ON hidden_items(profileId, itemType, itemId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_epg_overrides (
                        streamId INTEGER NOT NULL PRIMARY KEY,
                        epgChannelId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN source TEXT NOT NULL DEFAULT 'xc'")
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN streamUrl TEXT")
                db.execSQL("ALTER TABLE vod_cache ADD COLUMN fallbackUrl TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vod_cache_source ON vod_cache(source)")
                db.execSQL("ALTER TABLE series_cache ADD COLUMN plot TEXT")
                db.execSQL("ALTER TABLE series_cache ADD COLUMN source TEXT NOT NULL DEFAULT 'xc'")
                db.execSQL("ALTER TABLE series_cache ADD COLUMN externalId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_series_cache_source ON series_cache(source)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS episode_cache (
                        streamId INTEGER NOT NULL PRIMARY KEY,
                        seriesId INTEGER NOT NULL,
                        episodeNum INTEGER NOT NULL,
                        seasonNum INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        containerExtension TEXT NOT NULL DEFAULT 'mp4',
                        plot TEXT,
                        durationSecs INTEGER,
                        source TEXT NOT NULL DEFAULT 'xc',
                        externalId TEXT,
                        streamUrl TEXT,
                        fallbackUrl TEXT,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episode_cache_seriesId ON episode_cache(seriesId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episode_cache_source ON episode_cache(source)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v8 shipped two identity hashes: an earlier APK added a unique
                // streamId index on episode_cache, then the entity dropped it
                // without bumping version. Drop the leftover so Room validation
                // matches EpisodeEntity (indices = seriesId, source only).
                db.execSQL("DROP INDEX IF EXISTS index_episode_cache_streamId")
            }
        }
    }
}
