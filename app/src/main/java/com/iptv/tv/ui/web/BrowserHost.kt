package com.iptv.tv.ui.web

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/**
 * Keeps one [BrowserWebView] alive and hosts HTML5 custom-view fullscreen
 * in a sibling [FrameLayout] overlay — never replacing or destroying the WebView.
 *
 * Browser tab sets [useWindowOverlay] so the player fills the activity window
 * (not the leftover NavHost under the 1VIEW tab row). Multi tiles leave it
 * false so the page plays inside the tile.
 */
class BrowserHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val webView = BrowserWebView(context)
    private val overlay = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!isFullscreen) return super.dispatchKeyEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) hideCustomView()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    -> webView.togglePlay()
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply {
        visibility = GONE
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        keepScreenOn = true
        elevation = 64f
        fitsSystemWindows = false
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, 0)
    }
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var hiding = false
    private var previousSystemUi: Int? = null

    var onFullscreenChanged: ((Boolean) -> Unit)? = null
    var useWindowOverlay: Boolean = false
    val isFullscreen: Boolean get() = customView != null

    init {
        clipChildren = false
        clipToPadding = false
        addView(webView, fillParams())
        addView(overlay, fillParams())
    }

    fun showCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            hideCustomView()
            return
        }
        if (customView != null) hideCustomView()
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customCallback = callback
        overlay.removeAllViews()
        forceFill(view)
        overlay.addView(view, fillParams())
        overlay.visibility = VISIBLE
        if (useWindowOverlay) attachOverlayToWindow() else {
            overlay.bringToFront()
        }
        overlay.requestFocus()
        applyImmersive(true)
        onFullscreenChanged?.invoke(true)
        overlay.post {
            relayoutFullscreen()
        }
    }

    fun hideCustomView() {
        if (hiding) return
        if (customView == null && customCallback == null) return
        hiding = true
        try {
            overlay.removeAllViews()
            overlay.visibility = GONE
            restoreOverlayToHost()
            applyImmersive(false)
            val callback = customCallback
            customView = null
            customCallback = null
            runCatching { callback?.onCustomViewHidden() }
            webView.requestFocus()
            onFullscreenChanged?.invoke(false)
        } finally {
            hiding = false
        }
    }

    /** Re-apply MATCH_PARENT after chrome/tab-row hide so the first frame is already full-window. */
    fun relayoutFullscreen() {
        val view = customView ?: return
        forceFill(view)
        overlay.layoutParams = fillParams()
        overlay.measure(
            MeasureSpec.makeMeasureSpec(overlayParentWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(overlayParentHeight(), MeasureSpec.EXACTLY),
        )
        overlay.layout(0, 0, overlay.measuredWidth, overlay.measuredHeight)
        overlay.requestLayout()
        view.requestLayout()
        overlay.invalidate()
        view.invalidate()
    }

    fun releaseMedia() {
        hideCustomView()
        webView.pauseMedia()
        webView.onPause()
        BrowserPersistence.flush()
    }

    fun setBrowsePage(enabled: Boolean) {
        webView.setBrowsePage(enabled)
    }

    fun handleFullscreenKey(event: KeyEvent): Boolean {
        if (!isFullscreen) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) hideCustomView()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // The overlay's own dispatchKeyEvent toggles playback for play/pause/OK keys.
        overlay.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFullscreen &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            if (event.action == KeyEvent.ACTION_UP) hideCustomView()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isFullscreen && w > 0 && h > 0) relayoutFullscreen()
    }

    private fun attachOverlayToWindow() {
        val root = windowRoot() ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        root.addView(overlay, fillParams())
        overlay.bringToFront()
        overlay.visibility = VISIBLE
    }

    private fun restoreOverlayToHost() {
        val parent = overlay.parent as? ViewGroup
        if (parent != null && parent !== this) {
            parent.removeView(overlay)
        }
        if (overlay.parent == null) {
            addView(overlay, fillParams())
        }
    }

    private fun windowRoot(): ViewGroup? {
        val activity = context as? Activity ?: return null
        return activity.findViewById(android.R.id.content)
    }

    private fun overlayParentWidth(): Int {
        val parent = overlay.parent as? View ?: this
        return parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    }

    private fun overlayParentHeight(): Int {
        val parent = overlay.parent as? View ?: this
        return parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        if (!useWindowOverlay) return
        val decor = (context as? Activity)?.window?.decorView ?: return
        if (on) {
            if (previousSystemUi == null) previousSystemUi = decor.systemUiVisibility
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            previousSystemUi?.let { decor.systemUiVisibility = it }
            previousSystemUi = null
        }
    }

    private companion object {
        fun fillParams() = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.FILL,
        ).apply { setMargins(0, 0, 0, 0) }

        fun forceFill(view: View) {
            view.fitsSystemWindows = false
            view.layoutParams = fillParams()
            view.minimumWidth = 0
            view.minimumHeight = 0
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
                view.setPadding(0, 0, 0, 0)
                if (view.childCount == 1) {
                    val child = view.getChildAt(0)
                    val clp = child.layoutParams
                    if (clp != null) {
                        clp.width = LayoutParams.MATCH_PARENT
                        clp.height = LayoutParams.MATCH_PARENT
                        if (clp is MarginLayoutParams) clp.setMargins(0, 0, 0, 0)
                        child.layoutParams = clp
                    } else {
                        child.layoutParams = fillParams()
                    }
                }
            }
        }
    }
}
