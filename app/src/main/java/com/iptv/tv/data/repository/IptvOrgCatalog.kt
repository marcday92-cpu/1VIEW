package com.iptv.tv.data.repository

import android.content.Context
import com.iptv.tv.data.api.IptvOrgBlocklistDto
import com.iptv.tv.data.api.IptvOrgChannelDto
import com.iptv.tv.data.api.IptvOrgCountryDto
import com.iptv.tv.data.api.IptvOrgLogoDto
import com.iptv.tv.data.api.IptvOrgStreamDto
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.EpisodeInfo
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class IptvOrgSnapshot(
    val categories: List<Category>,
    val channels: List<Channel>,
)

data class FreeFastSnapshot(
    val movieCategories: List<Category>,
    val movies: List<VodItem>,
    val seriesCategories: List<Category>,
    val series: List<SeriesItem>,
    val episodes: List<Pair<Int, Episode>>,
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
}

private class StreamPick(
    var url: String,
    var quality: Int,
    var fallbackUrl: String? = null,
    var referrer: String? = null,
    var userAgent: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Singleton
class IptvOrgCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val cacheDir: File
        get() = File(context.filesDir, "iptv-org").also { it.mkdirs() }

    private val indexFile: File
        get() = File(cacheDir, INDEX_FILE)

    private val indexLock = Any()

    @Volatile
    private var cachedLogoIndex: ChannelLogoIndex? = null

    @Volatile
    private var cachedFast: FreeFastSnapshot? = null

    fun hasCache(): Boolean = FILES.filter { it != "logos.json" }.all { File(cacheDir, it).exists() }

    fun hasLogoIndex(): Boolean {
        if (cachedLogoIndex?.isEmpty == false) return true
        return indexFile.exists() && indexFile.length() > 2L
    }

    fun logoIndex(): ChannelLogoIndex {
        cachedLogoIndex?.let { return it }
        synchronized(indexLock) {
            cachedLogoIndex?.let { return it }
            val loaded = loadIndex()
            cachedLogoIndex = loaded
            return loaded
        }
    }

    fun download(force: Boolean = false) {
        cachedFast = null
        FILES.forEach { name ->
            val dest = File(cacheDir, name)
            if (!force && dest.exists() && dest.length() > 0) return@forEach
            val request = Request.Builder().url(BASE + name).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (name == "logos.json") return@forEach
                    error("iptv-org $name HTTP ${response.code}")
                }
                val body = response.body ?: return@forEach
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        }
    }

    fun parse(): IptvOrgSnapshot {
        val blocklist = HashSet<String>()
        forEachRecord<IptvOrgBlocklistDto>("blocklist.json") { dto ->
            dto.channel?.takeIf { it.isNotBlank() }?.let { blocklist.add(it) }
        }
        val countries = HashMap<String, String>()
        forEachRecord<IptvOrgCountryDto>("countries.json") { dto ->
            val code = dto.code?.takeIf { it.isNotBlank() }?.uppercase() ?: return@forEachRecord
            countries[code] = dto.name?.takeIf { it.isNotBlank() } ?: code
        }
        val streams = HashMap<String, StreamPick>()
        forEachRecord<IptvOrgStreamDto>("streams.json") { dto ->
            val channel = dto.channel?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val url = dto.url?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            if ("geo" in dto.label.orEmpty().lowercase()) return@forEachRecord
            val quality = qualityRank(dto.quality)
            val existing = streams[channel]
            if (existing == null) {
                streams[channel] = StreamPick(
                    url,
                    quality,
                    referrer = dto.headerReferrer,
                    userAgent = dto.userAgent,
                )
            } else if (quality > existing.quality) {
                existing.fallbackUrl = existing.url
                existing.url = url
                existing.quality = quality
                existing.referrer = dto.headerReferrer
                existing.userAgent = dto.userAgent
            } else if (existing.fallbackUrl == null) {
                existing.fallbackUrl = url
            }
        }
        val logoScores = HashMap<String, Int>()
        val logos = HashMap<String, String>()
        forEachRecord<IptvOrgLogoDto>("logos.json") { dto ->
            val channel = dto.channel?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val url = dto.url?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val score = (if (dto.inUse) 1_000_000 else 0) + dto.width * dto.height.coerceAtLeast(0)
            val previous = logoScores[channel] ?: -1
            if (score > previous) {
                logoScores[channel] = score
                logos[channel] = url
            }
        }

        val usedIds = HashSet<Int>()
        val channels = ArrayList<Channel>()
        val categoriesById = LinkedHashMap<String, Category>()
        val byId = HashMap<String, String>(logos.size * 2)
        val byName = HashMap<String, String>(logos.size)
        forEachRecord<IptvOrgChannelDto>("channels.json") { dto ->
            val id = dto.id?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val name = dto.name?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val nsfw = id in blocklist || dto.isNsfw ||
                dto.categories.any { it.equals("xxx", ignoreCase = true) }
            val logo = logos[id]
            if (!nsfw && logo != null) {
                ChannelLogoIndex.putId(byId, id, logo)
                ChannelLogoIndex.putName(byName, name, logo)
                dto.altNames.forEach { alt -> ChannelLogoIndex.putName(byName, alt, logo) }
            }
            if (nsfw || !dto.closed.isNullOrBlank()) return@forEachRecord
            val pick = streams[id] ?: return@forEachRecord
            val country = dto.country?.uppercase().orEmpty()
            val countryName = countries[country] ?: country.ifBlank { "International" }
            val categoryId = if (country.isNotBlank()) {
                ChannelSource.countryCategoryId(country)
            } else {
                ChannelSource.genreCategoryId(dto.categories.firstOrNull() ?: "general")
            }
            val categoryName = if (country.isNotBlank()) {
                ChannelSource.displayCountryName(country, countryName)
            } else {
                dto.categories.firstOrNull()?.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                } ?: "General"
            }
            categoriesById.putIfAbsent(
                categoryId,
                Category(
                    id = categoryId,
                    name = categoryName,
                    feedType = FeedType.LIVE,
                    source = ChannelSource.IPTV_ORG,
                ),
            )
            var streamId = ChannelSource.orgStreamId(id)
            while (!usedIds.add(streamId)) streamId++
            channels += Channel(
                streamId = streamId,
                name = name,
                logoUrl = logos[id],
                categoryId = categoryId,
                epgChannelId = id,
                source = ChannelSource.IPTV_ORG,
                externalId = id,
                streamUrl = pick.url,
                fallbackUrl = pick.fallbackUrl,
                referrer = pick.referrer,
                userAgent = pick.userAgent,
            )
        }

        val sortedCats = categoriesById.values
            .sortedWith(
                compareBy<Category> { ChannelSource.freeCountryPinIndex(it.id) }
                    .thenBy { it.name.lowercase() },
            )
            .mapIndexed { index, cat -> cat.copy(sortOrder = index) }
        rememberIndex(ChannelLogoIndex(byId, byName))
        return IptvOrgSnapshot(sortedCats, channels)
    }

    /** Live FAST movie/series channels from the same iptv-org files Live already uses. */
    fun fastVod(): FreeFastSnapshot {
        cachedFast?.let { return it }
        if (!hasCache()) return FreeFastSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val built = buildFastVod()
        cachedFast = built
        return built
    }

    private fun buildFastVod(): FreeFastSnapshot {
        val blocklist = HashSet<String>()
        forEachRecord<IptvOrgBlocklistDto>("blocklist.json") { dto ->
            dto.channel?.takeIf { it.isNotBlank() }?.let { blocklist.add(it) }
        }
        val streams = HashMap<String, StreamPick>()
        forEachRecord<IptvOrgStreamDto>("streams.json") { dto ->
            val channel = dto.channel?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val url = dto.url?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            if ("geo" in dto.label.orEmpty().lowercase()) return@forEachRecord
            val quality = qualityRank(dto.quality)
            val existing = streams[channel]
            if (existing == null || quality > existing.quality) {
                streams[channel] = StreamPick(
                    url,
                    quality,
                    fallbackUrl = existing?.url,
                    referrer = dto.headerReferrer,
                    userAgent = dto.userAgent,
                )
            } else if (existing.fallbackUrl == null) {
                existing.fallbackUrl = url
            }
        }
        val logos = HashMap<String, String>()
        forEachRecord<IptvOrgLogoDto>("logos.json") { dto ->
            val channel = dto.channel?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val url = dto.url?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            if (dto.inUse || !logos.containsKey(channel)) logos[channel] = url
        }

        val movies = ArrayList<ScoredFast<VodItem>>()
        val series = ArrayList<ScoredFast<SeriesItem>>()
        val episodes = ArrayList<Pair<Int, Episode>>()
        val usedMovieIds = HashSet<Int>()
        val usedSeriesIds = HashSet<Int>()
        val usedEpisodeIds = HashSet<Int>()

        forEachRecord<IptvOrgChannelDto>("channels.json") { dto ->
            val id = dto.id?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val name = dto.name?.takeIf { it.isNotBlank() } ?: return@forEachRecord
            val nsfw = id in blocklist || dto.isNsfw ||
                dto.categories.any { it.equals("xxx", ignoreCase = true) }
            if (nsfw || !dto.closed.isNullOrBlank()) return@forEachRecord
            val pick = streams[id] ?: return@forEachRecord
            val cats = dto.categories.map { it.lowercase() }.toSet()
            val kind = when {
                "movies" in cats -> FastKind.MOVIES
                "series" in cats -> FastKind.SERIES
                "classic" in cats -> FastKind.CLASSIC
                else -> return@forEachRecord
            }
            val country = dto.country?.uppercase().orEmpty()
            val pin = ChannelSource.freeCountryPinIndex(ChannelSource.countryCategoryId(country))
            when (kind) {
                FastKind.MOVIES, FastKind.CLASSIC -> {
                    val categoryId = if (kind == FastKind.CLASSIC) {
                        ChannelSource.FAST_VOD_CLASSIC
                    } else {
                        ChannelSource.FAST_VOD_MOVIES
                    }
                    val streamId = ChannelSource.uniqueHashedId(
                        id,
                        ChannelSource.FAST_MOVIE_ID_BASE,
                        usedMovieIds,
                        ChannelSource.ARCHIVE_ID_MASK,
                    )
                    movies += ScoredFast(
                        pin,
                        name,
                        VodItem(
                            streamId = streamId,
                            name = name,
                            posterUrl = logos[id],
                            categoryId = categoryId,
                            rating = null,
                            year = null,
                            duration = "Live",
                            containerExtension = "m3u8",
                            source = ChannelSource.IPTV_ORG,
                            externalId = id,
                            streamUrl = pick.url,
                            fallbackUrl = pick.fallbackUrl,
                        ),
                    )
                }
                FastKind.SERIES -> {
                    val seriesId = ChannelSource.uniqueHashedId(
                        id,
                        ChannelSource.FAST_SERIES_ID_BASE,
                        usedSeriesIds,
                        ChannelSource.ARCHIVE_ID_MASK,
                    )
                    series += ScoredFast(
                        pin,
                        name,
                        SeriesItem(
                            seriesId = seriesId,
                            name = name,
                            posterUrl = logos[id],
                            categoryId = ChannelSource.FAST_SER_CHANNELS,
                            rating = null,
                            plot = "Live series channel — plays whatever is on now, not on-demand episodes.",
                            source = ChannelSource.IPTV_ORG,
                            externalId = id,
                        ),
                    )
                    val episodeId = ChannelSource.uniqueHashedId(
                        id,
                        ChannelSource.FAST_EPISODE_ID_BASE,
                        usedEpisodeIds,
                        ChannelSource.ARCHIVE_ID_MASK,
                    )
                    episodes += seriesId to Episode(
                        id = episodeId.toString(),
                        episodeNum = 1,
                        seasonNum = 1,
                        title = "Watch live",
                        streamId = episodeId,
                        containerExtension = "m3u8",
                        info = EpisodeInfo(
                            plot = "Live channel. Not a stored episode list.",
                            durationSecs = null,
                            rating = null,
                            releaseDate = null,
                        ),
                        source = ChannelSource.IPTV_ORG,
                        externalId = id,
                        streamUrl = pick.url,
                        fallbackUrl = pick.fallbackUrl,
                    )
                }
            }
        }

        fun <T> takePinned(items: List<ScoredFast<T>>, cap: Int): List<T> =
            items.sortedWith(compareBy<ScoredFast<T>> { it.pin }.thenBy { it.name.lowercase() })
                .map { it.item }
                .take(cap)

        val movieItems = takePinned(movies, MOVIE_CAP)
        val seriesItems = takePinned(series, SERIES_CAP)
        val seriesIds = seriesItems.map { it.seriesId }.toSet()
        val movieCats = buildList {
            if (movieItems.any { it.categoryId == ChannelSource.FAST_VOD_MOVIES }) {
                add(
                    Category(
                        id = ChannelSource.FAST_VOD_MOVIES,
                        name = "Movie channels",
                        feedType = FeedType.VOD,
                        sortOrder = 0,
                        source = ChannelSource.IPTV_ORG,
                    ),
                )
            }
            if (movieItems.any { it.categoryId == ChannelSource.FAST_VOD_CLASSIC }) {
                add(
                    Category(
                        id = ChannelSource.FAST_VOD_CLASSIC,
                        name = "Classic channels",
                        feedType = FeedType.VOD,
                        sortOrder = 1,
                        source = ChannelSource.IPTV_ORG,
                    ),
                )
            }
        }
        val seriesCats = if (seriesItems.isEmpty()) {
            emptyList()
        } else {
            listOf(
                Category(
                    id = ChannelSource.FAST_SER_CHANNELS,
                    name = "Series channels",
                    feedType = FeedType.SERIES,
                    sortOrder = 0,
                    source = ChannelSource.IPTV_ORG,
                ),
            )
        }
        return FreeFastSnapshot(
            movieCategories = movieCats,
            movies = movieItems,
            seriesCategories = seriesCats,
            series = seriesItems,
            episodes = episodes.filter { it.first in seriesIds },
        )
    }

    private fun rememberIndex(index: ChannelLogoIndex) {
        cachedLogoIndex = index
        runCatching { persistIndex(index) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun persistIndex(index: ChannelLogoIndex) {
        val tmp = File(cacheDir, "$INDEX_FILE.tmp")
        tmp.outputStream().buffered().use { out ->
            json.encodeToStream(ChannelLogoIndexDto.serializer(), index.toDto(), out)
        }
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun loadIndex(): ChannelLogoIndex {
        val file = indexFile
        if (!file.exists() || file.length() <= 2L) return ChannelLogoIndex.EMPTY
        return runCatching {
            file.inputStream().buffered(64 * 1024).use { input ->
                ChannelLogoIndex.fromDto(json.decodeFromStream(ChannelLogoIndexDto.serializer(), input))
            }
        }.getOrDefault(ChannelLogoIndex.EMPTY)
    }

    private inline fun <reified T> forEachRecord(fileName: String, consumer: (T) -> Unit) {
        val file = File(cacheDir, fileName)
        if (!file.exists() || file.length() == 0L) return
        file.inputStream().buffered(64 * 1024).use { input ->
            json.decodeToSequence<T>(input, DecodeSequenceMode.ARRAY_WRAPPED).forEach(consumer)
        }
    }

    companion object {
        private const val BASE = "https://iptv-org.github.io/api/"
        private const val INDEX_FILE = "logo-index.json"
        private val FILES = listOf(
            "channels.json",
            "streams.json",
            "logos.json",
            "countries.json",
            "blocklist.json",
        )

        private const val MOVIE_CAP = 250
        private const val SERIES_CAP = 200

        fun qualityRank(quality: String?): Int {
            val value = quality?.lowercase().orEmpty()
            return when {
                "2160" in value || "4k" in value -> 4
                "1080" in value -> 3
                "720" in value -> 2
                "480" in value -> 1
                else -> 0
            }
        }
    }
}

private enum class FastKind { MOVIES, SERIES, CLASSIC }

private class ScoredFast<T>(val pin: Int, val name: String, val item: T)
