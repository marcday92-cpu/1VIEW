package com.iptv.tv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Stops the Fire TV screensaver / standby while video is on screen.
 * FLAG_KEEP_SCREEN_ON is the path Amazon documents for Fire OS; it is set on the
 * activity window and mirrored on the view, and cleared on pause / dispose so the stick can
 * sleep again once this app is in the background. Several players can be on screen at once
 * (Live preview, Multi), so the flag is reference counted.
 */
@SuppressLint("WakelockTimeout")
@Composable
fun KeepAwake() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, view) {
        val activity = context.findActivity()
        var held = false

        fun acquire() {
            if (held) return
            held = true
            view.keepScreenOn = true
            if (KeepAwakeCounter.increment() == 1) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        fun release() {
            if (!held) return
            held = false
            view.keepScreenOn = false
            if (KeepAwakeCounter.decrement() == 0) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> acquire()
                Lifecycle.Event.ON_PAUSE -> release()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            acquire()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            release()
        }
    }
}

private object KeepAwakeCounter {
    private var count = 0
    fun increment(): Int = ++count
    fun decrement(): Int = (--count).coerceAtLeast(0).also { count = it }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
