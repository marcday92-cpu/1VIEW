package com.iptv.tv.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class ArchiveSearchResponse(
    val response: ArchiveSearchBody = ArchiveSearchBody(),
    val error: String? = null,
)

@Serializable
data class ArchiveScrapeResponse(
    val items: List<ArchiveDoc> = emptyList(),
    val count: Int = 0,
    val cursor: String? = null,
    val error: String? = null,
)

@Serializable
data class ArchiveSearchBody(
    val numFound: Int = 0,
    val docs: List<ArchiveDoc> = emptyList(),
)

@Serializable
data class ArchiveDoc(
    val identifier: String? = null,
    val title: String? = null,
    val year: JsonElement? = null,
    val date: String? = null,
    val description: JsonElement? = null,
    val creator: JsonElement? = null,
    val licenseurl: String? = null,
    val collection: JsonElement? = null,
    val subject: JsonElement? = null,
    val downloads: Int = 0,
) {
    fun id(): String? = identifier?.trim()?.takeIf { it.isNotBlank() }
    fun displayTitle(): String? = title?.trim()?.takeIf { it.isNotBlank() }
    fun yearText(): String? = year.asText()?.let { ArchiveFields.yearOf(it) }
        ?: date?.let { ArchiveFields.yearOf(it) }
    fun plot(): String? = description.asText()?.trim()?.takeIf { it.isNotBlank() }
    fun creatorName(): String? = creator.asTextList()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && it.lowercase() !in SKIP_CREATORS }
    fun subjects(): List<String> = subject.asTextList().map { it.trim() }.filter { it.isNotBlank() }
    fun collections(): List<String> = collection.asTextList().map { it.trim() }.filter { it.isNotBlank() }
}

@Serializable
data class ArchiveMetadataResponse(
    val files: List<ArchiveMetadataFile> = emptyList(),
    val metadata: ArchiveItemMetadata? = null,
)

@Serializable
data class ArchiveItemMetadata(
    val identifier: String? = null,
    val title: String? = null,
)

@Serializable
data class ArchiveMetadataFile(
    val name: String? = null,
    val format: String? = null,
    val source: String? = null,
    val size: JsonElement? = null,
    val length: JsonElement? = null,
)

@Serializable
data class ArchiveSnapshotDto(
    val movies: List<ArchiveMovieSnap> = emptyList(),
    val series: List<ArchiveSeriesSnap> = emptyList(),
    val episodes: List<ArchiveEpisodeSnap> = emptyList(),
)

@Serializable
data class ArchiveMovieSnap(
    val streamId: Int,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String,
    val categoryName: String,
    val year: String? = null,
    val plot: String? = null,
    val identifier: String,
    val containerExtension: String = "mp4",
)

@Serializable
data class ArchiveSeriesSnap(
    val seriesId: Int,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String,
    val categoryName: String,
    val plot: String? = null,
    val showKey: String,
)

@Serializable
data class ArchiveEpisodeSnap(
    val streamId: Int,
    val seriesId: Int,
    val episodeNum: Int,
    val seasonNum: Int,
    val title: String,
    val identifier: String,
    val containerExtension: String = "mp4",
    val plot: String? = null,
)

@Serializable
data class ArchivePlaybackIndexDto(
    val items: Map<String, ArchivePlaybackSnap> = emptyMap(),
)

@Serializable
data class ArchivePlaybackSnap(
    val url: String,
    val fallbackUrl: String? = null,
    val containerExtension: String = "mp4",
    val subtitleUrl: String? = null,
)

object ArchiveFields {
    fun yearOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        return YEAR.find(text)?.groupValues?.getOrNull(1)
    }

    private val YEAR = Regex("""((?:18|19|20)\d{2})""")
}

fun JsonElement?.asText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?: intOrNull?.toString()
        ?: longOrNull?.toString()
    is JsonArray -> firstOrNull()?.asText()
    else -> null
}

fun JsonElement?.asTextList(): List<String> = when (this) {
    null, JsonNull -> emptyList()
    is JsonArray -> mapNotNull { it.asText() }
    is JsonPrimitive -> asText()?.let { listOf(it) }.orEmpty()
    else -> emptyList()
}

private val SKIP_CREATORS = setOf(
    "internet archive", "various", "unknown", "n/a", "none", "anonymous",
)
