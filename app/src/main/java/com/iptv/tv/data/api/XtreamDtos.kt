package com.iptv.tv.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AuthResponseDto(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
    @SerialName("server_info") val serverInfo: ServerInfoDto? = null,
)

@Serializable
data class UserInfoDto(
    val auth: JsonElement? = null,
    val username: String? = null,
    @SerialName("max_connections") val maxConnections: JsonElement? = null,
    @SerialName("active_cons") val activeCons: JsonElement? = null,
    val status: String? = null,
) {
    fun activeConsInt(): Int? = activeCons?.jsonPrimitive?.intOrNull
        ?: activeCons?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    fun authInt(): Int = auth?.jsonPrimitive?.intOrNull ?: auth?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    fun maxConnectionsInt(): Int = maxConnections?.jsonPrimitive?.intOrNull
        ?: maxConnections?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
}

@Serializable
data class ServerInfoDto(
    val url: String? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
    @SerialName("port") val port: String? = null,
    @SerialName("https_port") val httpsPort: String? = null,
    val timezone: String? = null,
)

@Serializable
data class CategoryDto(
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("category_name") val categoryName: String? = null,
) {
    fun id(): String = categoryId?.jsonPrimitive?.contentOrNull ?: categoryId?.jsonPrimitive?.intOrNull?.toString() ?: ""
}

@Serializable
data class LiveStreamDto(
    @SerialName("stream_id") val streamId: JsonElement? = null,
    val name: String? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("tv_archive") val tvArchive: JsonElement? = null,
    @SerialName("tv_archive_duration") val tvArchiveDuration: JsonElement? = null,
) {
    fun streamIdInt(): Int? = streamId?.jsonPrimitive?.intOrNull ?: streamId?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    fun categoryIdStr(): String = categoryId?.jsonPrimitive?.contentOrNull ?: categoryId?.jsonPrimitive?.intOrNull?.toString() ?: ""
    fun tvArchiveInt(): Int = xtreamFlagInt(tvArchive)
    fun tvArchiveDurationInt(): Int = xtreamFlagInt(tvArchiveDuration)
}

@Serializable
data class VodStreamDto(
    @SerialName("stream_id") val streamId: JsonElement? = null,
    val name: String? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    val rating: String? = null,
    val year: String? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
) {
    fun streamIdInt(): Int? = streamId?.jsonPrimitive?.intOrNull ?: streamId?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    fun categoryIdStr(): String = categoryId?.jsonPrimitive?.contentOrNull ?: categoryId?.jsonPrimitive?.intOrNull?.toString() ?: ""
}

@Serializable
data class SeriesDto(
    @SerialName("series_id") val seriesId: JsonElement? = null,
    val name: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    val rating: String? = null,
) {
    fun seriesIdInt(): Int? = seriesId?.jsonPrimitive?.intOrNull ?: seriesId?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    fun categoryIdStr(): String = categoryId?.jsonPrimitive?.contentOrNull ?: categoryId?.jsonPrimitive?.intOrNull?.toString() ?: ""
}

@Serializable
data class ShortEpgResponseDto(
    @SerialName("epg_listings") val epgListings: List<EpgListingDto>? = null,
)

@Serializable
data class EpgListingDto(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("start") val start: String? = null,
    @SerialName("end") val end: String? = null,
    @SerialName("start_timestamp") val startTimestamp: JsonElement? = null,
    @SerialName("stop_timestamp") val stopTimestamp: JsonElement? = null,
    @SerialName("has_archive") val hasArchive: JsonElement? = null,
) {
    fun startMs(): Long = XtreamTime.listingMs(startTimestamp, start)
    fun endMs(): Long = XtreamTime.listingMs(stopTimestamp, end)
    fun hasArchiveInt(): Int = xtreamFlagInt(hasArchive)
    fun decodedTitle(): String = title?.let { decodeBase64IfNeeded(it) } ?: "Unknown"
    fun decodedDescription(): String? = description?.let { decodeBase64IfNeeded(it) }

    private fun decodeBase64IfNeeded(value: String): String {
        return try {
            if (value.matches(Regex("^[A-Za-z0-9+/=]+$")) && value.length > 8) {
                String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
            } else value
        } catch (_: Exception) {
            value
        }
    }
}

@Serializable
data class VodInfoResponseDto(
    val info: VodInfoDto? = null,
    @SerialName("movie_data") val movieData: VodStreamDto? = null,
)

@Serializable
data class VodInfoDto(
    val name: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    val rating: String? = null,
    val year: String? = null,
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("releasedate") val releaseDate: String? = null,
    @SerialName("tmdb_id") val tmdbId: JsonElement? = null,
    @SerialName("imdb_id") val imdbId: JsonElement? = null,
)

@Serializable
data class SeriesInfoResponseDto(
    val info: SeriesInfoDto? = null,
    val episodes: Map<String, List<EpisodeDto>>? = null,
)

@Serializable
data class SeriesInfoDto(
    val name: String? = null,
    val cover: String? = null,
    val plot: String? = null,
    val year: String? = null,
    @SerialName("tmdb_id") val tmdbId: JsonElement? = null,
    @SerialName("imdb_id") val imdbId: JsonElement? = null,
)

@Serializable
data class EpisodeDto(
    val id: String? = null,
    @SerialName("episode_num") val episodeNum: JsonElement? = null,
    @SerialName("season") val season: JsonElement? = null,
    val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    val info: EpisodeInfoDto? = null,
) {
    fun episodeNumInt(): Int = episodeNum?.jsonPrimitive?.intOrNull ?: episodeNum?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    fun seasonInt(): Int = season?.jsonPrimitive?.intOrNull ?: season?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
}

@Serializable
data class EpisodeInfoDto(
    val plot: String? = null,
    @SerialName("duration_secs") val durationSecs: JsonElement? = null,
    val rating: String? = null,
    @SerialName("releasedate") val releaseDate: String? = null,
) {
    fun durationSecsInt(): Int? = durationSecs?.jsonPrimitive?.intOrNull
        ?: durationSecs?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

/** Xtream panels send 0/1, "1", or true/false for archive flags. */
private fun xtreamFlagInt(value: JsonElement?): Int {
    val primitive = value as? JsonPrimitive ?: return 0
    primitive.intOrNull?.let { return it }
    primitive.booleanOrNull?.let { return if (it) 1 else 0 }
    val text = primitive.contentOrNull?.trim().orEmpty()
    text.toIntOrNull()?.let { return it }
    return when (text.lowercase()) {
        "true", "yes", "on" -> 1
        else -> 0
    }
}

