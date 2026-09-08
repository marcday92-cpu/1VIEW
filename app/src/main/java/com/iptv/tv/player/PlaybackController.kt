package com.iptv.tv.player

import com.iptv.tv.data.repository.LiveWatchTracker
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ContentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class PlaybackOpenedFrom {
    HOME, LIVE, CATCHUP, MOVIES, SERIES, SEARCH, GUIDE, LIBRARY, MULTI;

    val route: String
        get() = when (this) {
            HOME -> "home"
            LIVE -> "live"
            CATCHUP -> "catchup"
            MOVIES -> "movies"
            SERIES -> "series"
            SEARCH -> "search"
            GUIDE -> "guide"
            LIBRARY -> "recordings"
            MULTI -> "multi"
        }
}

object PlaybackReturn {
    fun route(type: ContentType, openedFrom: PlaybackOpenedFrom?): String =
        when (type) {
            ContentType.LIVE -> when (openedFrom) {
                PlaybackOpenedFrom.GUIDE -> "guide"
                PlaybackOpenedFrom.SEARCH -> "search"
                PlaybackOpenedFrom.MULTI -> "multi"
                else -> "live"
            }
            ContentType.MOVIE, ContentType.EPISODE -> openedFrom?.route ?: "home"
        }
}

data class PlaybackSession(
    val type: ContentType,
    val streamId: Int,
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val posterUrl: String? = null,
    val contentKey: String,
    val startPositionMs: Long = 0,
    val channel: Channel? = null,
    val playlist: List<Channel> = emptyList(),
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val filename: String? = null,
    val seriesId: Int? = null,
    val containerExtension: String? = null,
    val fallbackUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val openedFrom: PlaybackOpenedFrom? = null,
    val liveRailId: String? = null,
    val isFree: Boolean = false,
) {
    val returnRoute: String get() = PlaybackReturn.route(type, openedFrom)
}

