package com.iptv.tv.data.repository

import android.content.Context
import android.util.Log
import com.iptv.tv.data.api.ArchiveDoc
import com.iptv.tv.data.api.ArchiveEpisodeSnap
import com.iptv.tv.data.api.ArchiveMetadataResponse
import com.iptv.tv.data.api.ArchiveMovieSnap
import com.iptv.tv.data.api.ArchivePlaybackIndexDto
import com.iptv.tv.data.api.ArchivePlaybackSnap
import com.iptv.tv.data.api.ArchiveScrapeResponse
import com.iptv.tv.data.api.ArchiveSearchResponse
import com.iptv.tv.data.api.ArchiveSeriesSnap
import com.iptv.tv.data.api.ArchiveSnapshotDto
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.EpisodeInfo
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.SeriesItem
import com.iptv.tv.domain.model.VodItem
import com.iptv.tv.player.VlcEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ArchiveVodSnapshot(
    val movieCategories: List<Category>,
    val movies: List<VodItem>,
    val seriesCategories: List<Category>,
    val series: List<SeriesItem>,
    val episodes: List<Pair<Int, Episode>>,
)

@Singleton
class ArchiveOrgCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val cacheDir: File
        get() = File(context.filesDir, DIR).also { it.mkdirs() }

    private val snapshotFile: File get() = File(cacheDir, SNAPSHOT_FILE)
    private val playbackFile: File get() = File(cacheDir, PLAYBACK_FILE)
    private val playbackLock = Any()

    @Volatile
    private var playbackIndex: MutableMap<String, ArchivePlaybackSnap> = linkedMapOf()

    fun hasCache(): Boolean = snapshotFile.exists() && snapshotFile.length() > 16L

    /** Copy the bundled first page so Movies/Series are never empty while Archive.org is slow. */
    fun installSeedIfNeeded() {
        if (hasCache()) return
        SEED_FILES.forEach { name ->
            val dest = File(cacheDir, name)
            val usable = dest.exists() && dest.length() > 16L && loadDocs(name).isNotEmpty()
            if (usable) return@forEach
            runCatching {
                context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
            }.onSuccess { text ->
                if (text.length > 16) atomicWrite(dest, text)
            }.onFailure { error ->
                Log.w(TAG, "seed $name missing", error)
            }
        }
    }

    fun ensureLocalSnapshot(): ArchiveVodSnapshot? {
        installSeedIfNeeded()
        loadSnapshot()?.takeIf { it.movies.isNotEmpty() || it.series.isNotEmpty() }?.let { return it }
        return runCatching { parse() }.getOrNull()
            ?.takeIf { it.movies.isNotEmpty() || it.series.isNotEmpty() }
    }

    fun download(force: Boolean = false) {
        val searchClient = client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
        var fetched = 0
        QUERIES.forEach { query ->
            val dest = File(cacheDir, query.fileName)
            if (!force && dest.exists() && dest.length() > 16L && loadDocs(query.fileName).isNotEmpty()) {
                fetched++
                return@forEach
            }
            val docs = runCatching { fetchDocs(searchClient, query) }.getOrElse { error ->
                Log.w(TAG, "archive.org ${query.fileName} failed", error)
                emptyList()
            }
            if (docs.isEmpty()) return@forEach
            val wrapped = json.encodeToString(
                ArchiveSearchResponse.serializer(),
                ArchiveSearchResponse(com.iptv.tv.data.api.ArchiveSearchBody(docs.size, docs)),
            )
            atomicWrite(dest, wrapped)
            fetched++
        }
        if (fetched == 0 && !hasCache()) {
            error("Archive.org search timed out")
        }
    }

    fun parse(): ArchiveVodSnapshot {
        val movies = ArrayList<ArchiveMovieSnap>()
        val seriesMap = LinkedHashMap<String, MutableSeries>()
        val usedMovieIds = HashSet<Int>()
        val usedSeriesIds = HashSet<Int>()
        val usedEpisodeIds = HashSet<Int>()

        loadDocs(MOVIE_FILE).forEach { doc ->
            toMovie(doc, usedMovieIds)?.let { movies += it }
        }
        loadDocs(PD_MOVIE_FILE).forEach { doc ->
            if (movies.any { it.identifier == doc.id() }) return@forEach
            toMovie(doc, usedMovieIds)?.let { movies += it }
        }
        loadDocs(TV_FILE).forEach { doc ->
            addEpisode(doc, seriesMap, usedSeriesIds, usedEpisodeIds)
        }

        val snapshot = ArchiveSnapshotDto(
            movies = movies.distinctBy { it.identifier },
            series = seriesMap.values
                .filter { it.episodes.isNotEmpty() }
                .map { it.toSnap() },
            episodes = seriesMap.values.flatMap { it.episodes },
        )
        atomicWrite(snapshotFile, json.encodeToString(ArchiveSnapshotDto.serializer(), snapshot))
        return snapshot.toDomain()
    }

    fun loadSnapshot(): ArchiveVodSnapshot? {
        if (!hasCache()) return null
        return runCatching {
            json.decodeFromString(ArchiveSnapshotDto.serializer(), snapshotFile.readText()).toDomain()
        }.getOrNull()
    }

    fun resolvePlayback(identifier: String): ArchivePlaybackPick? {
        val id = identifier.trim()
        if (id.isBlank()) return null
        val cached = synchronized(playbackLock) {
            loadPlaybackIndexLocked()
            playbackIndex[id]
        }
        cached?.let { snap ->
            return ArchivePlaybackPick(snap.url, snap.fallbackUrl, snap.containerExtension, snap.subtitleUrl)
        }
        val meta = fetchMetadata(id) ?: return null
        val pick = ArchiveFilePicker.pick(id, meta.files) ?: return null
        rememberPlayback(id, pick)
        return pick
    }

    private fun fetchMetadata(identifier: String): ArchiveMetadataResponse? {
        val url = "https://archive.org/metadata/$identifier"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VlcEngine.DEFAULT_UA)
            .header("Accept", "application/json")
            .header("Referer", "https://archive.org/details/$identifier")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                json.decodeFromString(ArchiveMetadataResponse.serializer(), body)
            }
        }.getOrNull()
    }

    private fun rememberPlayback(identifier: String, pick: ArchivePlaybackPick) {
        synchronized(playbackLock) {
            loadPlaybackIndexLocked()
            playbackIndex[identifier] = ArchivePlaybackSnap(
                url = pick.url,
                fallbackUrl = pick.fallbackUrl,
                containerExtension = pick.containerExtension,
                subtitleUrl = pick.subtitleUrl,
            )
            atomicWrite(
                playbackFile,
                json.encodeToString(ArchivePlaybackIndexDto.serializer(), ArchivePlaybackIndexDto(playbackIndex.toMap())),
            )
        }
    }

    private fun loadPlaybackIndex() {
        synchronized(playbackLock) { loadPlaybackIndexLocked() }
    }

    private fun loadPlaybackIndexLocked() {
        if (playbackIndex.isNotEmpty() || !playbackFile.exists()) return
        val loaded = runCatching {
            json.decodeFromString(ArchivePlaybackIndexDto.serializer(), playbackFile.readText())
        }.getOrNull()
        if (loaded != null) playbackIndex.putAll(loaded.items)
    }

    private fun loadDocs(fileName: String): List<ArchiveDoc> {
        val file = File(cacheDir, fileName)
        if (!file.exists() || file.length() < 8L) return emptyList()
        return runCatching {
            json.decodeFromString(ArchiveSearchResponse.serializer(), file.readText()).response.docs
        }.getOrDefault(emptyList())
    }

    private fun toMovie(doc: ArchiveDoc, usedIds: MutableSet<Int>): ArchiveMovieSnap? {
        val id = doc.id() ?: return null
        val title = doc.displayTitle() ?: return null
        if (isBlocked(title, doc.subjects())) return falseMovie()
        val genre = movieGenre(doc)
        return ArchiveMovieSnap(
            streamId = ChannelSource.uniqueHashedId(
                id,
                ChannelSource.ARCHIVE_MOVIE_ID_BASE,
                usedIds,
                ChannelSource.ARCHIVE_ID_MASK,
            ),
            name = title,
            posterUrl = ArchiveFilePicker.posterUrl(id),
            categoryId = ChannelSource.vodCategoryId(genre),
            categoryName = genre,
            year = doc.yearText(),
            plot = doc.plot(),
            identifier = id,
        )
    }

    private fun falseMovie(): ArchiveMovieSnap? = null

    private fun addEpisode(
        doc: ArchiveDoc,
        seriesMap: LinkedHashMap<String, MutableSeries>,
        usedSeriesIds: MutableSet<Int>,
        usedEpisodeIds: MutableSet<Int>,
    ) {
        val id = doc.id() ?: return
        val title = doc.displayTitle() ?: return
        if (isBlocked(title, doc.subjects())) return
        val lower = title.lowercase()
        if ("complete series" in lower || "full series" in lower) return
        val parsed = ArchiveTitleParser.parse(title, doc.creatorName()) ?: return
        val key = ArchiveTitleParser.showKey(parsed.showName)
        if (key.length < 3) return
        val bucket = seriesMap.getOrPut(key) {
            val genre = seriesGenre(doc)
            MutableSeries(
                seriesId = ChannelSource.uniqueHashedId(
                    key,
                    ChannelSource.ARCHIVE_SERIES_ID_BASE,
                    usedSeriesIds,
                    ChannelSource.ARCHIVE_ID_MASK,
                ),
                name = parsed.showName,
                posterUrl = ArchiveFilePicker.posterUrl(id),
                categoryId = ChannelSource.seriesCategoryId(genre),
                categoryName = genre,
                plot = doc.plot(),
                showKey = key,
            )
        }
        if (bucket.posterUrl.isNullOrBlank()) {
            bucket.posterUrl = ArchiveFilePicker.posterUrl(id)
        }
        val episodeNum = if (parsed.episode > 0) parsed.episode else bucket.episodes.size + 1
        bucket.episodes += ArchiveEpisodeSnap(
            streamId = ChannelSource.uniqueHashedId(
                id,
                ChannelSource.ARCHIVE_EPISODE_ID_BASE,
                usedEpisodeIds,
                ChannelSource.ARCHIVE_ID_MASK,
            ),
            seriesId = bucket.seriesId,
            episodeNum = episodeNum,
            seasonNum = parsed.season.coerceAtLeast(1),
            title = parsed.episodeTitle,
            identifier = id,
            plot = doc.plot(),
        )
    }

    private fun movieGenre(doc: ArchiveDoc): String {
        val hit = doc.subjects().firstNotNullOfOrNull { subject ->
            MOVIE_GENRES.entries.firstOrNull { (key, _) ->
                subject.contains(key, ignoreCase = true)
            }?.value
        }
        return hit ?: "Feature films"
    }

    private fun seriesGenre(doc: ArchiveDoc): String {
        val hit = doc.subjects().firstNotNullOfOrNull { subject ->
            SERIES_GENRES.entries.firstOrNull { (key, _) ->
                subject.contains(key, ignoreCase = true)
            }?.value
        }
        return hit ?: "Classic TV"
    }

    private fun isBlocked(title: String, subjects: List<String>): Boolean {
        val haystack = (title + " " + subjects.joinToString(" ")).lowercase()
        return NSFW.any { it in haystack }
    }

    private fun fetchDocs(searchClient: OkHttpClient, query: SearchQuery): List<ArchiveDoc> {
        val scraped = runCatching { fetchScrape(searchClient, query) }.getOrNull()
        if (!scraped.isNullOrEmpty()) return scraped
        return fetchAdvanced(searchClient, query)
    }

    private fun fetchScrape(searchClient: OkHttpClient, query: SearchQuery): List<ArchiveDoc> {
        val url = SCRAPE.toHttpUrl().newBuilder()
            .addQueryParameter("q", query.lucene)
            .addQueryParameter("fields", FIELDS.joinToString(","))
            .addQueryParameter("count", query.rows.coerceAtLeast(100).toString())
            .build()
        val body = execute(searchClient, url.toString(), query.fileName)
        val parsed = json.decodeFromString(ArchiveScrapeResponse.serializer(), body)
        if (!parsed.error.isNullOrBlank()) error(parsed.error)
        return parsed.items
    }

    private fun fetchAdvanced(searchClient: OkHttpClient, query: SearchQuery): List<ArchiveDoc> {
        val docs = ArrayList<ArchiveDoc>()
        for (page in 1..query.pages) {
            val url = SEARCH.toHttpUrl().newBuilder()
                .addQueryParameter("q", query.lucene)
                .addQueryParameter("rows", query.rows.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("output", "json")
                .addQueryParameter("sort[]", "downloads desc")
                .also { builder -> FIELDS.forEach { field -> builder.addQueryParameter("fl[]", field) } }
                .build()
            val body = execute(searchClient, url.toString(), query.fileName)
            val parsed = json.decodeFromString(ArchiveSearchResponse.serializer(), body)
            if (!parsed.error.isNullOrBlank()) error(parsed.error)
            docs += parsed.response.docs
        }
        return docs
    }

    private fun execute(searchClient: OkHttpClient, url: String, label: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VlcEngine.DEFAULT_UA)
            .header("Accept", "application/json")
            .build()
        searchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("archive.org $label HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private class MutableSeries(
        val seriesId: Int,
        val name: String,
        var posterUrl: String?,
        val categoryId: String,
        val categoryName: String,
        var plot: String?,
        val showKey: String,
        val episodes: MutableList<ArchiveEpisodeSnap> = mutableListOf(),
    ) {
        fun toSnap() = ArchiveSeriesSnap(
            seriesId = seriesId,
            name = name,
            posterUrl = posterUrl,
            categoryId = categoryId,
            categoryName = categoryName,
            plot = plot,
            showKey = showKey,
        )
    }

    companion object {
        private const val TAG = "IptvTv"
        private const val DIR = "archive-org"
        private const val ASSET_DIR = "archive-org"
        private const val SNAPSHOT_FILE = "snapshot.json"
        private const val PLAYBACK_FILE = "playback-index.json"
        private const val MOVIE_FILE = "movies-search.json"
        private const val PD_MOVIE_FILE = "pd-movies-search.json"
        private const val TV_FILE = "classic-tv-search.json"
        private const val SEARCH = "https://archive.org/advancedsearch.php"
        private const val SCRAPE = "https://archive.org/services/search/v1/scrape"
        private val SEED_FILES = listOf(MOVIE_FILE, TV_FILE)

        private val FIELDS = listOf(
            "identifier", "title", "year", "date", "description",
            "creator", "licenseurl", "collection", "subject", "downloads",
        )

        private val QUERIES = listOf(
            SearchQuery(
                fileName = MOVIE_FILE,
                lucene = "(collection:feature_films OR collection:SciFi_Horror OR collection:Comedy_Films) AND mediatype:movies",
                rows = 100,
                pages = 1,
            ),
            SearchQuery(
                fileName = TV_FILE,
                lucene = "collection:classic_tv AND mediatype:movies",
                rows = 100,
                pages = 2,
            ),
        )

        private val MOVIE_GENRES = linkedMapOf(
            "western" to "Westerns",
            "horror" to "Horror",
            "sci-fi" to "Sci-Fi",
            "science fiction" to "Sci-Fi",
            "comedy" to "Comedy",
            "drama" to "Drama",
            "crime" to "Crime",
            "mystery" to "Mystery",
            "thriller" to "Thriller",
            "romance" to "Romance",
            "musical" to "Musicals",
            "animation" to "Animation",
            "cartoon" to "Animation",
            "documentary" to "Documentary",
            "silent" to "Silent films",
            "war" to "War",
            "adventure" to "Adventure",
            "fantasy" to "Fantasy",
            "family" to "Family",
        )

        private val SERIES_GENRES = linkedMapOf(
            "western" to "Westerns",
            "sitcom" to "Comedy",
            "comedy" to "Comedy",
            "crime" to "Crime",
            "mystery" to "Mystery",
            "drama" to "Drama",
            "sci-fi" to "Sci-Fi",
            "science fiction" to "Sci-Fi",
            "horror" to "Horror",
            "adventure" to "Adventure",
            "anthology" to "Anthology",
        )

        private val NSFW = listOf("xxx", "porn", "nsfw", "erotic", "adult film", "adult movie")
    }

    private data class SearchQuery(
        val fileName: String,
        val lucene: String,
        val rows: Int,
        val pages: Int,
    )
}

