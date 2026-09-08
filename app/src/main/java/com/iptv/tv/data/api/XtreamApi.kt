package com.iptv.tv.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface XtreamApi {

    @GET
    suspend fun authenticate(@Url url: String): AuthResponseDto

    @GET
    suspend fun getLiveCategories(@Url url: String): List<CategoryDto>

    @GET
    suspend fun getLiveStreams(@Url url: String): List<LiveStreamDto>

    @GET
    suspend fun getVodCategories(@Url url: String): List<CategoryDto>

    @GET
    suspend fun getVodStreams(@Url url: String): List<VodStreamDto>

    @GET
    suspend fun getSeriesCategories(@Url url: String): List<CategoryDto>

    @GET
    suspend fun getSeries(@Url url: String): List<SeriesDto>

    @GET
    suspend fun getSeriesInfo(@Url url: String): SeriesInfoResponseDto

    @GET
    suspend fun getVodInfo(@Url url: String): VodInfoResponseDto

    @GET
    suspend fun getShortEpg(@Url url: String): ShortEpgResponseDto

    @Streaming
    @GET
    suspend fun downloadXmlTv(@Url url: String): ResponseBody
}

object XtreamUrlBuilder {
    private fun base(creds: com.iptv.tv.domain.model.ServerCredentials): HttpUrl =
        requireNotNull(creds.serverUrl.trimEnd('/').toHttpUrlOrNull()) { "Invalid IPTV server URL" }

    private fun apiUrl(
        creds: com.iptv.tv.domain.model.ServerCredentials,
        vararg parameters: Pair<String, String>,
    ): String = base(creds).newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", creds.username)
        .addQueryParameter("password", creds.password)
        .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }
        .build().toString()

    private fun streamUrl(
        creds: com.iptv.tv.domain.model.ServerCredentials,
        kind: String,
        streamId: Int,
        extension: String,
    ): String {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "ts" }
        return base(creds).newBuilder()
            .addPathSegment(kind)
            .addPathSegment(creds.username)
            .addPathSegment(creds.password)
            .addPathSegment("$streamId.$ext")
            .build().toString()
    }

    fun authUrl(creds: com.iptv.tv.domain.model.ServerCredentials) = apiUrl(creds)

    fun liveCategoriesUrl(creds: com.iptv.tv.domain.model.ServerCredentials) =
        apiUrl(creds, "action" to "get_live_categories")

    fun liveStreamsUrl(creds: com.iptv.tv.domain.model.ServerCredentials, categoryId: String? = null): String {
        return apiUrl(creds, *buildList {
            add("action" to "get_live_streams")
            categoryId?.let { add("category_id" to it) }
        }.toTypedArray())
    }

    fun vodCategoriesUrl(creds: com.iptv.tv.domain.model.ServerCredentials) =
        apiUrl(creds, "action" to "get_vod_categories")

    fun vodStreamsUrl(creds: com.iptv.tv.domain.model.ServerCredentials, categoryId: String? = null): String {
        return apiUrl(creds, *buildList {
            add("action" to "get_vod_streams")
            categoryId?.let { add("category_id" to it) }
        }.toTypedArray())
    }

    fun seriesCategoriesUrl(creds: com.iptv.tv.domain.model.ServerCredentials) =
        apiUrl(creds, "action" to "get_series_categories")

    fun seriesUrl(creds: com.iptv.tv.domain.model.ServerCredentials, categoryId: String? = null): String {
        return apiUrl(creds, *buildList {
            add("action" to "get_series")
            categoryId?.let { add("category_id" to it) }
        }.toTypedArray())
    }

    fun seriesInfoUrl(creds: com.iptv.tv.domain.model.ServerCredentials, seriesId: Int) =
        apiUrl(creds, "action" to "get_series_info", "series_id" to seriesId.toString())

    fun vodInfoUrl(creds: com.iptv.tv.domain.model.ServerCredentials, vodId: Int) =
        apiUrl(creds, "action" to "get_vod_info", "vod_id" to vodId.toString())

    fun shortEpgUrl(creds: com.iptv.tv.domain.model.ServerCredentials, streamId: Int, limit: Int = 4) =
        apiUrl(
            creds,
            "action" to "get_short_epg",
            "stream_id" to streamId.toString(),
            "limit" to limit.toString(),
        )

    /** Provider catch-up table for one archive channel (finished programmes with `has_archive`). */
    fun simpleDataTableUrl(creds: com.iptv.tv.domain.model.ServerCredentials, streamId: Int) =
        apiUrl(creds, "action" to "get_simple_data_table", "stream_id" to streamId.toString())

    fun xmlTvUrl(creds: com.iptv.tv.domain.model.ServerCredentials): String {
        return base(creds).newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
            .build().toString()
    }

    fun liveStreamUrl(creds: com.iptv.tv.domain.model.ServerCredentials, streamId: Int, ext: String = "ts"): String {
        return streamUrl(creds, "live", streamId, ext)
    }

    fun vodStreamUrl(creds: com.iptv.tv.domain.model.ServerCredentials, streamId: Int, ext: String = "mp4"): String {
        return streamUrl(creds, "movie", streamId, ext)
    }

    fun episodeStreamUrl(creds: com.iptv.tv.domain.model.ServerCredentials, streamId: Int, ext: String = "mp4"): String {
        return streamUrl(creds, "series", streamId, ext)
    }

    fun timeshiftUrl(
        creds: com.iptv.tv.domain.model.ServerCredentials,
        streamId: Int,
        startMs: Long,
        durationMinutes: Int,
        serverTimeZoneId: String? = null,
    ): String {
        val start = XtreamTime.timeshiftStart(startMs, serverTimeZoneId)
        val mins = durationMinutes.coerceIn(1, 24 * 60)
        return base(creds).newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(creds.username)
            .addPathSegment(creds.password)
            .addPathSegment(mins.toString())
            .addPathSegment(start)
            .addPathSegment("$streamId.ts")
            .build().toString()
    }
}
