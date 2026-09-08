package com.iptv.tv.player

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Fire TV channel +/-, D-pad up/down (live), and media keys must reach the
 * player even when the bottom bar has focus. Overlay buttons swallow those
 * keys, so [dispatch] runs from [android.app.Activity.dispatchKeyEvent] first.
 *
 * D-pad zap is skipped while a CC / extras panel is open so lists still scroll.
 * Left/right stay with overlay buttons. OK / Enter is never handled here.
 *
 * One [KeyEvent.ACTION_DOWN] with [KeyEvent.getRepeatCount] == 0 is one action.
 * Held-key repeats for zap / skip about every [HOLD_REPEAT_MS]. ACTION_UP of
 * the same press never fires again; ACTION_UP without a handled DOWN (Fire OS
 * quirk) still fires once.
 *
 * OK / Enter is never handled here — that only opens the overlay in Compose.
 */
object PlayerChannelKeys {
    private const val HOLD_REPEAT_MS = 400L

    @Volatile private var isLive: () -> Boolean = { false }
    @Volatile private var dpadZap: () -> Boolean = { false }
    @Volatile private var onZap: ((Int) -> Unit)? = null
    @Volatile private var onPlayPause: (() -> Unit)? = null
    @Volatile private var onSeekBy: ((Long) -> Unit)? = null
    @Volatile private var canSeek: () -> Boolean = { false }

    private var lastHandledDownTime = 0L
    private var lastActionAt = 0L

    fun register(
        isLive: () -> Boolean,
        onZap: (Int) -> Unit,
        dpadZap: () -> Boolean = { false },
        onPlayPause: (() -> Unit)? = null,
        onSeekBy: ((Long) -> Unit)? = null,
        canSeek: () -> Boolean = { false },
    ) {
        this.isLive = isLive
        this.dpadZap = dpadZap
        this.onZap = onZap
        this.onPlayPause = onPlayPause
        this.onSeekBy = onSeekBy
        this.canSeek = canSeek
    }

    fun unregister() {
        isLive = { false }
        dpadZap = { false }
        onZap = null
        onPlayPause = null
        onSeekBy = null
        canSeek = { false }
        lastHandledDownTime = 0L
        lastActionAt = 0L
    }

    fun dispatch(event: KeyEvent): Boolean {
        if (onZap != null && isLive()) {
            val delta = deltaFor(event.keyCode)
            if (delta != null) {
                if (shouldRepeatable(event)) onZap?.invoke(delta)
                return true
            }
        }
        return dispatchTransport(event)
    }

    fun deltaFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> 1
        KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
        KeyEvent.KEYCODE_DPAD_UP -> if (dpadZap()) 1 else null
        KeyEvent.KEYCODE_DPAD_DOWN -> if (dpadZap()) -1 else null
        else -> null
    }

    fun seekDeltaFor(keyCode: Int): Long? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        -> -10_000L
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        -> 10_000L
        else -> null
    }

    fun isPlayPauseKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> true
        else -> false
    }

    private fun dispatchTransport(event: KeyEvent): Boolean {
        if (onPlayPause == null && onSeekBy == null) return false
        if (isPlayPauseKey(event.keyCode)) {
            if (shouldSingle(event)) onPlayPause?.invoke()
            return onPlayPause != null
        }
        val seek = seekDeltaFor(event.keyCode) ?: return false
        if (!canSeek()) return false
        if (shouldRepeatable(event)) onSeekBy?.invoke(seek)
        return true
    }

    private fun shouldSingle(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (lastHandledDownTime == event.downTime) return false
            lastHandledDownTime = event.downTime
            lastActionAt = SystemClock.uptimeMillis()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP &&
            event.repeatCount == 0 &&
            lastHandledDownTime != event.downTime
        ) {
            lastHandledDownTime = event.downTime
            lastActionAt = SystemClock.uptimeMillis()
            return true
        }
        return false
    }

    private fun shouldRepeatable(event: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (lastHandledDownTime == event.downTime) return false
                    lastHandledDownTime = event.downTime
                    lastActionAt = now
                    return true
                }
                if (now - lastActionAt >= HOLD_REPEAT_MS) {
                    lastActionAt = now
                    lastHandledDownTime = event.downTime
                    return true
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                if (event.repeatCount == 0 && lastHandledDownTime != event.downTime) {
                    lastHandledDownTime = event.downTime
                    lastActionAt = now
                    return true
                }
                return false
            }
            else -> return false
        }
    }
}
