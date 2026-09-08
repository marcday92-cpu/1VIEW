package com.iptv.tv.subtitle

import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.SubtitleCandidate
import com.iptv.tv.domain.model.SubtitleQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class SubDlSearchResponse(
    val status: JsonElement? = null,
    val message: String? = null,
    val error: String? = null,
    val subtitles: List<SubDlSubtitle>? = null,
) {
    fun succeeded(): Boolean {
        val primitive = status?.jsonPrimitive ?: return true
        return primitive.booleanOrNull == true ||
            primitive.intOrNull == 1 ||
            primitive.contentOrNull.equals("true", ignoreCase = true)
    }
}

@Serializable
private data class SubDlSubtitle(
    @SerialName("sd_id") val sdId: JsonElement? = null,
    val name: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
    val lang: String? = null,
    val language: String? = null,
    val url: String? = null,
    val author: String? = null,
    val subtitlePage: String? = null,
    val season: JsonElement? = null,
    val episode: JsonElement? = null,
    val hi: JsonElement? = null,
    @SerialName("hearing_impaired") val hearingImpaired: JsonElement? = null,
) {
    fun seasonNumber(): Int? = season?.jsonPrimitive?.intOrNull
        ?: season?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    fun episodeNumber(): Int? = episode?.jsonPrimitive?.intOrNull
        ?: episode?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    fun isHearingImpaired(): Boolean = boolish(hearingImpaired) || boolish(hi)

    private fun boolish(value: JsonElement?): Boolean {
        val primitive = value?.jsonPrimitive ?: return false
        return primitive.booleanOrNull == true ||
            primitive.intOrNull == 1 ||
            primitive.contentOrNull.equals("true", ignoreCase = true) ||
            primitive.contentOrNull == "1"
    }
}

