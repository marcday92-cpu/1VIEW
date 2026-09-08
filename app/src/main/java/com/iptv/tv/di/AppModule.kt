package com.iptv.tv.di

import android.content.Context
import androidx.room.Room
import com.iptv.tv.data.api.XtreamApi
import com.iptv.tv.data.db.AppDatabase
import com.iptv.tv.data.db.CategoryDao
import com.iptv.tv.data.db.CategoryOverrideDao
import com.iptv.tv.data.db.ChannelDao
import com.iptv.tv.data.db.CustomGroupDao
import com.iptv.tv.data.db.EpgDao
import com.iptv.tv.data.db.FavouriteDao
import com.iptv.tv.data.db.ChannelEpgOverrideDao
import com.iptv.tv.data.db.HiddenChannelDao
import com.iptv.tv.data.db.HiddenItemDao
import com.iptv.tv.data.db.ProfileDao
import com.iptv.tv.data.db.RecordingDao
import com.iptv.tv.data.db.ReminderDao
import com.iptv.tv.data.db.ResumeDao
import com.iptv.tv.data.db.ScheduledRecordingDao
import com.iptv.tv.data.db.SeriesDao
import com.iptv.tv.data.db.SubtitleSidecarDao
import com.iptv.tv.data.db.SubtitleSyncDao
import com.iptv.tv.data.db.ViewHistoryDao
import com.iptv.tv.data.db.VodDao
import com.iptv.tv.data.db.EpisodeDao
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://placeholder.local/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamApi(retrofit: Retrofit): XtreamApi = retrofit.create(XtreamApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "iptv.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
            )
            .build()

    @Provides fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()
    @Provides fun provideChannelDao(db: AppDatabase): ChannelDao = db.channelDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCategoryOverrideDao(db: AppDatabase): CategoryOverrideDao = db.categoryOverrideDao()
    @Provides fun provideCustomGroupDao(db: AppDatabase): CustomGroupDao = db.customGroupDao()
    @Provides fun provideFavouriteDao(db: AppDatabase): FavouriteDao = db.favouriteDao()
    @Provides fun provideHiddenChannelDao(db: AppDatabase): HiddenChannelDao = db.hiddenChannelDao()
    @Provides fun provideHiddenItemDao(db: AppDatabase): HiddenItemDao = db.hiddenItemDao()
    @Provides fun provideChannelEpgOverrideDao(db: AppDatabase): ChannelEpgOverrideDao = db.channelEpgOverrideDao()
    @Provides fun provideViewHistoryDao(db: AppDatabase): ViewHistoryDao = db.viewHistoryDao()
    @Provides fun provideResumeDao(db: AppDatabase): ResumeDao = db.resumeDao()
    @Provides fun provideEpgDao(db: AppDatabase): EpgDao = db.epgDao()
    @Provides fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()
    @Provides fun provideRecordingDao(db: AppDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideScheduledRecordingDao(db: AppDatabase): ScheduledRecordingDao = db.scheduledRecordingDao()
    @Provides fun provideSubtitleSyncDao(db: AppDatabase): SubtitleSyncDao = db.subtitleSyncDao()
    @Provides fun provideSubtitleSidecarDao(db: AppDatabase): SubtitleSidecarDao = db.subtitleSidecarDao()
    @Provides fun provideVodDao(db: AppDatabase): VodDao = db.vodDao()
    @Provides fun provideSeriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: AppDatabase): EpisodeDao = db.episodeDao()
}
