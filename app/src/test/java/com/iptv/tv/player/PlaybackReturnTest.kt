package com.iptv.tv.player

import com.iptv.tv.domain.model.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackReturnTest {
    @Test
    fun liveFromHomeReturnsToLive() {
        assertEquals("live", PlaybackReturn.route(ContentType.LIVE, PlaybackOpenedFrom.HOME))
    }

    @Test
    fun liveFromLiveStaysOnLive() {
        assertEquals("live", PlaybackReturn.route(ContentType.LIVE, PlaybackOpenedFrom.LIVE))
    }

    @Test
    fun liveFromGuideReturnsToGuide() {
        assertEquals("guide", PlaybackReturn.route(ContentType.LIVE, PlaybackOpenedFrom.GUIDE))
    }

    @Test
    fun movieFromHomeReturnsToHome() {
        assertEquals("home", PlaybackReturn.route(ContentType.MOVIE, PlaybackOpenedFrom.HOME))
    }

    @Test
    fun movieFromMoviesReturnsToMovies() {
        assertEquals("movies", PlaybackReturn.route(ContentType.MOVIE, PlaybackOpenedFrom.MOVIES))
    }

    @Test
    fun episodeFromHomeReturnsToHome() {
        assertEquals("home", PlaybackReturn.route(ContentType.EPISODE, PlaybackOpenedFrom.HOME))
    }

    @Test
    fun episodeFromSeriesReturnsToSeries() {
        assertEquals("series", PlaybackReturn.route(ContentType.EPISODE, PlaybackOpenedFrom.SERIES))
    }

    @Test
    fun recordingReturnsToRecordingsTab() {
        assertEquals("recordings", PlaybackOpenedFrom.LIBRARY.route)
    }

    @Test
    fun catchupMovieReturnsToCatchupTab() {
        assertEquals("catchup", PlaybackReturn.route(ContentType.MOVIE, PlaybackOpenedFrom.CATCHUP))
    }
}
