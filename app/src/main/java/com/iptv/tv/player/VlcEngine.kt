package com.iptv.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

class VlcEngine(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lib: LibVLC by lazy {
        LibVLC(
            appContext,
            arrayListOf(
                "--aout=opensles",
                "--audio-time-stretch",
                "--network-caching=4000",
                "--live-caching=4000",
                "--http-reconnect",
                "--no-osd",
            ),
        )
    }
    private var player: MediaPlayer? = null
    private var attachedLayout: VLCVideoLayout? = null
    var onEvent: ((Int) -> Unit)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val timeMs: Long get() = player?.time?.coerceAtLeast(0) ?: 0
    val lengthMs: Long get() = player?.length?.coerceAtLeast(0) ?: 0

    fun attach(layout: VLCVideoLayout) {
        if (attachedLayout === layout) return
        detach()
        attachedLayout = layout
        ensurePlayer().attachViews(layout, null, false, false)
    }

    fun detach() {
        runCatching { player?.detachViews() }
        attachedLayout = null
    }

    /**
     * Compose swaps the Live preview and the fullscreen surface in one frame: the new
     * layout attaches before the old node releases. Only detach if [layout] is still ours.
     */
    fun detach(layout: VLCVideoLayout) {
        if (attachedLayout !== layout) return
        detach()
    }

    fun play(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        subtitleFile: java.io.File? = null,
    ) {
        val mediaPlayer = ensurePlayer()
        mediaPlayer.stop()
        val media = Media(lib, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        val userAgent = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: DEFAULT_UA
        media.addOption(":http-user-agent=$userAgent")
        headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { media.addOption(":http-referrer=$it") }
        media.addOption(":network-caching=4000")
        // libVLC ignores setTime() before the input is running; start-time is honoured.
        if (startPositionMs > 0) media.addOption(":start-time=${startPositionMs / 1000.0}")
        subtitleFile?.takeIf { it.exists() }?.let { file ->
            media.addOption(":sub-file=${file.absolutePath}")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    fun setSubtitleFile(file: java.io.File): Boolean {
        if (!file.exists()) return false
        val mediaPlayer = player ?: return false
        return runCatching {
            mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
        }.getOrDefault(false)
    }

    fun setSpuDelay(delayMs: Long): Boolean =
        runCatching { player?.spuDelay = delayMs * 1000L; true }.getOrDefault(false)

    fun setAudioDelay(delayMs: Long): Boolean =
        runCatching { player?.audioDelay = delayMs * 1000L; true }.getOrDefault(false)

    fun setSpuTrack(trackId: Int): Boolean =
        runCatching { player?.spuTrack = trackId; true }.getOrDefault(false)

    fun diagnostics(bufferCount: Int): StreamStats {
        val video = player?.currentVideoTrack
        val audioId = player?.audioTrack ?: -1
        val audio = player?.media?.let { media ->
            try {
                (0 until media.trackCount)
                    .mapNotNull { i -> media.getTrack(i) as? IMedia.AudioTrack }
                    .firstOrNull { it.id == audioId }
            } finally {
                // MediaPlayer.getMedia() retains; release our reference every time.
                media.release()
            }
        }
        val fps = video?.let {
            if (it.frameRateDen > 0) "%.0f".format(it.frameRateNum.toFloat() / it.frameRateDen) else "—"
        } ?: "—"
        return StreamStats(
            resolution = if (video != null && video.width > 0) "${video.width}×${video.height}" else "—",
            fps = fps,
            bitrate = "—",
            videoCodec = video?.codec ?: "—",
            audioCodec = audio?.codec ?: "—",
            bufferCount = bufferCount,
            latency = "—",
            engine = "VLC",
        )
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stop() {
        runCatching { player?.stop() }
    }

    fun seekTo(positionMs: Long) {
        player?.time = positionMs.coerceAtLeast(0)
    }

    fun setScale(mode: AspectMode) {
        player?.videoScale = when (mode) {
            AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_16_9
        }
    }

    fun release() {
        detach()
        runCatching { player?.release() }
        player = null
        runCatching { lib.release() }
    }

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        return MediaPlayer(lib).also { created ->
            player = created
            attachedLayout?.let { created.attachViews(it, null, false, false) }
            created.setEventListener { event ->
                val type = event.type
                handler.post { onEvent?.invoke(type) }
            }
        }
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }
}