private fun ArchiveSnapshotDto.toDomain(): ArchiveVodSnapshot {
    val movieCats = movies
        .groupBy { it.categoryId }
        .map { (id, items) ->
            Category(
                id = id,
                name = items.first().categoryName,
                feedType = FeedType.VOD,
                source = ChannelSource.ARCHIVE,
            )
        }
        .sortedBy { it.name.lowercase() }
        .mapIndexed { index, cat -> cat.copy(sortOrder = index) }
    val seriesCats = series
        .groupBy { it.categoryId }
        .map { (id, items) ->
            Category(
                id = id,
                name = items.first().categoryName,
                feedType = FeedType.SERIES,
                source = ChannelSource.ARCHIVE,
            )
        }
        .sortedBy { it.name.lowercase() }
        .mapIndexed { index, cat -> cat.copy(sortOrder = index) }
    return ArchiveVodSnapshot(
        movieCategories = movieCats,
        movies = movies.map { snap ->
            VodItem(
                streamId = snap.streamId,
                name = snap.name,
                posterUrl = snap.posterUrl,
                categoryId = snap.categoryId,
                rating = null,
                year = snap.year,
                duration = null,
                containerExtension = snap.containerExtension,
                source = ChannelSource.ARCHIVE,
                externalId = snap.identifier,
            )
        },
        seriesCategories = seriesCats,
        series = series.map { snap ->
            SeriesItem(
                seriesId = snap.seriesId,
                name = snap.name,
                posterUrl = snap.posterUrl,
                categoryId = snap.categoryId,
                rating = null,
                plot = snap.plot,
                source = ChannelSource.ARCHIVE,
                externalId = snap.showKey,
            )
        },
        episodes = episodes.map { snap ->
            snap.seriesId to Episode(
                id = snap.streamId.toString(),
                episodeNum = snap.episodeNum,
                seasonNum = snap.seasonNum,
                title = snap.title,
                streamId = snap.streamId,
                containerExtension = snap.containerExtension,
                info = EpisodeInfo(plot = snap.plot, durationSecs = null, rating = null, releaseDate = null),
                source = ChannelSource.ARCHIVE,
                externalId = snap.identifier,
            )
        },
    )
}
