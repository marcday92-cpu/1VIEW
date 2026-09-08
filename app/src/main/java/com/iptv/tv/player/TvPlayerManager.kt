package com.iptv.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.iptv.tv.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StreamStats(
    val resolution: String = "—",
    val fps: String = "—",
    val bitrate: String = "—",
    val videoCodec: String = "—",
    val audioCodec: String = "—",
    val bufferCount: Int = 0,
    val latency: String = "—",
    val engine: String = "—",
)

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** Why playback stopped, so the UI can offer the right recovery action. */
    val errorKind: PlayerErrorKind? = null,
    val currentUrl: String? = null,
    val engine: PlayerEngine = PlayerEngine.EXOPLAYER,
    val textTracks: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val selectedTextId: String? = null,
    val selectedAudioId: String? = null,
    /** False when the user has explicitly turned captions off. */
    val subtitlesEnabled: Boolean = true,
    /** A sidecar file is loaded, so timing adjustment is possible. */
    val subtitleDelaySupported: Boolean = false,
    val subtitleDelayMs: Long = 0,
    val audioDelayMs: Long = 0,
    val stats: StreamStats = StreamStats(),
    val playbackEnded: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
)

data class MediaTrack(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
)

enum class AspectMode { FIT, FILL, ZOOM }

enum class PlayerEngine { EXOPLAYER, VLC }

enum class PlayerErrorKind {
    /** No validated internet route; playback restarts by itself when one returns. */
    OFFLINE,
    /** The server refused or could not deliver the stream (HTTP error, timeout, DNS). */
    UNAVAILABLE,
    /** Neither engine can decode this stream on this device. */
    UNSUPPORTED,
    /** Anything else. */
    UNKNOWN,
}

