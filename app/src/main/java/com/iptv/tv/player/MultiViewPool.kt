package com.iptv.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class TileStatus(
    val buffering: Boolean = true,
    val error: String? = null,
)

/**
 * Owns one independent ExoPlayer per multi-view tile. Tiles are deliberately cheap:
 * subtitles are off, buffers are short and only the active tile is audible.
 */
@OptIn(UnstableApi::class)
@Singleton
class MultiViewPool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private class Tile(
        val player: ExoPlayer,
        val selector: DefaultTrackSelector,
        val http: androidx.media3.datasource.DefaultHttpDataSource.Factory,
        var listener: Player.Listener? = null,
        var url: String? = null,
        var retries: Int = 0,
        var maxWidth: Int = Int.MAX_VALUE,
        var maxHeight: Int = Int.MAX_VALUE,
        /** When the tile last reached READY; long stable play earns a fresh retry budget. */
        var lastReadyAt: Long = 0L,
        var lastFailAt: Long = 0L,
        var pendingRetry: Runnable? = null,
        /** When the current media item was handed to the player. */
        var loadStartedAt: Long = 0L,
        /** Last playback position the watchdog saw, and when it last moved. */
        var lastPosition: Long = -1L,
        var lastProgressAt: Long = 0L,
        /** Last buffered position the watchdog saw while buffering, and when it last grew. */
        var lastBuffered: Long = -1L,
        var lastBufferedAt: Long = 0L,
    )

    private val tiles = mutableMapOf<Int, Tile>()

    private val _status = MutableStateFlow<Map<Int, TileStatus>>(emptyMap())
    val status: StateFlow<Map<Int, TileStatus>> = _status.asStateFlow()

    /** Bumped whenever a tile's ExoPlayer is created or released, so PlayerViews rebind. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var activeSlot: Int = 0

    fun playerFor(slot: Int): ExoPlayer = tile(slot).player

    fun play(slot: Int, url: String, maxTiles: Int, headers: Map<String, String> = emptyMap()) {
        val existing = tiles[slot]
        if (existing != null &&
            existing.url == url &&
            existing.player.playbackState != Player.STATE_IDLE &&
            existing.player.playbackState != Player.STATE_ENDED &&
            existing.pendingRetry == null &&
            _status.value[slot]?.error == null
        ) {
            existing.player.volume = if (slot == activeSlot) 1f else 0f
            return
        }
        val tile = tile(slot)
        // A retry scheduled for the previous stream must not load it over this one.
        tile.pendingRetry?.let { mainHandler.removeCallbacks(it) }
        tile.pendingRetry = null
        tile.url = url
        tile.retries = 0
        // Free channels often need a Referer / User-Agent, same as the fullscreen player.
        tile.http.setUserAgent(
            headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: VlcEngine.DEFAULT_UA,
        )
        tile.http.setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        // Tiles are small on screen, so there is no point decoding more pixels than we show.
        val maxHeight = if (maxTiles > 4) 540 else 720
        tile.maxHeight = maxHeight
        tile.maxWidth = maxHeight * 16 / 9
        applySelector(tile)
        updateStatus(slot, TileStatus(buffering = true, error = null))
        tile.player.volume = if (slot == activeSlot) 1f else 0f
        if (!loadTile(tile, url)) updateStatus(slot, TileStatus(buffering = false, error = "Stream unavailable"))
    }

    /**
     * Called when a tile's live stream ends or fails. Return true to take over recovery (a
     * borrowed line whose owner started watching must be handed back, not reconnected).
     */
    var onStreamCut: ((slot: Int) -> Boolean)? = null

    /** Media source creation can throw synchronously; a tile must show an error, not crash the app. */
    private fun loadTile(tile: Tile, url: String): Boolean = try {
        val now = android.os.SystemClock.elapsedRealtime()
        tile.loadStartedAt = now
        tile.lastPosition = -1L
        tile.lastProgressAt = now
        tile.lastBuffered = -1L
        tile.lastBufferedAt = now
        tile.player.setMediaItem(MediaItem.fromUri(url))
        tile.player.prepare()
        tile.player.playWhenReady = true
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Closes the tile's connection and shows it as reconnecting while its owner decides what to
     * do about a cut. The connection must be gone before the panel is asked who is on the line,
     * or the panel counts our own stalled stream as somebody watching.
     */
    fun markReconnecting(slot: Int) {
        val tile = tiles[slot] ?: return
        runCatching { tile.player.stop() }
        updateStatus(slot, TileStatus(buffering = true, error = null))
    }

    /** Reconnects a cut tile on the same URL, with the usual back-off and retry budget. */
    fun reconnect(slot: Int) {
        val tile = tiles[slot] ?: return
        retryOrFail(slot, tile.player)
    }

    /** True while the tile has a stream (playing, buffering or reconnecting) and no final error. */
    fun isStreaming(slot: Int): Boolean {
        val tile = tiles[slot] ?: return false
        return tile.url != null && _status.value[slot]?.error == null
    }

    /** True while the tile is streaming or filling its buffer, with no failure pending. */
    fun isHealthy(slot: Int): Boolean {
        val tile = tiles[slot] ?: return false
        if (tile.url == null || tile.pendingRetry != null || _status.value[slot]?.error != null) return false
        return tile.player.playbackState == Player.STATE_READY || tile.player.playbackState == Player.STATE_BUFFERING
    }

    /** Shows an error on a tile that never got as far as a URL. */
    fun markUnavailable(slot: Int, message: String) {
        updateStatus(slot, TileStatus(buffering = false, error = message))
    }

    fun setActiveSlot(slot: Int) {
        activeSlot = slot
        tiles.forEach { (index, tile) -> tile.player.volume = if (index == slot) 1f else 0f }
    }

    fun activeSlot(): Int = activeSlot

    fun retry(slot: Int, maxTiles: Int) {
        val url = tiles[slot]?.url ?: return
        play(slot, url, maxTiles)
    }

    fun release(slot: Int) {
        tiles.remove(slot)?.let { tile ->
            tile.pendingRetry?.let { mainHandler.removeCallbacks(it) }
            // A PlayerView may still be detaching from this player as we tear it down.
            runCatching { tile.listener?.let { tile.player.removeListener(it) } }
            runCatching { tile.player.stop() }
            runCatching { tile.player.release() }
            _generation.value++
        }
        _status.value = _status.value - slot
    }

    fun releaseAll() {
        tiles.keys.toList().forEach { release(it) }
        _status.value = emptyMap()
    }

    private fun applySelector(tile: Tile) {
        tile.selector.setParameters(
            tile.selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(tile.maxWidth, tile.maxHeight),
        )
        tile.player.playWhenReady = tile.url != null
    }

    private fun tile(slot: Int): Tile = tiles.getOrPut(slot) { createTile(slot).also { startWatchdog() } }

    private var watchdogRunning = false

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * Catches the failure the listener never sees: a stream the server stops feeding without
     * closing it. The tile then sits buffering with nothing arriving, or shows a frozen frame
     * with the clock stopped, and would stay that way for ever. Either is treated like a cut
     * and reconnected. A slow start is not a stall: while the buffered position keeps growing
     * the tile is left alone, however long it takes, because tearing a decoder down and
     * rebuilding it every few seconds is what wedges this stick's video driver.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            for ((slot, tile) in tiles.toMap()) {
                if (tile.url == null || tile.pendingRetry != null || _status.value[slot]?.error != null) continue
                val player = tile.player
                val stalled = when (player.playbackState) {
                    Player.STATE_BUFFERING -> {
                        val buffered = player.bufferedPosition
                        if (buffered != tile.lastBuffered) {
                            tile.lastBuffered = buffered
                            tile.lastBufferedAt = now
                            false
                        } else {
                            now - maxOf(tile.lastBufferedAt, tile.loadStartedAt) > STALL_BUFFERING_MS
                        }
                    }
                    Player.STATE_READY -> {
                        tile.lastBuffered = player.bufferedPosition
                        tile.lastBufferedAt = now
                        val position = player.currentPosition
                        if (position != tile.lastPosition) {
                            tile.lastPosition = position
                            tile.lastProgressAt = now
                            false
                        } else {
                            player.playWhenReady && now - tile.lastProgressAt > STALL_FROZEN_MS
                        }
                    }
                    else -> false
                }
                if (stalled) {
                    android.util.Log.w(TAG, "tile $slot stalled (state=${player.playbackState})")
                    tile.lastProgressAt = now
                    tile.loadStartedAt = now
                    if (onStreamCut?.invoke(slot) != true) retryOrFail(slot, player)
                }
            }
            if (tiles.isNotEmpty()) mainHandler.postDelayed(this, WATCHDOG_MS) else watchdogRunning = false
        }
    }

    private fun createTile(slot: Int): Tile {
        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // Equal min and max keep the loader reading the socket continuously. With 8 s / 25 s the
        // player filled 25 s, then left the connection idle until only 8 s remained; the
        // provider's server treats a connection that stops reading for that long as dead and
        // closes it, so every tile "ended" and reconnected every half minute or so.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 10_000, 1_500, 3_000)
            .build()
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context).setDataSourceFactory(
                    androidx.media3.datasource.DefaultDataSource.Factory(context, http),
                ),
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playWhenReady = true
                volume = 0f
            }
        val tile = Tile(player, selector, http)
        _generation.value++
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) tiles[slot]?.lastReadyAt = android.os.SystemClock.elapsedRealtime()
                updateStatus(
                    slot,
                    TileStatus(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        error = _status.value[slot]?.error,
                    ),
                )
                // A live stream never legitimately ends. The server closing the connection
                // arrives as a clean end of stream, not an error, and would freeze the tile.
                if (playbackState == Player.STATE_ENDED) {
                    android.util.Log.w(TAG, "tile $slot stream ended by server")
                    if (onStreamCut?.invoke(slot) != true) retryOrFail(slot, player)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                val form = player.currentMediaItem?.localConfiguration?.uri?.path?.substringAfterLast('.')
                android.util.Log.w(TAG, "tile $slot error=${error.errorCodeName} http=$http form=$form")
                if (onStreamCut?.invoke(slot) != true) retryOrFail(slot, player)
            }
        }
        tile.listener = listener
        player.addListener(listener)
        return tile
    }

    /**
     * Reconnects a tile whose stream ended or failed. Some panels drop extra `.ts` connections
     * after half a minute, so the first attempt uses the stream's HLS twin, the second the
     * original URL. A tile that had been playing for a while gets a fresh budget: a stream that
     * is cut every few minutes must keep coming back, only one that fails straight away gives up.
     */
    private fun retryOrFail(slot: Int, player: ExoPlayer) {
        val current = tiles[slot] ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        // A fresh budget only when the tile actually played for a while since its last failure;
        // otherwise a dead stream would be retried for ever.
        if (current.lastReadyAt > current.lastFailAt && now - current.lastReadyAt > STABLE_PLAY_MS) current.retries = 0
        current.lastFailAt = now
        if (current.retries >= MAX_RETRIES) {
            updateStatus(slot, TileStatus(buffering = false, error = "Stream unavailable"))
            return
        }
        current.retries++
        // The plain stream first: reconnecting it works on every panel. The HLS twin is the last
        // resort, since some panels answer it with 405.
        val fallback = current.url?.let { StreamUrls.hlsVariant(it) }
        val next = (if (current.retries == MAX_RETRIES && fallback != null) fallback else current.url) ?: return
        // Back off between attempts: a panel that just cut the stream needs a moment to free
        // the slot, and a burst of reconnects can get the whole address blocked.
        val delayMs = RETRY_DELAYS_MS[(current.retries - 1).coerceIn(RETRY_DELAYS_MS.indices)]
        android.util.Log.w(TAG, "tile $slot reconnect attempt ${current.retries} in ${delayMs / 1000}s")
        current.pendingRetry?.let { mainHandler.removeCallbacks(it) }
        val scheduledFor = current.url
        val work = Runnable {
            current.pendingRetry = null
            // The slot may have been released or given a new stream while we waited.
            if (tiles[slot] !== current || current.url != scheduledFor) return@Runnable
            if (!loadTile(current, next)) updateStatus(slot, TileStatus(buffering = false, error = "Stream unavailable"))
        }
        current.pendingRetry = work
        mainHandler.postDelayed(work, delayMs)
    }

    private fun updateStatus(slot: Int, status: TileStatus) {
        _status.value = _status.value + (slot to status)
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val STABLE_PLAY_MS = 20_000L
        // A panel that just cut a stream accepts a new one straight away; only repeated
        // failures back off.
        val RETRY_DELAYS_MS = longArrayOf(800L, 6_000L, 20_000L)
        const val WATCHDOG_MS = 5_000L
        /**
         * Buffering this long with nothing new arriving: the server has stopped sending. Longer
         * than the HTTP read timeout, so a dead socket surfaces as a player error first.
         */
        const val STALL_BUFFERING_MS = 25_000L
        /** READY and meant to be playing, but the position has not moved for this long. */
        const val STALL_FROZEN_MS = 10_000L
        const val TAG = "IptvTv"
    }
}