@Singleton
class SubDlProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiConfig: SubtitleApiConfig,
) : SubtitleProvider {

    override val name = NAME

    override suspend fun search(query: SubtitleQuery): List<SubtitleCandidate> = withContext(Dispatchers.IO) {
        val apiKey = apiConfig.subdlApiKey()
        if (apiKey.isBlank()) {
            throw SubtitleException("This build has no SubDL key. Add SUBDL_API_KEY to local.properties and rebuild.")
        }
        val language = query.language.ifBlank { "en" }.uppercase()
        val urlBuilder = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("languages", language)
            .addQueryParameter("subs_per_page", "30")
        when (query.type) {
            ContentType.EPISODE -> urlBuilder.addQueryParameter("type", "tv")
            else -> urlBuilder.addQueryParameter("type", "movie")
        }
        val imdb = query.imdbId?.trim()?.takeIf { it.isNotBlank() }
        val tmdb = query.tmdbId?.trim()?.takeIf { it.isNotBlank() }
        when {
            imdb != null -> urlBuilder.addQueryParameter("imdb_id", imdb.removePrefix("tt").removePrefix("TT"))
            tmdb != null -> urlBuilder.addQueryParameter("tmdb_id", tmdb)
            else -> urlBuilder.addQueryParameter("film_name", query.title.trim())
        }
        query.year?.trim()?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("year", it) }
        query.season?.let { urlBuilder.addQueryParameter("season_number", it.toString()) }
        query.episode?.let { urlBuilder.addQueryParameter("episode_number", it.toString()) }
        query.filename?.trim()?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("file_name", it) }

        client.newCall(
            Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build(),
        ).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.code == 429) throw SubtitleException("SubDL is rate-limited. Try again in a minute.")
        if (!response.isSuccessful) throw SubtitleException(apiErrorMessage(response.code, body))
        val parsed = runCatching { json.decodeFromString<SubDlSearchResponse>(body) }
            .getOrElse { throw SubtitleException("SubDL sent a response we could not read.") }
        if (!parsed.succeeded()) throw SubtitleException(parsed.message ?: parsed.error ?: "SubDL search failed.")
        parsed.subtitles.orEmpty().mapNotNull { item ->
            val downloadUrl = absoluteDownloadUrl(item.url) ?: return@mapNotNull null
            val fileName = listOf(item.name, item.releaseName, item.author?.let { "English · $it" })
                .firstOrNull { !it.isNullOrBlank() }
                ?: "English subtitle"
            val lang = (item.language ?: item.lang ?: language).lowercase()
            val hi = item.isHearingImpaired()
            SubtitleCandidate(
                id = item.sdId?.jsonPrimitive?.contentOrNull ?: downloadUrl,
                fileName = fileName,
                language = lang,
                matchScore = SubtitleMatch.score(
                    query = query,
                    releaseName = item.releaseName.orEmpty(),
                    fileName = fileName,
                    language = lang,
                    hearingImpaired = hi,
                    season = item.seasonNumber(),
                    episode = item.episodeNumber(),
                ),
                hearingImpaired = hi,
                provider = NAME,
                downloadUrl = downloadUrl,
                releaseName = item.releaseName,
            )
        }.sortedByDescending { it.matchScore }
        }
    }

    override suspend fun download(candidate: SubtitleCandidate, destDir: File, episode: Pair<Int, Int>?): File = withContext(Dispatchers.IO) {
        val downloadUrl = candidate.downloadUrl
            ?: throw SubtitleException("That subtitle has no download link.")
        destDir.mkdirs()
        val hash = cacheHash(downloadUrl)
        val cached = destDir.listFiles().orEmpty()
            .filter { it.name.startsWith("subdl_$hash") && it.length() > 40 }
            .sortedBy { it.name }
        pickForEpisode(cached, episode)?.let { return@withContext it }

        val parsedUrl = runCatching {
            downloadUrl.toHttpUrl().also { url ->
                require(url.isHttps && url.host == DOWNLOAD_DOMAIN) { "Unexpected subtitle download host" }
            }
        }.getOrElse {
            throw SubtitleException("That subtitle download link was not valid.")
        }
        client.newCall(
            Request.Builder()
                .url(parsedUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
        if (response.code == 429) {
            throw SubtitleException("SubDL is rate-limited. Try again in a minute.")
        }
        if (response.code == 402) {
            throw SubtitleException("SubDL refused the download. Check the API key in Settings.")
        }
        if (!response.isSuccessful) {
            throw SubtitleException("Could not download that subtitle (${response.code}).")
        }
        val length = response.body?.contentLength() ?: -1
        if (length > MAX_DOWNLOAD_BYTES) throw SubtitleException("That subtitle download was too large.")
        val bytes = response.body?.bytes()
            ?: throw SubtitleException("SubDL sent an empty file.")
        if (bytes.size.toLong() > MAX_DOWNLOAD_BYTES) throw SubtitleException("That subtitle download was too large.")
        if (bytes.size < 20) {
            throw SubtitleException("SubDL sent an empty file.")
        }
        extractSubtitleFile(bytes, destDir, "subdl_$hash", episode)
        }
    }

    private fun extractSubtitleFile(bytes: ByteArray, destDir: File, baseName: String, episode: Pair<Int, Int>?): File {
        if (isZip(bytes)) {
            return unzipSubtitles(bytes, destDir, baseName, episode)
        }
        val ext = sniffExtension(bytes)
        val dest = File(destDir, "$baseName.$ext")
        dest.writeBytes(bytes)
        if (dest.length() < 20) throw SubtitleException("That subtitle file was empty.")
        return dest
    }

    /**
     * Extracts every subtitle file in the archive (a season pack holds one per episode) and
     * returns the one for [episode] when it can be told apart by its name, else the best
     * format in archive order. Partial output is deleted if the archive turns out to be bad.
     */
    private fun unzipSubtitles(bytes: ByteArray, destDir: File, baseName: String, episode: Pair<Int, Int>?): File {
        val extracted = mutableListOf<File>()
        var files = 0
        var written = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    files++
                    if (files > 80) throw SubtitleException("That subtitle archive was not usable.")
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext !in PREFERRED_EXTENSIONS) continue
                    // Keep the entry's own name (sanitised) so episode numbers survive for matching.
                    val safeName = entry.name.substringAfterLast('/').substringBeforeLast('.')
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    val dest = File(destDir, "${baseName}_${files}_$safeName.$ext")
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_UNCOMPRESSED) {
                                throw SubtitleException("That subtitle archive was not usable.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    extracted += dest
                }
            }
        } catch (error: SubtitleException) {
            extracted.forEach { it.delete() }
            throw error
        } catch (_: Exception) {
            extracted.forEach { it.delete() }
            throw SubtitleException("That subtitle archive was damaged.")
        }
        val file = pickForEpisode(extracted.filter { it.length() >= 20 }, episode)
            ?: throw SubtitleException("No subtitle file inside that download.")
        return file
    }

    /** Prefer a file whose name carries the wanted episode, then the best-ranked format. */
    private fun pickForEpisode(files: List<File>, episode: Pair<Int, Int>?): File? {
        if (files.isEmpty()) return null
        val byRank = files.sortedBy { PREFERRED_EXTENSIONS.indexOf(it.extension.lowercase()).let { r -> if (r < 0) 99 else r } }
        if (episode != null && files.size > 1) {
            val (season, ep) = episode
            byRank.firstOrNull { SubtitleMatch.mentionsEpisode(it.name, season, ep) }?.let { return it }
        }
        return byRank.first()
    }

    private fun absoluteDownloadUrl(raw: String?): String? {
        val path = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val candidate = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "$DOWNLOAD_HOST$path"
            else -> "$DOWNLOAD_HOST/$path"
        }
        return runCatching {
            candidate.toHttpUrl().takeIf { it.isHttps && it.host == DOWNLOAD_DOMAIN }?.toString()
        }.getOrNull()
    }

    private fun apiErrorMessage(code: Int, body: String): String {
        val parsed = runCatching { json.decodeFromString<SubDlSearchResponse>(body) }.getOrNull()
        parsed?.message?.takeIf { it.isNotBlank() }?.let { return it }
        parsed?.error?.takeIf { it.isNotBlank() }?.let { return it }
        return when (code) {
            401, 403 -> "SubDL rejected the API key."
            404 -> "SubDL found no matching subtitles."
            else -> "SubDL search failed ($code)."
        }
    }

    private fun cacheHash(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun sniffExtension(bytes: ByteArray): String {
        val head = bytes.decodeToString(0, minOf(bytes.size, 64)).trimStart()
        return when {
            head.startsWith("WEBVTT") -> "vtt"
            head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
    }

    companion object {
        const val NAME = "SubDL"
        private val PREFERRED_EXTENSIONS = listOf("srt", "vtt", "ttml", "xml")
        private const val SEARCH_URL = "https://api.subdl.com/api/v1/subtitles"
        private const val DOWNLOAD_HOST = "https://dl.subdl.com"
        private const val DOWNLOAD_DOMAIN = "dl.subdl.com"
        private const val USER_AGENT = "TV-IPTV/1.0 (Fire TV)"
        private const val MAX_UNCOMPRESSED = 6L * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
    }
}
