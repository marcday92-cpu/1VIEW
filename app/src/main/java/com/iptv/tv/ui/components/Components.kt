package com.iptv.tv.ui.components

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.iptv.tv.player.SeekScrub
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.tv.ui.theme.LocalAccent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FocusScale(
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = tween(160),
        label = "focusScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .then(
                if (focused) Modifier.border(2.dp, LocalAccent.current, RoundedCornerShape(8.dp))
                else Modifier,
            ),
    ) { content() }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) LocalAccent.current else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(16.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(LocalAccent.current),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            visualTransformation = if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) {
                        false
                    } else when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        -> {
                            focusManager.moveFocus(FocusDirection.Down)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            focusManager.moveFocus(FocusDirection.Up)
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.2f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(LocalAccent.current),
        )
    }
}

/** D-pad Right from a left rail always lands on [target], even if the pane has one control. */
fun Modifier.tvDpadRightTo(target: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action == KeyEvent.ACTION_DOWN &&
        native.repeatCount == 0 &&
        native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    ) {
        runCatching { target.requestFocus() }.getOrDefault(false)
    } else {
        false
    }
}

/** D-pad Left returns to the settings category list. */
fun Modifier.tvDpadLeftTo(target: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action == KeyEvent.ACTION_DOWN &&
        native.repeatCount == 0 &&
        native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
    ) {
        runCatching { target.requestFocus() }.getOrDefault(false)
    } else {
        false
    }
}

/**
 * Focusable VOD timeline. Left/Right scrub, hold for larger steps, tap/drag
 * to a fraction of the bar. OK does not seek.
 */
@Composable
fun VodSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onSizeChanged { barSize = it }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) LocalAccent.current else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(vertical = 10.dp)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val step = SeekScrub.stepMs(durationMs, held = native.repeatCount > 0)
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeek(SeekScrub.applyStep(positionMs, durationMs, -step))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeek(SeekScrub.applyStep(positionMs, durationMs, step))
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    onSeek(SeekScrub.positionFromFraction(offset.x / width, durationMs))
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures { change, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    onSeek(SeekScrub.positionFromFraction(change.position.x / width, durationMs))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        ProgressBar(progress, Modifier.fillMaxWidth())
    }
}

/**
 * Fire TV often never delivers [android.view.KeyEvent.isLongPress] to a Compose Surface
 * because the click consumes OK first. Hold OK ~550ms, or press Menu / Info.
 */
@Composable
fun Modifier.tvHoldOptions(onOptions: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    var hold by remember { mutableStateOf<Job?>(null) }
    var consumed by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val code = native.keyCode
        val menu = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO
        val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        when {
            native.action == KeyEvent.ACTION_DOWN && menu && native.repeatCount == 0 -> {
                hold?.cancel()
                onOptions()
                true
            }
            native.action == KeyEvent.ACTION_DOWN && select && native.repeatCount == 0 -> {
                consumed = false
                hold?.cancel()
                hold = scope.launch {
                    delay(550)
                    consumed = true
                }
                false
            }
            native.action == KeyEvent.ACTION_DOWN && select &&
                (native.isLongPress || native.repeatCount > 0) -> {
                hold?.cancel()
                consumed = true
                true
            }
            native.action == KeyEvent.ACTION_UP && select -> {
                hold?.cancel()
                hold = null
                val swallowClick = consumed
                if (consumed) onOptions()
                consumed = false
                swallowClick
            }
            else -> false
        }
    }
}

/** Full-screen dim + panel that actually traps the remote. Overlay Boxes do not. */
@Composable
fun TvModal(
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        BackHandler(onBack = onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .widthIn(min = 320.dp)
                .heightIn(min = 180.dp)
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = alignment,
        ) {
            content()
        }
    }
}

@Composable
fun FreeBadge(selected: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        "FREE",
        color = if (selected) Color.White else LocalAccent.current,
        fontSize = 10.sp,
        modifier = modifier.padding(start = 6.dp),
    )
}
