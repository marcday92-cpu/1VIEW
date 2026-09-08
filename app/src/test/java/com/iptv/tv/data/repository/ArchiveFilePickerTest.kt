package com.iptv.tv.data.repository

import com.iptv.tv.data.api.ArchiveMetadataFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveFilePickerTest {
    @Test
    fun prefersMp4H264DerivativeOverOriginalAvi() {
        val pick = ArchiveFilePicker.pick(
            "night_of_the_living_dead",
            listOf(
                ArchiveMetadataFile("film.avi", "AVI", "original", kotlinx.serialization.json.JsonPrimitive("2500000000")),
                ArchiveMetadataFile("film.mp4", "h.264", "derivative", kotlinx.serialization.json.JsonPrimitive("400000000")),
                ArchiveMetadataFile("film.gif", "Animated GIF", "derivative", kotlinx.serialization.json.JsonPrimitive("12000")),
            ),
        )
        assertNotNull(pick)
        assertTrue(pick.url.endsWith("film.mp4"))
        assertEquals("mp4", pick.containerExtension)
    }

    @Test
    fun prefers512kbMpeg4ForFireTv() {
        val pick = ArchiveFilePicker.pick(
            "his_girl_friday",
            listOf(
                ArchiveMetadataFile("movie_512kb.mp4", "512Kb MPEG4", "derivative", kotlinx.serialization.json.JsonPrimitive("80000000")),
                ArchiveMetadataFile("movie.ogv", "Ogg Video", "derivative", kotlinx.serialization.json.JsonPrimitive("90000000")),
            ),
        )
        assertNotNull(pick)
        assertTrue(pick.url.contains("512kb.mp4"))
    }

    @Test
    fun downloadUrlEncodesSpaces() {
        val url = ArchiveFilePicker.downloadUrl("item", "My Film.mp4")
        assertEquals("https://archive.org/download/item/My%20Film.mp4", url)
    }
}
