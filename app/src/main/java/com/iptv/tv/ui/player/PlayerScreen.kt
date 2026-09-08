package com.iptv.tv.ui.player

import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.iptv.tv.R
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.videolan.libvlc.util.VLCVideoLayout
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.player.PlayerChannelKeys
import com.iptv.tv.player.PlayerEngine
import com.iptv.tv.player.PlayerErrorKind
import com.iptv.tv.ui.KeepAwake
import com.iptv.tv.ui.components.ProgressBar
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.VodSeekBar
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock
import com.iptv.tv.util.formatDuration
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onGuide: () -> Unit,
    onCollapseLive: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val lastChannel by viewModel.lastChannel.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val next by viewModel.next.collectAsStateWithLifecycle()
    val rec by viewModel.recording.collectAsStateWithLifecycle()
    val warning by viewModel.connectionWarning.collectAsStateWithLifecycle()
    val playerState by viewModel.playerManager.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val appearance by viewModel.subtitleAppearance.collectAsStateWithLifecycle()
    val showTimingAfterLoad by viewModel.showTimingAfterLoad.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val captionLines by viewModel.captionLines.collectAsStateWithLifecycle()
    val nextPrompt by viewModel.nextEpisode.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf(true) }
    var ccOpen by remember { mutableStateOf(false) }
    var onlineOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var remindOpen by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(false) }
    var hideGeneration by remember { mutableIntStateOf(0) }
    var swallowSelectDownTime by remember { mutableStateOf(0L) }

    val rootFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    val ccFocus = remember { FocusRequester() }
    val timingFocus = remember { FocusRequester() }
    val onlineFocus = remember { FocusRequester() }
    val nextEpisodeFocus = remember { FocusRequester() }
    val accent = LocalAccent.current
    val live = session?.type == ContentType.LIVE
    val nextUp = nextPrompt != null
    val panelOpen = ccOpen || onlineOpen || advanced || switcher || remindOpen || nextUp

    fun dismissBack() {
        when {
            nextUp -> viewModel.cancelNextEpisode()
            onlineOpen -> onlineOpen = false
            ccOpen -> ccOpen = false
            remindOpen -> remindOpen = false
            advanced -> advanced = false
            switcher -> switcher = false
            live -> onCollapseLive()
            overlay -> overlay = false
            else -> viewModel.close()
        }
    }

    BackHandler { dismissBack() }
    KeepAwake()

    val liveNow = rememberUpdatedState(live)
    val panelOpenNow = rememberUpdatedState(panelOpen)
    val onChannelZap = rememberUpdatedState<(Int) -> Unit>({ delta ->
        viewModel.zap(delta)
        overlay = true
        if (!panelOpenNow.value) hideGeneration++
    })
    val onPlayPauseKey = rememberUpdatedState({
        viewModel.togglePlayPause()
        overlay = true
        if (!panelOpenNow.value) hideGeneration++
    })
    val onSeekKey = rememberUpdatedState<(Long) -> Unit>({ ms ->
        if (!liveNow.value) {
            viewModel.seekBy(ms)
            overlay = true
            if (!panelOpenNow.value) hideGeneration++
        }
    })
    DisposableEffect(Unit) {
        PlayerChannelKeys.register(
            isLive = { liveNow.value },
            onZap = { onChannelZap.value(it) },
            dpadZap = { liveNow.value && !panelOpenNow.value },
            onPlayPause = { onPlayPauseKey.value() },
            onSeekBy = { onSeekKey.value(it) },
            canSeek = { !liveNow.value },
        )
        onDispose { PlayerChannelKeys.unregister() }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    var pendingTimingFocus by remember { mutableStateOf(false) }

    LaunchedEffect(showTimingAfterLoad) {
        if (showTimingAfterLoad) {
            onlineOpen = false
            ccOpen = true
            overlay = true
            pendingTimingFocus = true
            viewModel.consumeShowTimingAfterLoad()
        }
    }

    LaunchedEffect(ccOpen) {
        if (ccOpen && !onlineOpen) {
            delay(80)
            if (!pendingTimingFocus) {
                runCatching { ccFocus.requestFocus() }
            }
        }
    }

    LaunchedEffect(pendingTimingFocus, playerState.subtitleDelaySupported, ccOpen, onlineOpen) {
        if (!pendingTimingFocus || !ccOpen || onlineOpen) return@LaunchedEffect
        if (!playerState.subtitleDelaySupported) return@LaunchedEffect
        delay(80)
        runCatching { timingFocus.requestFocus() }
        pendingTimingFocus = false
    }

    // Focus Previous only when the overlay first appears — never on every key,
    // or moving to Guide/CC immediately jumps back and OK fires Previous.
    LaunchedEffect(overlay) {
        if (overlay) {
            delay(80)
            runCatching { overlayFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(overlay, hideGeneration, panelOpen, nextUp) {
        if (!overlay || panelOpen || nextUp) return@LaunchedEffect
        delay(10_000)
        overlay = false
    }

    LaunchedEffect(nextUp) {
        if (!nextUp) return@LaunchedEffect
        overlay = false
        delay(80)
        runCatching { nextEpisodeFocus.requestFocus() }
    }

    val shown = session
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val key = native.keyCode
                val down = native.action == KeyEvent.ACTION_DOWN

                // Channel +/- before overlay buttons. Activity.dispatchKeyEvent
                // is the primary intercept; this is the Compose backup.
                if (PlayerChannelKeys.dispatch(native)) return@onPreviewKeyEvent true

                // Fire OS often delivers BACK on UP. If we ignore UP it is swallowed
                // and neither Compose nor the activity leaves the player.
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
                    if (down) dismissBack()
                    return@onPreviewKeyEvent true
                }
                val isSelect = key == KeyEvent.KEYCODE_DPAD_CENTER ||
                    key == KeyEvent.KEYCODE_ENTER ||
                    key == KeyEvent.KEYCODE_NUMPAD_ENTER
                // Swallow only the UP of the press that revealed the overlay; a held OK keeps
                // repeating with the same downTime and must still reach the long-press branch.
                if (isSelect && native.downTime == swallowSelectDownTime &&
                    (native.action == KeyEvent.ACTION_UP || native.repeatCount == 0)
                ) {
                    return@onPreviewKeyEvent true
                }
                if (!down) return@onPreviewKeyEvent false

                if (overlay && !panelOpen) hideGeneration++

                when (key) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        when {
                            panelOpen -> false
                            native.isLongPress -> {
                                switcher = true
                                true
                            }
                            overlay -> false
                            else -> {
                                swallowSelectDownTime = native.downTime
                                overlay = true
                                true
                            }
                        }
                    }
                    // D-pad up/down zap live (overlay or not). Activity.dispatchKeyEvent
                    // is primary so focused overlay buttons cannot swallow them.
                    // Left/Right stay with overlay buttons. CC/extras panels keep D-pad.
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!panelOpen && live) {
                            viewModel.zap(1)
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!panelOpen && live) {
                            viewModel.zap(-1)
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_F11 -> {
                        stats = !stats
                        true
                    }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> {
                        if (live && !panelOpen) {
                            viewModel.zapLast()
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        viewModel.togglePlayPause()
                        overlay = true
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    -> {
                        if (!live) {
                            viewModel.seekBy(-10_000)
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    -> {
                        if (!live) {
                            viewModel.seekBy(10_000)
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!overlay && !panelOpen && !live) {
                            viewModel.seekBy(-10_000)
                            overlay = true
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!overlay && !panelOpen && !live) {
                            viewModel.seekBy(10_000)
                            overlay = true
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        if (playerState.engine == PlayerEngine.VLC) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        viewModel.playerManager.attachVlcLayout(layout)
                    }
                },
                onRelease = { layout -> viewModel.playerManager.detachVlcLayout(layout) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        useController = false
                        keepScreenOn = true
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        player = viewModel.playerManager.player
                    }
                },
                update = { view ->
                    if (view.player !== viewModel.playerManager.player) {
                        view.player = viewModel.playerManager.player
                    }
                    // A loaded sidecar is drawn by the Compose overlay (with the user's delay);
                    // Media3's own SubtitleView must stay hidden or the undelayed cue shows in gaps.
                    val sidecarOverlay = playerState.subtitleDelaySupported && playerState.subtitlesEnabled
                    view.resizeMode = when (playerState.aspectMode) {
                        com.iptv.tv.player.AspectMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        com.iptv.tv.player.AspectMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        com.iptv.tv.player.AspectMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    view.subtitleView?.apply {
                        visibility = if (sidecarOverlay) View.GONE else View.VISIBLE
                        bringToFront()
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                if (appearance.backgroundBox) 0xCC000000.toInt() else android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            ),
                        )
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp)
                        setBottomPaddingFraction(appearance.bottomPaddingFraction)
                    }
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (playerState.subtitlesEnabled && captionLines.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 96.dp)
                    .padding(bottom = if (overlay) 220.dp else (24 + (appearance.bottomPaddingFraction * 80)).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                captionLines.forEach { line ->
                    Text(
                        line,
                        color = Color.White,
                        fontSize = appearance.textSizeSp.sp,
                        modifier = if (appearance.backgroundBox) {
                            Modifier
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        // Buffering: a small, delayed indicator so a normal one-second start does not flash.
        val buffering = playerState.isBuffering && !playerState.isPlaying && playerState.error == null
        var showBuffering by remember { mutableStateOf(false) }
        LaunchedEffect(buffering, shown?.contentKey) {
            if (!buffering) {
                showBuffering = false
            } else {
                delay(700)
                showBuffering = true
            }
        }
        if (showBuffering) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BufferingSpinner()
                Text(
                    if (shown?.type == ContentType.LIVE) "Connecting to ${shown.title}…" else "Loading…",
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }

        // Playback failed: say why and offer a way out. Focus lands on Retry.
        val playerError = playerState.error
        if (playerError != null && shown != null) {
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(playerError) {
                delay(80)
                runCatching { retryFocus.requestFocus() }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(560.dp)
                    .background(Color(0xF216161A), RoundedCornerShape(16.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when (playerState.errorKind) {
                        PlayerErrorKind.OFFLINE -> "No internet connection"
                        PlayerErrorKind.UNSUPPORTED -> "Can't play this stream"
                        else -> "Stream unavailable"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Text(shown.title, color = TextSecondary)
                Text(playerError, color = TextSecondary, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.retry() },
                        modifier = Modifier.focusRequester(retryFocus),
                    ) { Text("Retry") }
                    if (playerState.errorKind != PlayerErrorKind.OFFLINE) {
                        Button(onClick = { viewModel.switchEngine() }) {
                            Text(if (playerState.engine == PlayerEngine.VLC) "Try Media3 player" else "Try VLC player")
                        }
                    }
                    if (live && shown.playlist.size > 1) {
                        Button(onClick = { viewModel.zap(1) }) { Text("Next channel") }
                    }
                    Button(onClick = { dismissBack() }) { Text(if (live) "Back to list" else "Close") }
                }
            }
        }

        rec?.let {
            Text(
                "● REC  ${it.title}",
                color = LocalAccent.current,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
        }

        if (stats) {
            val s = playerState.stats
            Text(
                buildString {
                    appendLine("${s.engine}  ${s.resolution}  ${s.fps} fps")
                    appendLine("Video ${s.videoCodec}")
                    appendLine("Audio ${s.audioCodec}")
                    appendLine("Bitrate ${s.bitrate}")
                    appendLine("Buffers ${s.bufferCount}  Latency ${s.latency}")
                }.trim(),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }

        if (overlay && shown != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(32.dp),
            ) {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    shown.subtitle ?: now?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                val nowProgramme = now
                if (shown.type == ContentType.LIVE && nowProgramme != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(nowProgramme.startTimeMs), color = TextSecondary)
                        ProgressBar(nowProgramme.progressFraction, Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text(formatClock(nowProgramme.endTimeMs), color = TextSecondary)
                    }
                } else {
                    VodSeekBar(
                        positionMs = playerState.positionMs,
                        durationMs = playerState.durationMs,
                        onSeek = viewModel::seekTo,
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = overlayFocus,
                    )
                    Text(formatDuration(playerState.positionMs), color = TextSecondary)
                }
                Spacer(Modifier.height(16.dp))
                val channelKeys = Modifier.onPreviewKeyEvent {
                    PlayerChannelKeys.dispatch(it.nativeKeyEvent)
                }
                if (live) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.zap(-1) },
                            modifier = Modifier.focusRequester(overlayFocus).then(channelKeys),
                        ) { Text("Previous") }
                        Button(
                            onClick = {
                                viewModel.close()
                                onGuide()
                            },
                            modifier = channelKeys,
                        ) { Text("Guide") }
                        Button(
                            onClick = { viewModel.zap(1) },
                            modifier = channelKeys,
                        ) { Text("Next") }
                        if (lastChannel != null) {
                            Button(
                                onClick = { viewModel.zapLast() },
                                modifier = channelKeys,
                            ) { Text("Last") }
                        }
                        Button(onClick = { ccOpen = true }, modifier = channelKeys) { Text("CC") }
                        Button(onClick = { advanced = true }, modifier = channelKeys) { Text("Audio & video") }
                        if (IptvCapabilities.canRemind(session?.channel, viewModel.hasIptvLogin)) {
                            Button(onClick = { remindOpen = true }, modifier = channelKeys) { Text("Remind") }
                        }
                        if (IptvCapabilities.canRecord(session?.channel, viewModel.hasIptvLogin)) {
                            Button(onClick = { viewModel.recordNow() }, modifier = channelKeys) { Text("Record") }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.seekBy(-10_000) }) { Text("−10 sec") }
                        Button(onClick = { viewModel.togglePlayPause() }) {
                            Text(if (playerState.isPlaying) "Pause" else "Play")
                        }
                        Button(onClick = { viewModel.seekBy(10_000) }) { Text("+10 sec") }
                        Button(onClick = { ccOpen = true }) { Text("CC") }
                        Button(onClick = { advanced = true }) { Text("Audio & video") }
                        if (session?.type == ContentType.EPISODE) {
                            Button(onClick = { viewModel.playNextEpisodeManual() }) { Text("Play next episode") }
                        }
                        Button(onClick = { viewModel.close() }) { Text("Close") }
                    }
                }
            }
        }

        if (ccOpen) {
            TvModal(onDismiss = { ccOpen = false }, alignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(440.dp)
                    .fillMaxSize()
                    .background(Color(0xF216161A))
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("SUBTITLES", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                val activeTrack = playerState.textTracks.find { it.selected }
                Text(
                    when {
                        !playerState.subtitlesEnabled -> "Currently off"
                        activeTrack != null && playerState.subtitleDelaySupported ->
                            "Currently on · ${activeTrack.label} · delay ${playerState.subtitleDelayMs} ms"
                        activeTrack != null -> "Currently on · ${activeTrack.label}"
                        else -> "Currently off"
                    },
                    color = if (playerState.subtitlesEnabled && activeTrack != null) accent else TextSecondary,
                    fontSize = 14.sp,
                )

                // Sidecar timing first so a SubDL load lands on the nudge buttons.
                if (playerState.subtitleDelaySupported) {
                    Text("Timing", color = TextMuted)
                    Text(
                        "Plus if lines are early. Minus if they're late.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.nudgeDelay(-50) },
                            modifier = Modifier.weight(1f).focusRequester(timingFocus),
                        ) { Text("-50") }
                        Button(
                            onClick = { viewModel.nudgeDelay(50) },
                            modifier = Modifier.weight(1f),
                        ) { Text("+50") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.nudgeDelay(-100) },
                            modifier = Modifier.weight(1f),
                        ) { Text("-100") }
                        Button(
                            onClick = { viewModel.resetDelay() },
                            modifier = Modifier.weight(1f),
                        ) { Text("RESET") }
                        Button(
                            onClick = { viewModel.nudgeDelay(100) },
                            modifier = Modifier.weight(1f),
                        ) { Text("+100") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.nudgeDelay(-500) },
                            modifier = Modifier.weight(1f),
                        ) { Text("-500") }
                        Button(
                            onClick = { viewModel.nudgeDelay(500) },
                            modifier = Modifier.weight(1f),
                        ) { Text("+500") }
                    }
                    Text("Delay: ${playerState.subtitleDelayMs} ms", color = TextSecondary)
                    Button(
                        onClick = { viewModel.autoSync() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Auto Sync") }
                    if (sync.isNotBlank()) Text(sync, color = TextSecondary, fontSize = 13.sp)
                }

                Text("Embedded", color = TextMuted)
                CcRow(
                    label = "Off",
                    selected = !playerState.subtitlesEnabled || activeTrack == null,
                    onClick = { viewModel.disableSubtitles() },
                    focusRequester = ccFocus,
                )
                if (playerState.textTracks.isEmpty()) {
                    Text(
                        "This stream carries no subtitle tracks. Use Online or External below.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
                playerState.textTracks.forEach { track ->
                    CcRow(
                        label = track.label + (track.language?.let { " ($it)" } ?: ""),
                        selected = track.selected && playerState.subtitlesEnabled,
                        onClick = { viewModel.selectTextTrack(track) },
                    )
                }
                Text("Online", color = TextMuted)
                Button(onClick = {
                    viewModel.openOnlineSubtitles()
                    ccOpen = false
                    onlineOpen = true
                }) { Text("Online Subtitles") }
                if (!playerState.subtitleDelaySupported) {
                    Text("Timing", color = TextMuted)
                    Text(
                        "Timing adjustment needs a loaded subtitle file. Captions built " +
                            "into the stream can't be shifted.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    if (sync.isNotBlank()) Text(sync, color = TextSecondary, fontSize = 13.sp)
                }

                Text("Appearance", color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.nudgeSubtitleSize(-2f) }) { Text("Smaller") }
                    Button(onClick = { viewModel.nudgeSubtitleSize(2f) }) { Text("Bigger") }
                    Button(onClick = { viewModel.toggleSubtitleBackground() }) {
                        Text(if (appearance.backgroundBox) "Box off" else "Box on")
                    }
                }
                Text("Size: ${appearance.textSizeSp.toInt()}sp", color = TextSecondary, fontSize = 13.sp)
                var showUrlField by remember { mutableStateOf(false) }
                if (!showUrlField) {
                    Button(onClick = { showUrlField = true }) { Text("Load from URL") }
                } else {
                    Text("Paste a direct .srt link, then load.", color = TextMuted, fontSize = 13.sp)
                    val subUrl by viewModel.subtitleUrl.collectAsStateWithLifecycle()
                    com.iptv.tv.ui.components.TvTextField(subUrl, viewModel::setSubtitleUrl, "https://…/file.srt")
                    Button(onClick = { viewModel.loadSubtitleUrl() }) { Text("Load subtitle URL") }
                    Button(onClick = { showUrlField = false }) { Text("Cancel URL") }
                }
                Button(onClick = { ccOpen = false }) { Text("Close") }
            }
            }
        }

        if (onlineOpen) {
            val online by viewModel.onlineSubtitles.collectAsStateWithLifecycle()
            var editingQuery by remember(onlineOpen) { mutableStateOf(false) }
            val firstResultFocus = remember { FocusRequester() }
            LaunchedEffect(onlineOpen, online.searching, online.results.firstOrNull()?.id, editingQuery) {
                if (!onlineOpen) return@LaunchedEffect
                delay(80)
                if (!editingQuery && online.results.isNotEmpty() && !online.searching) {
                    runCatching { firstResultFocus.requestFocus() }
                } else {
                    runCatching { onlineFocus.requestFocus() }
                }
            }
            TvModal(onDismiss = { onlineOpen = false; ccOpen = true }, alignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(520.dp)
                    .fillMaxSize()
                    .background(Color(0xF216161A))
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("ONLINE SUBTITLES", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text("SubDL · English", color = TextMuted, fontSize = 13.sp)
                Text(
                    if (online.query.isBlank()) "No title filled in. Edit the search."
                    else "Search: ${online.query}",
                    color = TextPrimary,
                    fontSize = 16.sp,
                )
                if (editingQuery) {
                    com.iptv.tv.ui.components.TvTextField(
                        online.query,
                        viewModel::setOnlineSubtitleQuery,
                        "Title, year, or S01E02",
                        Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        editingQuery = false
                        viewModel.searchOnlineSubtitles()
                    }) { Text("Search this title") }
                }
                Button(
                    onClick = {
                        if (editingQuery) {
                            editingQuery = false
                            viewModel.searchOnlineSubtitles()
                        } else {
                            viewModel.searchOnlineSubtitles()
                        }
                    },
                    modifier = Modifier.focusRequester(onlineFocus),
                ) {
                    Text(if (online.searching) "Searching…" else "Search")
                }
                if (!editingQuery) {
                    Button(onClick = { editingQuery = true }) { Text("Edit search") }
                }
                if (online.status.isNotBlank()) {
                    Text(online.status, color = TextSecondary, fontSize = 13.sp)
                }
                online.results.forEachIndexed { index, candidate ->
                    val downloading = online.downloadingId == candidate.id
                    val label = candidate.fileName.ifBlank { candidate.releaseName }
                        .orEmpty().ifBlank { "${candidate.language.uppercase()} subtitle" }
                    Surface(
                        onClick = { viewModel.downloadSubtitle(candidate) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                (if (downloading) "Downloading · " else "") + label,
                                color = TextPrimary,
                                maxLines = 2,
                            )
                            Text(
                                buildString {
                                    append(candidate.provider)
                                    append(" · ")
                                    append(candidate.language.uppercase())
                                    append(" · ")
                                    append("${candidate.matchScore}% match")
                                    if (candidate.hearingImpaired) append(" · HI")
                                    candidate.releaseName?.takeIf {
                                        it.isNotBlank() && it != label
                                    }?.let { append(" · $it") }
                                },
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Button(onClick = { onlineOpen = false; ccOpen = true }) { Text("Back to subtitles") }
            }
            }
        }

        if (remindOpen) {
            TvModal(onDismiss = { remindOpen = false }) {
                Column(
                    Modifier
                        .width(420.dp)
                        .background(Color(0xEE16161A), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Remind", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Button(onClick = { viewModel.remind(5); remindOpen = false }) { Text("5 minutes before") }
                    Button(onClick = { viewModel.remind(10); remindOpen = false }) { Text("10 minutes before") }
                    Button(onClick = { viewModel.remind(15); remindOpen = false }) { Text("15 minutes before") }
                    Button(onClick = { remindOpen = false }) { Text("Close") }
                }
            }
        }

        if (advanced) {
            TvModal(onDismiss = { advanced = false }) {
            Column(
                Modifier
                    .width(480.dp)
                    .background(Color(0xEE16161A))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Audio & video", style = MaterialTheme.typography.headlineSmall)
                playerState.audioTracks.forEach { track ->
                    Surface(onClick = { viewModel.selectAudio(track) }) {
                        Text((if (track.selected) "✓ " else "") + "Audio: ${track.label}", modifier = Modifier.padding(10.dp))
                    }
                }
                Text("Audio sync", color = TextMuted)
                Text(
                    if (playerState.engine == PlayerEngine.VLC) {
                        "Auto is 0 ms. Nudge only if this stream is out of time."
                    } else {
                        "Auto uses the player timestamps. Switch this stream to VLC from the channel menu if you need a manual offset."
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                )
                Text("Offset: ${playerState.audioDelayMs} ms", color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.nudgeAudioDelay(-50) }) { Text("−50 ms") }
                    Button(onClick = { viewModel.nudgeAudioDelay(50) }) { Text("+50 ms") }
                    Button(onClick = { viewModel.resetAudioDelay() }) { Text("Reset to Auto") }
                }
                Button(onClick = { stats = !stats; advanced = false }) {
                    Text(if (stats) "Hide stream info" else "Stream info")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.setAspect(com.iptv.tv.player.AspectMode.FIT) }) { Text("Fit") }
                    Button(onClick = { viewModel.setAspect(com.iptv.tv.player.AspectMode.FILL) }) { Text("Fill") }
                    Button(onClick = { viewModel.setAspect(com.iptv.tv.player.AspectMode.ZOOM) }) { Text("Zoom") }
                }
                playerState.error?.let { Text("Error: $it", color = LocalAccent.current) }
                Button(onClick = { advanced = false }) { Text("Close") }
            }
            }
        }

        if (switcher) {
            TvModal(onDismiss = { switcher = false }) {
            Column(
                Modifier
                    .width(420.dp)
                    .background(Color(0xEE16161A))
                    .padding(24.dp),
            ) {
                Text("RECENT CHANNELS", style = MaterialTheme.typography.headlineSmall)
                recent.forEach { ch ->
                    Surface(
                        onClick = { viewModel.playRecent(ch); switcher = false },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    ) {
                        Text(ch.name, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            }
        }

        warning?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).background(Color.Black.copy(alpha = 0.7f)).padding(12.dp),
                color = Color.White,
            )
        }

        nextPrompt?.let { prompt ->
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(40.dp)
                    .width(460.dp)
                    .background(Color(0xF216161A), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("NEXT EPISODE", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                Text(
                    prompt.seriesName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Text(
                    "S${prompt.seasonNum} E${prompt.episodeNum}  ${prompt.episodeTitle}",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextSecondary,
                )
                Text(
                    "Next episode in ${prompt.secondsLeft}…",
                    color = TextPrimary,
                    fontSize = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.playNextEpisodeNow() },
                        modifier = Modifier.focusRequester(nextEpisodeFocus),
                    ) { Text("Play now") }
                    Button(onClick = { viewModel.cancelNextEpisode() }) { Text("Cancel") }
                }
            }
        }
    }

}

@Composable
private fun CcRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f),
            contentColor = TextPrimary,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
            pressedContainerColor = accent,
            pressedContentColor = Color.White,
        ),
    ) {
        Text(
            (if (selected) "✓  " else "") + label,
            modifier = Modifier.padding(12.dp),
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun BufferingSpinner() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "angle",
    )
    val accent = LocalAccent.current
    androidx.compose.foundation.Canvas(Modifier.width(22.dp).height(22.dp)) {
        val stroke = 3.dp.toPx()
        drawArc(
            color = Color.White.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawArc(
            color = accent,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
    }
}