@Singleton
class PlaybackController @Inject constructor(
    val playerManager: TvPlayerManager,
    private val liveWatchTracker: LiveWatchTracker,
    private val profileRepository: ProfileRepository,
    private val iptvRepository: IptvRepository,
    private val preferences: com.iptv.tv.data.preferences.AppPreferences,
) {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val session: StateFlow<PlaybackSession?> = _session.asStateFlow()
    private val _lastChannel = MutableStateFlow<Channel?>(null)
    val lastChannel: StateFlow<Channel?> = _lastChannel.asStateFlow()
    private val _liveBrowse = MutableStateFlow(false)
    val liveBrowse: StateFlow<Boolean> = _liveBrowse.asStateFlow()

    @Volatile private var playerMode: String = "AUTO"
    @Volatile private var streamOverrides: Map<Int, String> = emptyMap()
    /** Playback was stopped because the activity left the screen; restart it on return. */
    private var suspendedByBackground: Boolean = false

    init {
        persistenceScope.launch {
            preferences.playerPreference.collect { playerMode = it }
        }
        persistenceScope.launch {
            preferences.streamEngineOverrides.collect { streamOverrides = it }
        }
    }

    fun play(session: PlaybackSession) {
        persistCurrentSession()
        if (session.type != ContentType.LIVE) {
            liveWatchTracker.onLiveStopped()
            _liveBrowse.value = false
        }
        rememberLastLive(session)
        _session.value = session
        playerManager.playUrl(
            session.url,
            session.startPositionMs,
            session.fallbackUrl,
            session.headers,
            useVlc = shouldUseVlc(session),
        )
    }

    fun playLive(
        channel: Channel,
        url: String,
        playlist: List<Channel>,
        browse: Boolean? = null,
        openedFrom: PlaybackOpenedFrom? = null,
        liveRailId: String? = null,
    ) {
        when {
            browse != null -> _liveBrowse.value = browse
            _session.value?.type != ContentType.LIVE -> _liveBrowse.value = false
        }
        val previous = _session.value
        play(
            PlaybackSession(
                type = ContentType.LIVE,
                streamId = channel.streamId,
                title = channel.name,
                url = url,
                posterUrl = channel.logoUrl,
                contentKey = "live_${channel.streamId}",
                channel = channel,
                playlist = playlist,
                fallbackUrl = channel.fallbackUrl,
                headers = playbackHeaders(channel, url),
                openedFrom = openedFrom ?: previous?.openedFrom ?: PlaybackOpenedFrom.LIVE,
                liveRailId = liveRailId ?: previous?.liveRailId,
            ),
        )
        liveWatchTracker.onLiveStarted(channel)
    }

    fun zap(delta: Int) {
        val current = _session.value ?: return
        if (current.playlist.isEmpty()) return
        val index = current.playlist.indexOfFirst { it.streamId == current.streamId }
        if (index < 0) return
        val next = current.playlist[(index + delta).mod(current.playlist.size)]
        val url = runCatching { iptvRepository.livePlaybackUrl(next) }.getOrElse {
            current.url.replace("/${current.streamId}.", "/${next.streamId}.")
        }
        playLive(next, url, current.playlist)
    }

    fun zapToLast() {
        val previous = _lastChannel.value ?: return
        val current = _session.value
        val playlist = current?.playlist?.takeIf { it.isNotEmpty() } ?: listOf(previous)
        val url = runCatching { iptvRepository.livePlaybackUrl(previous) }.getOrNull() ?: return
        playLive(previous, url, playlist)
    }

    fun playFallback() {
        val current = _session.value ?: return
        val alt = current.fallbackUrl?.takeIf { it.isNotBlank() && it != current.url } ?: return
        play(current.copy(url = alt, fallbackUrl = current.url))
    }

    private fun rememberLastLive(next: PlaybackSession) {
        val current = _session.value ?: return
        if (current.type != ContentType.LIVE || next.type != ContentType.LIVE) return
        if (current.streamId == next.streamId) return
        _lastChannel.value = current.channel
    }

    private fun shouldUseVlc(session: PlaybackSession): Boolean {
        val streamId = session.channel?.streamId ?: session.streamId
        when (streamOverrides[streamId]?.uppercase()) {
            "VLC" -> return true
            "MEDIA3" -> return false
        }
        return when (playerMode.uppercase()) {
            "VLC" -> true
            "MEDIA3" -> false
            else -> session.isFree || session.channel?.isFree == true ||
                session.url.contains("archive.org", ignoreCase = true)
        }
    }

    /**
     * Fire TV Home / another app in front: nothing must keep decoding or counting watch
     * time. Live streams are stopped outright (a paused live edge is useless); VOD pauses.
     */
    fun onAppBackgrounded() {
        val current = _session.value ?: return
        persistCurrentSession()
        liveWatchTracker.onLiveStopped()
        suspendedByBackground = true
        if (current.type == ContentType.LIVE) playerManager.stop() else playerManager.pause()
    }

    fun onAppForegrounded() {
        if (!suspendedByBackground) return
        suspendedByBackground = false
        val current = _session.value ?: return
        if (current.type == ContentType.LIVE) {
            playerManager.playUrl(
                current.url,
                0,
                current.fallbackUrl,
                current.headers,
                useVlc = shouldUseVlc(current),
            )
            current.channel?.let { liveWatchTracker.onLiveStarted(it) }
        } else {
            playerManager.resume()
        }
    }

    fun setLiveBrowse(browse: Boolean) {
        if (_session.value?.type == ContentType.LIVE) _liveBrowse.value = browse
    }

    fun close() {
        persistCurrentSession()
        liveWatchTracker.onLiveStopped()
        suspendedByBackground = false
        playerManager.stop()
        _liveBrowse.value = false
        _session.value = null
    }

    private fun persistCurrentSession() {
        val current = _session.value ?: return
        if (current.type == ContentType.LIVE) return
        val position = playerManager.positionMs
        val duration = playerManager.durationMs
        persistenceScope.launch {
            runCatching {
                profileRepository.saveResume(
                    profileRepository.getActiveProfileId(),
                    current.contentKey,
                    current.type,
                    position,
                    duration,
                    title = listOfNotNull(current.title, current.subtitle).joinToString(" · "),
                    posterUrl = current.posterUrl,
                    streamId = current.streamId,
                    seasonNum = current.season,
                    episodeNum = current.episode,
                    year = current.year,
                    seriesId = current.seriesId,
                    filename = current.filename,
                    containerExtension = current.containerExtension,
                )
            }
        }
    }

    companion object {
        fun playbackHeaders(channel: Channel, url: String): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            val userAgent = channel.userAgent?.takeIf { it.isNotBlank() } ?: VlcEngine.DEFAULT_UA
            headers["User-Agent"] = userAgent
            headers["Accept"] = "*/*"
            val referer = channel.referrer?.takeIf { it.isNotBlank() }
            if (referer != null) {
                headers["Referer"] = referer
                originOf(referer)?.let { headers["Origin"] = it }
            } else {
                originOf(url)?.let { headers["Origin"] = it }
            }
            return headers
        }

        private fun originOf(url: String): String? {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = uri.port
            return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
        }
    }
}
