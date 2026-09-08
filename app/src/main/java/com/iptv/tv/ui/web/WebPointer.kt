package com.iptv.tv.ui.web

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.iptv.tv.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fire-remote mouse: a visible circle over the WebView.
 * D-pad moves it (hold accelerates). At the edge of the viewport — or in a
 * band near it when the page is taller than the screen — further Up/Down
 * (and Left/Right) pan the document. OK injects a real touch at that point
 * and a JS [elementFromPoint] click as backup.
 */
class WebPointerState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    var visible by mutableStateOf(false)
    var heldDx by mutableFloatStateOf(0f)
    var heldDy by mutableFloatStateOf(0f)
    private var page: WebView? = null

    fun showCentered() {
        visible = true
        if (width > 0f && height > 0f) {
            x = width / 2f
            y = height / 2f
        }
        heldDx = 0f
        heldDy = 0f
    }

    fun hide() {
        visible = false
        heldDx = 0f
        heldDy = 0f
        page = null
    }

    fun resize(w: Float, h: Float) {
        val first = width <= 0f || height <= 0f
        width = w
        height = h
        if (first) {
            x = w / 2f
            y = h / 2f
        } else {
            x = x.coerceIn(0f, w.coerceAtLeast(1f))
            y = y.coerceIn(0f, h.coerceAtLeast(1f))
        }
    }

    fun nudge(dx: Float, dy: Float, distance: Float, hold: Boolean = false) {
        val step = pointerStep(x, y, width, height, dx, dy, distance, hold)
        x = step.x
        y = step.y
        if (step.panX != 0f || step.panY != 0f) {
            page?.let { BrowserWebView.panPage(it, step.panX, step.panY, x, y) }
        }
    }

    /**
     * @return true if the key is consumed by the pointer (never BACK / Menu).
     */
    fun onKey(event: KeyEvent, webView: WebView?): Boolean {
        if (!visible) return false
        if (webView != null) page = webView
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return false
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_TV_CONTENTS_MENU) return false
        if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_PAGE_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val dir = if (code == KeyEvent.KEYCODE_PAGE_DOWN) 1f else -1f
                val amount = max(height * PAGE_FRACTION, TAP_PAN_MIN)
                page?.let { BrowserWebView.panPage(it, 0f, dir * amount, x, y) }
            }
            return true
        }
        val dir = direction(code)
        if (dir != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    heldDx = dir.first
                    heldDy = dir.second
                    if (event.repeatCount == 0) nudge(dir.first, dir.second, TAP_STEP, hold = false)
                }
                KeyEvent.ACTION_UP -> {
                    heldDx = 0f
                    heldDy = 0f
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) webView?.let { clickOn(it, x, y) }
            return true
        }
        return false
    }

    companion object {
        const val TAP_STEP = 18f
        const val HOLD_START = 8f
        const val HOLD_MAX = 30f
        const val EDGE_FRACTION = 0.2f
        const val EDGE_MIN_PX = 56f
        const val TAP_PAN_MIN = 96f
        const val TAP_PAN_FRACTION = 0.16f
        const val HOLD_PAN_GAIN = 1.8f
        const val HOLD_PAN_MAX = 28f
        const val PAGE_FRACTION = 0.85f

        private fun direction(keyCode: Int): Pair<Float, Float>? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
            else -> null
        }

        fun clickOn(webView: WebView, viewX: Float, viewY: Float) {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
            val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, viewX, viewY, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            val w = webView.width.coerceAtLeast(1)
            val h = webView.height.coerceAtLeast(1)
            val nx = (viewX / w).toDouble()
            val ny = (viewY / h).toDouble()
            webView.evaluateJavascript(
                """
                (function(){
                  var x = innerWidth * $nx, y = innerHeight * $ny;
                  var el = document.elementFromPoint(x, y);
                  if (!el) return 'none';
                  try {
                    var opts = {bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
                    el.dispatchEvent(new MouseEvent('mousedown', opts));
                    el.dispatchEvent(new MouseEvent('mouseup', opts));
                    el.dispatchEvent(new MouseEvent('click', opts));
                  } catch (e) {}
                  try { if (el.click) el.click(); } catch (e) {}
                  try { el.focus(); } catch (e) {}
                  return el.tagName;
                })();
                """.trimIndent(),
                null,
            )
        }
    }
}

internal data class PointerStep(
    val x: Float,
    val y: Float,
    val panX: Float,
    val panY: Float,
)

/**
 * Cursor stays inside the viewport. Once it is in the edge band (or already
 * clamped), leftover movement becomes a page pan — the TV-browser behaviour.
 */
internal fun pointerStep(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    dx: Float,
    dy: Float,
    distance: Float,
    hold: Boolean,
): PointerStep {
    if (width <= 0f || height <= 0f) return PointerStep(x, y, 0f, 0f)
    val nx = (x + dx * distance).coerceIn(0f, width)
    val ny = (y + dy * distance).coerceIn(0f, height)
    val slackX = max(WebPointerState.EDGE_MIN_PX, width * WebPointerState.EDGE_FRACTION)
    val slackY = max(WebPointerState.EDGE_MIN_PX, height * WebPointerState.EDGE_FRACTION)
    val panX = when {
        dx < 0f && nx <= slackX -> -panAmount(distance, width, hold)
        dx > 0f && nx >= width - slackX -> panAmount(distance, width, hold)
        else -> 0f
    }
    val panY = when {
        dy < 0f && ny <= slackY -> -panAmount(distance, height, hold)
        dy > 0f && ny >= height - slackY -> panAmount(distance, height, hold)
        else -> 0f
    }
    return PointerStep(nx, ny, panX, panY)
}

private fun panAmount(distance: Float, viewport: Float, hold: Boolean): Float {
    return if (hold) {
        (distance * WebPointerState.HOLD_PAN_GAIN).coerceIn(14f, WebPointerState.HOLD_PAN_MAX)
    } else {
        max(distance * 5f, viewport * WebPointerState.TAP_PAN_FRACTION)
            .coerceIn(WebPointerState.TAP_PAN_MIN, viewport * 0.4f)
    }
}

@Composable
fun WebPointerOverlay(
    pointer: WebPointerState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    LaunchedEffect(enabled) {
        if (!enabled) pointer.hide()
    }
    LaunchedEffect(enabled, pointer.heldDx, pointer.heldDy) {
        if (!enabled || (pointer.heldDx == 0f && pointer.heldDy == 0f)) return@LaunchedEffect
        delay(80)
        var speed = WebPointerState.HOLD_START
        while (true) {
            pointer.nudge(pointer.heldDx, pointer.heldDy, speed, hold = true)
            speed = (speed + 1.2f).coerceAtMost(WebPointerState.HOLD_MAX)
            delay(16)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { pointer.resize(it.width.toFloat(), it.height.toFloat()) },
    ) {
        if (enabled && pointer.visible) {
            Box(
                Modifier
                    .offset { IntOffset((pointer.x - 16).roundToInt(), (pointer.y - 16).roundToInt()) }
                    .size(32.dp)
                    .border(2.dp, androidx.compose.ui.graphics.Color.White, CircleShape)
                    .background(accent.copy(alpha = 0.45f), CircleShape),
            )
            Box(
                Modifier
                    .offset { IntOffset((pointer.x - 4).roundToInt(), (pointer.y - 4).roundToInt()) }
                    .size(8.dp)
                    .background(androidx.compose.ui.graphics.Color.White, CircleShape),
            )
        }
    }
}
