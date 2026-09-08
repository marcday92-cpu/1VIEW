package com.iptv.tv.data.repository

import android.content.Context
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.ResumeItem
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last Home rails on disk so cold start can paint favourites / recent / continue
 * before Room or the live catalogue catch up.
 */
@Singleton
class HomeSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    @Volatile
    private var memory: HomeSnapshot? = null

    fun load(): HomeSnapshot? = memory ?: readDisk()?.also { memory = it }

    fun saveAsync(snapshot: HomeSnapshot) {
        if (snapshot.profileId <= 0L) return
        memory = snapshot
        scope.launch {
            runCatching {
                val tmp = File(file.parentFile, "$FILE_NAME.${System.nanoTime()}.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    private fun readDisk(): HomeSnapshot? = runCatching {
        if (!file.exists() || file.length() < 8L) return@runCatching null
        json.decodeFromString(HomeSnapshot.serializer(), file.readText())
    }.getOrNull()

    private companion object {
        const val FILE_NAME = "home-snapshot.json"
    }
}

@Serializable
data class HomeSnapshot(
    val profileId: Long = 0,
    val profileName: String = "",
    val recentlyWatched: List<HomeChannelSnap> = emptyList(),
    val mostWatched: List<HomeChannelSnap> = emptyList(),
    val favourites: List<HomeChannelSnap> = emptyList(),
    val continueWatching: List<HomeResumeSnap> = emptyList(),
    val recentMovies: List<HomeVodSnap> = emptyList(),
    val recentSeries: List<HomeSeriesSnap> = emptyList(),
) {
    fun channelIndex(): Map<Int, Channel> = buildMap {
        (recentlyWatched + mostWatched + favourites).forEach { snap ->
            put(snap.streamId, snap.toChannel())
        }
    }
}

@Serializable
data class HomeChannelSnap(
    val streamId: Int,
    val name: String,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val categoryId: String = "",
    val source: String = ChannelSource.XC,
) {
    fun toChannel() = Channel(
        streamId = streamId,
        name = name,
        logoUrl = logoUrl,
        categoryId = categoryId,
        epgChannelId = epgChannelId,
        source = source,
    )

    companion object {
        fun from(channel: Channel) = HomeChannelSnap(
            streamId = channel.streamId,
            name = channel.name,
            logoUrl = channel.logoUrl,
            epgChannelId = channel.epgChannelId,
            categoryId = channel.categoryId,
            source = channel.source,
        )
    }
}

@Serializable
data class HomeResumeSnap(
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val seasonNum: Int? = null,
    val episodeNum: Int? = null,
    val streamId: Int? = null,
    val year: String? = null,
    val seriesId: Int? = null,
    val filename: String? = null,
    val containerExtension: String? = null,
) {
    fun toResume() = ResumeItem(
        contentId = contentId,
        contentType = runCatching { ContentType.valueOf(contentType) }.getOrDefault(ContentType.MOVIE),
        title = title,
        posterUrl = posterUrl,
        positionMs = positionMs,
        durationMs = durationMs,
        seasonNum = seasonNum,
        episodeNum = episodeNum,
        streamId = streamId,
        year = year,
        seriesId = seriesId,
        filename = filename,
        containerExtension = containerExtension,
    )

    companion object {
        fun from(item: ResumeItem) = HomeResumeSnap(
            contentId = item.contentId,
            contentType = item.contentType.name,
            title = item.title,
            posterUrl = item.posterUrl,
            positionMs = item.positionMs,
            durationMs = item.durationMs,
            seasonNum = item.seasonNum,
            episodeNum = item.episodeNum,
            streamId = item.streamId,
            year = item.year,
            seriesId = item.seriesId,
            filename = item.filename,
            containerExtension = item.containerExtension,
        )
    }
}

@Serializable
data class HomeVodSnap(
    val streamId: Int,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String = "",
    val rating: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val containerExtension: String = "mp4",
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
    val streamUrl: String? = null,
    val fallbackUrl: String? = null,
) {
    fun toVod() = VodItem(
        streamId = streamId,
        name = name,
        posterUrl = posterUrl,
        categoryId = categoryId,
        rating = rating,
        year = year,
        duration = duration,
        containerExtension = containerExtension,
        source = source,
        externalId = externalId,
        streamUrl = streamUrl,
        fallbackUrl = fallbackUrl,
    )

    companion object {
        fun from(item: VodItem) = HomeVodSnap(
            streamId = item.streamId,
            name = item.name,
            posterUrl = item.posterUrl,
            categoryId = item.categoryId,
            rating = item.rating,
            year = item.year,
            duration = item.duration,
            containerExtension = item.containerExtension,
            source = item.source,
            externalId = item.externalId,
            streamUrl = item.streamUrl,
            fallbackUrl = item.fallbackUrl,
        )
    }
}

@Serializable
data class HomeSeriesSnap(
    val seriesId: Int,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String = "",
    val rating: String? = null,
    val plot: String? = null,
    val source: String = ChannelSource.XC,
    val externalId: String? = null,
) {
    fun toSeries() = SeriesItem(
        seriesId = seriesId,
        name = name,
        posterUrl = posterUrl,
        categoryId = categoryId,
        rating = rating,
        plot = plot,
        source = source,
        externalId = externalId,
    )

    companion object {
        fun from(item: SeriesItem) = HomeSeriesSnap(
            seriesId = item.seriesId,
            name = item.name,
            posterUrl = item.posterUrl,
            categoryId = item.categoryId,
            rating = item.rating,
            plot = item.plot,
            source = item.source,
            externalId = item.externalId,
        )
    }
}
