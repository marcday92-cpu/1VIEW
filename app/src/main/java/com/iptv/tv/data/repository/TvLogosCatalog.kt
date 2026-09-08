package com.iptv.tv.data.repository

import android.content.Context
import com.iptv.tv.domain.model.ChannelSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class GitTreeDto(
    val tree: List<GitTreeEntryDto> = emptyList(),
)

@Serializable
private data class GitTreeEntryDto(
    val path: String? = null,
    val type: String? = null,
)

/**
 * Filename index for tv-logo/tv-logos. PNGs stay on jsDelivr; we only cache paths.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class TvLogosCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val cacheDir: File
        get() = File(context.filesDir, "tv-logos").also { it.mkdirs() }

    private val indexFile: File
        get() = File(cacheDir, INDEX_FILE)

    private val indexLock = Any()

    @Volatile
    private var cached: ChannelLogoIndex? = null

    fun hasIndex(): Boolean {
        if (cached?.isEmpty == false) return true
        return indexFile.exists() && indexFile.length() > 2L
    }

    fun logoIndex(): ChannelLogoIndex {
        cached?.let { return it }
        synchronized(indexLock) {
            cached?.let { return it }
            val loaded = loadIndex()
            cached = loaded
            return loaded
        }
    }

    fun download(force: Boolean = false) {
        if (!force && hasIndex() && indexFile.lastModified() > System.currentTimeMillis() - TTL_MS) {
            return
        }
        val request = Request.Builder()
            .url(TREE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        val tree = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("tv-logos tree HTTP ${response.code}")
            val body = response.body ?: error("tv-logos empty body")
            json.decodeFromStream(GitTreeDto.serializer(), body.byteStream())
        }
        remember(indexFromTree(tree))
    }

    private fun indexFromTree(tree: GitTreeDto): ChannelLogoIndex {
        val byName = HashMap<String, String>(8_192)
        val scores = HashMap<String, Int>(8_192)
        for (entry in tree.tree) {
            if (entry.type != null && entry.type != "blob") continue
            val path = entry.path?.takeIf { it.startsWith("countries/") && it.endsWith(".png") }
                ?: continue
            val fileName = path.substringAfterLast('/')
            val url = CDN + path
            val score = fileScore(fileName)
            for (key in keysFromFilename(fileName)) {
                if (key.length < ChannelLogoIndex.MIN_KEY) continue
                val previous = scores[key] ?: -1
                if (score > previous) {
                    scores[key] = score
                    byName[key] = url
                }
            }
        }
        return ChannelLogoIndex(byName = byName)
    }

    private fun remember(index: ChannelLogoIndex) {
        cached = index
        runCatching {
            val tmp = File(cacheDir, "$INDEX_FILE.${System.nanoTime()}.tmp")
            tmp.outputStream().buffered().use { out ->
                json.encodeToStream(ChannelLogoIndexDto.serializer(), index.toDto(), out)
            }
            if (!tmp.renameTo(indexFile)) {
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun loadIndex(): ChannelLogoIndex {
        val file = indexFile
        if (!file.exists() || file.length() <= 2L) return ChannelLogoIndex.EMPTY
        return runCatching {
            file.inputStream().buffered(64 * 1024).use { input ->
                ChannelLogoIndex.fromDto(json.decodeFromStream(ChannelLogoIndexDto.serializer(), input))
            }
        }.getOrDefault(ChannelLogoIndex.EMPTY)
    }

    companion object {
        private const val USER_AGENT = "1VIEW-TV"
        private const val TREE_URL =
            "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
        const val CDN = "https://cdn.jsdelivr.net/gh/tv-logo/tv-logos@main/"
        private const val INDEX_FILE = "logo-index.json"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        internal fun keysFromFilename(fileName: String): List<String> {
            var base = fileName.substringBeforeLast('.').lowercase()
            base = COUNTRY_SUFFIX.replace(base, "")
            base = STYLE_SUFFIX.replace(base, "")
            base = QUALITY_SUFFIX.replace(base, "")
            val spaced = base.replace('-', ' ').trim()
            if (spaced.length < 3) return emptyList()
            return ChannelSource.lookupKeys(spaced)
        }

        internal fun fileScore(fileName: String): Int {
            val lower = fileName.lowercase()
            return when {
                "-icon-" in lower || "-icon." in lower -> 0
                "-hz-" in lower || lower.endsWith("-hz.png") -> 3
                else -> 2
            }
        }

        internal fun constructedUrls(name: String): List<String> {
            val slug = ChannelSource.hyphenSlug(name) ?: return emptyList()
            if (slug.length < 3) return emptyList()
            return listOf(
                "${CDN}countries/united-kingdom/$slug-hz-uk.png",
                "${CDN}countries/united-kingdom/$slug-uk.png",
                "${CDN}countries/ireland/$slug-ie.png",
            )
        }

        private val COUNTRY_SUFFIX = Regex("""-(uk|gb|us|usa|ie|au|ca|nz|de|it|fr|es)$""")
        private val STYLE_SUFFIX = Regex("""-(icon|hz|logo|light|dark)$""")
        private val QUALITY_SUFFIX = Regex("""-(uhd|fhd|hd|sd|4k)$""")
    }
}
