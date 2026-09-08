package com.iptv.tv.data.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class XtreamDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun tvArchiveAcceptsIntStringAndBoolean() {
        assertEquals(1, decode("""{"stream_id":1,"tv_archive":1}""").tvArchiveInt())
        assertEquals(7, decode("""{"stream_id":1,"tv_archive":"7"}""").tvArchiveInt())
        assertEquals(1, decode("""{"stream_id":1,"tv_archive":true}""").tvArchiveInt())
        assertEquals(0, decode("""{"stream_id":1,"tv_archive":false}""").tvArchiveInt())
        assertEquals(14, decode("""{"stream_id":1,"tv_archive_duration":14}""").tvArchiveDurationInt())
    }

    private fun decode(raw: String): LiveStreamDto = json.decodeFromString(raw)
}