@OptIn(UnstableApi::class)
@Singleton
class TvPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredAudioLanguage("en")
            .setPreferredTextLanguage("en")
            // Streams frequently ship subtitle tracks with no language tag at all;
            // without this they are never auto-selected.
            .setSelectUndeterminedTextLanguage(true)
            .build()
    }

    private val rmsProcessor = RmsAudioProcessor()

    val audioLevel: Float get() = rmsProcessor.level

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(rmsProcessor))
                .build()
        }
    }
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    // Media3 defaults are 50s/50s/2.5s/5s. A 15s min buffer was starving live
    // IPTV on Fire TV Wi-Fi jitter; keep a deeper tank and wait longer after a stall.
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 50_000, 2_500, 8_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(
            // DefaultDataSource routes file://, content:// and asset URIs (recordings, sidecar
            // subtitles) to local readers and everything else to the HTTP source; the HTTP
            // factory alone throws a ClassCastException on a file URI.
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory),
            ),
        )
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            playWhenReady = true
        }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var retryCount = 0
    private var lastUrl: String? = null
    private var lastStartMs: Long = 0
    private var altFallbackUrl: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var engine: PlayerEngine = PlayerEngine.EXOPLAYER
    /** The engine the caller asked for; [retry] goes back to it. */
    private var requestedVlc: Boolean = false
    /** Set once Media3 has handed this URL to VLC, so a VLC failure does not bounce back. */
    private var vlcFallbackUsed: Boolean = false
    /** Media3 was tried for this request (so a later VLC failure means both engines failed). */
    private var triedExo: Boolean = false
    /** True while we are parked on an "offline" error waiting for the network. */
    private var awaitingNetwork: Boolean = false
    private val vlc = VlcEngine(context)
    private var subtitleDelayMs: Long = 0
    private var currentSubtitleFile: File? = null
    /** The pristine download, so repeated delay changes don't compound. */
    private var subtitleSourceFile: File? = null
    private var subtitlesEnabled: Boolean = true
    private var preferredTextLanguage: String = "en"
    private var pendingSidecarSelect: Boolean = false
    private var bufferCount: Int = 0
    private var lastBitrateBps: Long = 0
    private var wasReady: Boolean = false
    private var playbackEnded: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    var aspectMode: AspectMode = AspectMode.FIT
        private set

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING && wasReady) bufferCount++
            // A stream that reached READY has recovered: the next glitch starts a fresh retry budget.
            if (playbackState == Player.STATE_READY) retryCount = 0
            wasReady = playbackState == Player.STATE_READY
            playbackEnded = playbackState == Player.STATE_ENDED
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (pendingSidecarSelect) selectSidecarTrack(tracks)
            publish(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = lastUrl ?: return
            if (isDecoderFailure(error) && !vlcFallbackUsed) {
                vlcFallbackUsed = true
                startVlc(current, lastHeaders, vodResumePosition())
                return
            }
            if (!networkMonitor.isOnline) {
                parkOffline()
                return
            }
            val hlsFallback = StreamUrls.hlsVariant(current)
            if (retryCount < MAX_RETRIES) {
                retryCount++
                val next = if (retryCount == 2 && hlsFallback != null) hlsFallback else current
                val delayMs = if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    250L
                } else {
                    retryDelayMs(retryCount)
                }
                scheduleRetry(delayMs) { retryWith(next) }
                return
            }
            val alt = altFallbackUrl?.takeIf { it != current }
            when {
                alt != null -> {
                    altFallbackUrl = null
                    retryCount = 0
                    scheduleRetry(retryDelayMs(1)) { retryWith(alt) }
                }
                // HTTP errors, DNS and timeouts will not get better in another engine.
                isSourceFailure(error) -> fail(PlayerErrorKind.UNAVAILABLE, sourceFailureMessage(error))
                !vlcFallbackUsed -> {
                    vlcFallbackUsed = true
                    val resumeAt = vodResumePosition()
                    scheduleRetry(retryDelayMs(1)) { startVlc(current, lastHeaders, resumeAt) }
                }
                else -> fail(PlayerErrorKind.UNKNOWN, "Playback failed.")
            }
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                lastBitrateBps = bitrateEstimate
            }
        })
        mainScope.launch {
            networkMonitor.status.collect { status ->
                if (status.online && awaitingNetwork) {
                    awaitingNetwork = false
                    retry()
                }
            }
        }
        vlc.onEvent = { type ->
            // Events from a VLC player we already stopped (zap to a Media3 channel) are stale.
            if (engine == PlayerEngine.VLC) when (type) {
                MediaPlayer.Event.EncounteredError -> {
                    val current = lastUrl
                    when {
                        current == null -> Unit
                        !networkMonitor.isOnline -> parkOffline()
                        retryCount < MAX_RETRIES -> {
                            retryCount++
                            scheduleRetry(retryDelayMs(retryCount)) { startVlc(current, lastHeaders, 0) }
                        }
                        else -> {
                            val alt = altFallbackUrl?.takeIf { it != current }
                            if (alt != null) {
                                altFallbackUrl = null
                                retryCount = 0
                                scheduleRetry(retryDelayMs(1)) { startVlc(alt, lastHeaders, 0) }
                            } else if (triedExo) {
                                fail(PlayerErrorKind.UNSUPPORTED, "Neither player could play this stream on this device.")
                            } else {
                                fail(PlayerErrorKind.UNAVAILABLE, "This stream isn't available right now.")
                            }
                        }
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    playbackEnded = true
                    publishVlc()
                }
                MediaPlayer.Event.Playing,
                MediaPlayer.Event.Paused,
                MediaPlayer.Event.Buffering,
                MediaPlayer.Event.TimeChanged,
                MediaPlayer.Event.LengthChanged,
                -> publishVlc()
            }
        }
    }

    private fun parkOffline() {
        cancelPendingRetry()
        awaitingNetwork = true
        _state.value = _state.value.copy(
            isBuffering = false,
            isPlaying = false,
            error = "No internet connection. Playback restarts when the network is back.",
            errorKind = PlayerErrorKind.OFFLINE,
        )
    }

    private fun fail(kind: PlayerErrorKind, message: String) {
        cancelPendingRetry()
        _state.value = _state.value.copy(
            isBuffering = false,
            isPlaying = false,
            error = message,
            errorKind = kind,
            stats = if (engine == PlayerEngine.VLC) vlc.diagnostics(bufferCount) else collectExoStats(),
        )
    }

    /** User pressed Retry, or the network came back: start the current stream again from scratch. */
    fun retry() {
        val url = lastUrl ?: return
        val resumeAt = if (durationMs > 0) positionMs.coerceAtLeast(lastStartMs) else lastStartMs
        playUrl(url, resumeAt, altFallbackUrl, lastHeaders, useVlc = requestedVlc)
    }

    /** "Try the other player": same stream, opposite engine. */
    fun switchEngine() {
        val url = lastUrl ?: return
        val resumeAt = if (durationMs > 0) positionMs else lastStartMs
        playUrl(url, resumeAt, altFallbackUrl, lastHeaders, useVlc = engine != PlayerEngine.VLC)
    }

    /** Where a VOD stream should restart after an engine change or retry; 0 for live. */
    private fun vodResumePosition(): Long {
        val known = if (engine == PlayerEngine.VLC) vlc.lengthMs > 0 else
            player.duration != C.TIME_UNSET && player.duration > 0 && !player.isCurrentMediaItemLive
        return if (known) positionMs.coerceAtLeast(0) else lastStartMs.coerceAtLeast(0)
    }

    private fun retryWith(next: String) {
        lastUrl = next
        val live = player.isCurrentMediaItemLive
        val timelineKnown = player.duration != C.TIME_UNSET && player.duration > 0
        val resumeAt = when {
            live -> C.TIME_UNSET
            timelineKnown -> player.currentPosition.coerceAtLeast(0)
            else -> lastStartMs
        }
        if (resumeAt != C.TIME_UNSET) lastStartMs = resumeAt
        val retryItem = player.currentMediaItem?.buildUpon()?.setUri(next)?.build()
            ?: MediaItem.fromUri(next)
        loadIntoExo(retryItem, resumeAt)
    }

    /**
     * Hands a media item to ExoPlayer. Building the media source can throw synchronously (a
     * container the build cannot handle, a malformed URI); that must surface as a player error,
     * never as an uncaught exception on the main thread.
     */
    private fun loadIntoExo(item: MediaItem, startPositionMs: Long) {
        try {
            player.setMediaItem(item, startPositionMs)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            fail(PlayerErrorKind.UNSUPPORTED, "This stream format is not supported by the player.")
        }
    }

    private fun scheduleRetry(delayMs: Long, action: () -> Unit) {
        cancelPendingRetry()
        _state.value = _state.value.copy(isBuffering = true, error = null, errorKind = null)
        val work = Runnable {
            retryRunnable = null
            action()
        }
        retryRunnable = work
        mainHandler.postDelayed(work, delayMs)
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun retryDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 750L
        2 -> 2_000L
        else -> 4_000L
    }

    private fun isDecoderFailure(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

    private fun isSourceFailure(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        -> true
        else -> false
    }

    private fun sourceFailureMessage(error: PlaybackException): String {
        val cause = error.cause
        val status = (cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            status != null -> "The server refused this stream (HTTP $status)."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "The server took too long to answer."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Could not connect to the stream server."
            else -> "This stream isn't available right now."
        }
    }

    fun playUrl(
        url: String,
        startPositionMs: Long = 0,
        fallbackUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        useVlc: Boolean = false,
    ) {
        cancelPendingRetry()
        retryCount = 0
        awaitingNetwork = false
        requestedVlc = useVlc
        vlcFallbackUsed = useVlc
        triedExo = !useVlc
        lastUrl = url
        lastStartMs = startPositionMs
        lastHeaders = headers
        altFallbackUrl = fallbackUrl?.takeIf { it.isNotBlank() && it != url }
        currentSubtitleFile = null
        subtitleSourceFile = null
        subtitleDelayMs = 0
        subtitlesEnabled = true
        bufferCount = 0
        wasReady = false
        playbackEnded = false
        lastBitrateBps = 0
        if (useVlc) {
            startVlc(url, headers, startPositionMs)
            return
        }
        engine = PlayerEngine.EXOPLAYER
        vlc.stop()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        httpDataSourceFactory.setUserAgent(userAgent ?: VlcEngine.DEFAULT_UA)
        httpDataSourceFactory.setDefaultRequestProperties(
            headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) },
        )
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(preferredTextLanguage)
                .setSelectUndeterminedTextLanguage(true),
        )
        _state.value = PlayerState(currentUrl = url, engine = PlayerEngine.EXOPLAYER, aspectMode = aspectMode)
        val builder = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        loadIntoExo(builder.build(), startPositionMs)
    }

    fun attachVlcLayout(layout: VLCVideoLayout) {
        vlc.attach(layout)
    }

    fun detachVlcLayout(layout: VLCVideoLayout) {
        vlc.detach(layout)
    }

    private fun startVlc(url: String, headers: Map<String, String>, startPositionMs: Long) {
        engine = PlayerEngine.VLC
        playbackEnded = false
        lastUrl = url
        lastStartMs = startPositionMs
        player.stop()
        _state.value = PlayerState(
            currentUrl = url,
            isBuffering = true,
            engine = PlayerEngine.VLC,
        )
        vlc.play(url, headers, startPositionMs, currentSubtitleFile)
    }

    private fun publishVlc() {
        if (engine != PlayerEngine.VLC) return
        if (_state.value.error != null && !vlc.isPlaying) return
        _state.value = _state.value.copy(
            isPlaying = vlc.isPlaying,
            isBuffering = !vlc.isPlaying,
            positionMs = vlc.timeMs,
            durationMs = vlc.lengthMs,
            currentUrl = lastUrl,
            engine = PlayerEngine.VLC,
            error = null,
            errorKind = null,
            subtitlesEnabled = subtitlesEnabled,
            subtitleDelaySupported = subtitleSourceFile != null,
            subtitleDelayMs = subtitleDelayMs,
            stats = vlc.diagnostics(bufferCount),
            playbackEnded = playbackEnded,
        )
    }

    val positionMs: Long
        get() = if (engine == PlayerEngine.VLC) vlc.timeMs else player.currentPosition.coerceAtLeast(0)

    val durationMs: Long
        get() = if (engine == PlayerEngine.VLC) vlc.lengthMs else player.duration.coerceAtLeast(0)

    fun playWithSubtitle(url: String, subtitleFile: File, mimeType: String = guessMime(subtitleFile)) {
        subtitleSourceFile = subtitleFile
        subtitleDelayMs = 0
        // Reload whatever is actually playing: after a fallback the session URL is the dead one.
        loadSubtitle(lastUrl ?: url, subtitleFile, mimeType)
    }

    private fun loadSubtitle(url: String, subtitleFile: File, mimeType: String = guessMime(subtitleFile)) {
        retryCount = 0
        lastUrl = url
        currentSubtitleFile = subtitleFile
        subtitlesEnabled = true
        if (engine == PlayerEngine.VLC) {
            pendingSidecarSelect = false
            val attached = vlc.setSubtitleFile(subtitleFile)
            if (!attached) {
                startVlc(url, lastHeaders, positionMs.coerceAtLeast(0))
            }
            vlc.setSpuDelay(0)
            publishVlc()
            return
        }
        // A newly loaded sidecar must not be blocked by a previous "off",
        // and unlabeled embedded tracks must not outrank the file we just attached.
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(preferredTextLanguage)
                .setSelectUndeterminedTextLanguage(false),
        )
        pendingSidecarSelect = true
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
            .setMimeType(mimeType)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setLabel(subtitleFile.name)
            .build()
        val resumeAt = player.currentPosition.coerceAtLeast(0)
        lastStartMs = resumeAt
        loadIntoExo(
            MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(listOf(subtitleConfig))
                .build(),
            resumeAt,
        )
    }

    private fun selectSidecarTrack(tracks: Tracks) {
        val fileName = currentSubtitleFile?.name
        val group = tracks.groups.firstOrNull { group ->
            SidecarText.isTextGroup(group.type) &&
                (0 until group.length).any { i ->
                    SidecarText.isSidecarFormat(group.getTrackFormat(i), fileName)
                }
        } ?: return
        pendingSidecarSelect = false
        subtitlesEnabled = true
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)),
        )
    }


    fun pause() {
        if (engine == PlayerEngine.VLC) vlc.pause() else player.pause()
    }
    fun resume() {
        if (engine == PlayerEngine.VLC) vlc.resume() else player.play()
    }
    fun stop() {
        cancelPendingRetry()
        awaitingNetwork = false
        vlc.stop()
        player.stop()
        engine = PlayerEngine.EXOPLAYER
        lastUrl = null
        _state.value = PlayerState()
    }
    fun seekTo(positionMs: Long) {
        lastStartMs = positionMs.coerceAtLeast(0)
        if (engine == PlayerEngine.VLC) vlc.seekTo(positionMs) else player.seekTo(positionMs)
    }

    fun togglePlayPause() {
        if (engine == PlayerEngine.VLC) {
            if (vlc.isPlaying) vlc.pause() else vlc.resume()
            publishVlc()
        } else {
            if (player.isPlaying) player.pause() else player.play()
            publish()
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = durationMs
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val next = (positionMs + deltaMs).coerceIn(0L, max)
        seekTo(next)
        lastStartMs = next
        if (engine == PlayerEngine.VLC) publishVlc() else publish()
    }

    fun tickPosition() {
        if (lastUrl == null) return
        if (_state.value.error != null) return
        if (engine == PlayerEngine.VLC) {
            publishVlc()
            return
        }
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            engine = PlayerEngine.EXOPLAYER,
            playbackEnded = player.playbackState == Player.STATE_ENDED,
        )
    }

    /**
     * Timing can only be shifted for sidecar subtitle files; Media3 has no offset for
     * captions muxed into the stream. Returns false so the UI can say so.
     */
    fun setSubtitleDelay(delayMs: Long): Boolean {
        if (subtitleSourceFile == null) return false
        subtitleDelayMs = delayMs
        if (engine == PlayerEngine.VLC) {
            vlc.setSpuDelay(delayMs)
            publishVlc()
            return true
        }
        // Delay is applied by the Compose caption overlay (cue time + offset).
        // Reloading a shifted SRT used to drop the selected sidecar track — the
        // UI then showed −1650 ms with nothing on screen.
        if (engine == PlayerEngine.EXOPLAYER) publish()
        return true
    }

    fun hasSidecarSubtitle(): Boolean = subtitleSourceFile != null

    fun getSubtitleDelay(): Long = subtitleDelayMs

    /** Passing null turns captions off entirely. */
    fun selectTextTrack(track: MediaTrack?) {
        if (engine == PlayerEngine.VLC) {
            if (track == null) {
                subtitlesEnabled = false
                vlc.setSpuTrack(-1)
            } else {
                subtitlesEnabled = true
                vlc.setSpuTrack(track.trackIndex)
            }
            publishVlc()
            return
        }
        val builder = trackSelector.parameters.buildUpon()
        if (track == null) {
            subtitlesEnabled = false
            pendingSidecarSelect = false
            // A stale override outranks the language preference, so clear it too.
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
            subtitlesEnabled = true
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
        }
        trackSelector.setParameters(builder)
        publish()
    }

    /** Re-enables captions using the preferred language rather than a specific track. */
    fun enableSubtitles() {
        subtitlesEnabled = true
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false),
        )
        if (currentSubtitleFile != null) {
            pendingSidecarSelect = true
            selectSidecarTrack(player.currentTracks)
        }
        if (engine == PlayerEngine.VLC) publishVlc() else publish()
    }

    fun setPreferredAudioLanguage(language: String?) {
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setPreferredAudioLanguage(language),
        )
    }


    fun setPreferredTextLanguage(language: String?) {
        preferredTextLanguage = language?.takeIf { it.isNotBlank() } ?: "en"
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setPreferredTextLanguage(language),
        )
        if (currentSubtitleFile != null && subtitlesEnabled) {
            pendingSidecarSelect = true
            selectSidecarTrack(player.currentTracks)
        }
    }

    fun selectAudioTrack(track: MediaTrack) {
        val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
        trackSelector.setParameters(
            trackSelector.parameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)),
        )
    }

    fun setAudioDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-2_000, 2_000)
        if (engine == PlayerEngine.VLC) vlc.setAudioDelay(clamped)
        _state.value = _state.value.copy(audioDelayMs = clamped)
    }

    /** Media3 draws into a TextureView, so the PlayerView's resize mode (set by the UI from [PlayerState.aspectMode]) does the work. */
    fun setAspect(mode: AspectMode) {
        aspectMode = mode
        if (engine == PlayerEngine.VLC) vlc.setScale(mode)
        _state.value = _state.value.copy(aspectMode = mode)
    }


    private fun publish(tracks: Tracks = player.currentTracks) {
        if (engine == PlayerEngine.VLC) {
            publishVlc()
            return
        }
        if (_state.value.error != null && !player.isPlaying) return
        val text = mutableListOf<MediaTrack>()
        val audio = mutableListOf<MediaTrack>()
        tracks.groups.forEachIndexed { gIndex, group ->
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                val item = MediaTrack(
                    id = "$gIndex-$t",
                    groupIndex = gIndex,
                    trackIndex = t,
                    label = format.label ?: format.language ?: "Track ${t + 1}",
                    language = format.language,
                    selected = group.isTrackSelected(t),
                    forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    hearingImpaired = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 ||
                        (format.label?.contains("SDH", ignoreCase = true) == true) ||
                        (format.label?.contains("hearing", ignoreCase = true) == true) ||
                        (format.label?.contains(HEARING_IMPAIRED_LABEL) == true),
                )
                when (group.type) {
                    C.TRACK_TYPE_TEXT -> text += item
                    C.TRACK_TYPE_AUDIO -> audio += item
                }
            }
        }
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            currentUrl = lastUrl,
            engine = PlayerEngine.EXOPLAYER,
            textTracks = text,
            audioTracks = audio,
            selectedTextId = text.find { it.selected }?.id,
            selectedAudioId = audio.find { it.selected }?.id,
            subtitlesEnabled = subtitlesEnabled,
            subtitleDelaySupported = subtitleSourceFile != null,
            subtitleDelayMs = subtitleDelayMs,
            stats = collectExoStats(),
            playbackEnded = playbackEnded,
        )
    }

    private fun collectExoStats(): StreamStats {
        val video = player.videoFormat
        val audio = player.audioFormat
        val width = video?.width ?: 0
        val height = video?.height ?: 0
        val fps = video?.frameRate?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "—"
        val bitrate = when {
            lastBitrateBps > 0 -> "${lastBitrateBps / 1000} kbps"
            (video?.bitrate ?: Format.NO_VALUE) > 0 -> "${(video?.bitrate ?: 0) / 1000} kbps"
            else -> "—"
        }
        val liveOffset = runCatching { player.currentLiveOffset }.getOrNull()
            ?.takeIf { it != C.TIME_UNSET }
            ?.let { "${it / 1000}s" }
            ?: "—"
        return StreamStats(
            resolution = if (width > 0 && height > 0) "${width}×${height}" else "—",
            fps = fps,
            bitrate = bitrate,
            videoCodec = video?.codecs ?: video?.sampleMimeType ?: "—",
            audioCodec = audio?.codecs ?: audio?.sampleMimeType ?: "—",
            bufferCount = bufferCount,
            latency = liveOffset,
            engine = "Media3",
        )
    }

    companion object {
        private const val MAX_RETRIES = 3
        private val HEARING_IMPAIRED_LABEL = Regex("""\bHI\b""", RegexOption.IGNORE_CASE)

        fun guessMime(file: File): String = when (file.extension.lowercase()) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

    }
}
