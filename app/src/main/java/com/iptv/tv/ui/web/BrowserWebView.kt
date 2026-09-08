package com.iptv.tv.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import kotlin.math.roundToInt

/**
 * The app's WebView. Remote input is handled by the on-screen pointer ([WebPointerState]),
 * which injects touches and pans the page at the viewport edge; this class also carries
 * the media helpers (play / mute / fullscreen) that the Web tab and Multi tiles drive
 * through JavaScript.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    init {
        BrowserPersistence.install(context)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // LAYER_TYPE_NONE: HTML5 video uses a SurfaceView that cannot composite
        // into a hardware layer (black picture). TextureView IPTV tiles are paused
        // when this WebView is focused so the stick can decode the page video.
        setLayerType(LAYER_TYPE_NONE, null)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Streaming sites still mix https pages with http media segments; compatibility
            // mode allows that but blocks mixed scripts and frames.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // No popup window handling exists, so nothing may open one.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            // The D-pad circle cursor owns focus movement; Chromium must not walk links.
            setNeedInitialFocus(false)
            setSupportZoom(true)
        }
    }

    fun applyUserAgent(ua: String) {
        settings.userAgentString = ua
    }

    fun injectMediaHelpers() {
        evaluateJavascript(MEDIA_JS, null)
        evaluateJavascript(PAN_JS, null)
    }

    fun setBrowsePage(enabled: Boolean) {
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        // Block descendants either way so D-pad cannot walk the DOM (tiny
        // scrollIntoView jumps) while the circle cursor is driving the page.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        if (enabled) requestFocus() else clearFocus()
    }

    fun requestHtml5Fullscreen() {
        evaluateJavascript(MEDIA_JS, null)
        evaluateJavascript("window.__tvFullscreen&&window.__tvFullscreen()", null)
    }

    fun exitHtml5Fullscreen() {
        evaluateJavascript("window.__tvExitFullscreen&&window.__tvExitFullscreen()", null)
    }

    fun pauseMedia() {
        evaluateJavascript(MEDIA_JS, null)
        evaluateJavascript("window.__tvPauseMedia&&window.__tvPauseMedia()", null)
    }

    fun togglePlay() {
        evaluateJavascript("window.__tvTogglePlay&&window.__tvTogglePlay()", null)
    }

    fun setMuted(muted: Boolean) {
        evaluateJavascript(
            "window.__tvMuted=${if (muted) "true" else "false"};window.__tvApplyMute&&window.__tvApplyMute()",
            null,
        )
    }

    fun tryPlay() {
        evaluateJavascript(MEDIA_JS, null)
        evaluateJavascript("window.__tvTryPlay&&window.__tvTryPlay()", null)
    }


    companion object {
        fun panPage(webView: WebView, dx: Float, dy: Float, viewX: Float, viewY: Float) {
            if (dx == 0f && dy == 0f) return
            val ix = dx.roundToInt()
            val iy = dy.roundToInt()
            if (ix == 0 && iy == 0) return
            val w = webView.width.coerceAtLeast(1)
            val h = webView.height.coerceAtLeast(1)
            val insetX = viewX.coerceIn(12f, (w - 12).toFloat().coerceAtLeast(12f))
            val insetY = viewY.coerceIn(12f, (h - 12).toFloat().coerceAtLeast(12f))
            val nx = (insetX / w).toDouble()
            val ny = (insetY / h).toDouble()
            // One script: install the helper if this page has not seen it yet, then pan.
            webView.evaluateJavascript(
                "$PAN_JS;window.__tvPanX=$nx;window.__tvPanY=$ny;window.__tvPan&&window.__tvPan($ix,$iy)",
                null,
            )
        }

        private const val PAN_JS = """
(function(){
  if (window.__tvPan) return;
  window.__tvPan = function(dx, dy){
    dx = dx || 0; dy = dy || 0;
    if (!dx && !dy) return 'zero';
    function overflow(el, axis){
      if (!el) return false;
      try {
        var s = getComputedStyle(el);
        var o = axis === 'y' ? (s.overflowY || s.overflow) : (s.overflowX || s.overflow);
        return o === 'auto' || o === 'scroll' || o === 'overlay';
      } catch (e) { return false; }
    }
    function can(el, dx, dy){
      if (!el) return false;
      var root = el === document.scrollingElement || el === document.documentElement || el === document.body;
      if (dy) {
        if (!root && !overflow(el, 'y')) return false;
        var max = el.scrollHeight - el.clientHeight;
        if (max <= 1) return false;
        if (dy > 0) return el.scrollTop < max - 1;
        return el.scrollTop > 0;
      }
      if (!root && !overflow(el, 'x')) return false;
      var maxw = el.scrollWidth - el.clientWidth;
      if (maxw <= 1) return false;
      if (dx > 0) return el.scrollLeft < maxw - 1;
      return el.scrollLeft > 0;
    }
    function apply(el, dx, dy){
      if (!can(el, dx, dy)) return false;
      var before = dy ? el.scrollTop : el.scrollLeft;
      try { el.scrollBy({left: dx, top: dy, behavior: 'auto'}); }
      catch (e) { try { el.scrollTop += dy; el.scrollLeft += dx; } catch (e2) {} }
      var after = dy ? el.scrollTop : el.scrollLeft;
      return after !== before;
    }
    var x = Math.max(0, Math.min(innerWidth - 1, innerWidth * (window.__tvPanX || 0.5)));
    var y = Math.max(0, Math.min(innerHeight - 1, innerHeight * (window.__tvPanY || 0.5)));
    var el = document.elementFromPoint(x, y);
    var n = 0;
    while (el && n < 32) {
      if (apply(el, dx, dy)) return 'el';
      el = el.parentElement;
      n++;
    }
    var roots = [
      document.scrollingElement,
      document.documentElement,
      document.body,
      document.querySelector('main'),
      document.getElementById('root'),
      document.getElementById('app'),
      document.getElementById('__next')
    ];
    for (var i = 0; i < roots.length; i++) {
      if (apply(roots[i], dx, dy)) return 'root';
    }
    try { window.scrollBy(dx, dy); } catch (e) {}
    try {
      var t = document.elementFromPoint(x, y) || document.documentElement;
      t.dispatchEvent(new WheelEvent('wheel', {
        deltaX: dx, deltaY: dy, deltaMode: 0,
        bubbles: true, cancelable: true, view: window
      }));
    } catch (e) {}
    return 'win';
  };
})();
"""

        private const val MEDIA_JS = """
(function(){
  if (window.__tvMedia) return;
  window.__tvMedia = true;
  window.__tvMuted = !!window.__tvMuted;
  function prep(m){
    try {
      m.setAttribute('playsinline','');
      m.setAttribute('webkit-playsinline','');
      m.playsInline = true;
    } catch(e){}
  }
  window.__tvApplyMute = function(){
    var mute = !!window.__tvMuted;
    document.querySelectorAll('video,audio').forEach(function(m){
      try {
        prep(m);
        m.muted = mute;
        if (mute) m.volume = 0; else if (m.volume===0) m.volume = 1;
      } catch(e){}
    });
  };
  window.__tvTryPlay = function(){
    var n = 0;
    document.querySelectorAll('video,audio').forEach(function(m){
      try {
        prep(m);
        m.muted = !!window.__tvMuted;
        if (!window.__tvMuted && m.volume===0) m.volume = 1;
        var p = m.play();
        if (p && p.catch) p.catch(function(){});
        n++;
      } catch(e){}
    });
    return String(n);
  };
  window.__tvPauseMedia = function(){
    document.querySelectorAll('video,audio').forEach(function(m){ try { m.pause(); } catch(e){} });
  };
  window.__tvTogglePlay = function(){
    var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
    var v = vids.filter(function(x){ return !x.paused; })[0] || vids[0];
    if (!v) return 'none';
    if (v.paused) { try { v.play(); } catch(e){} return 'play'; }
    v.pause(); return 'pause';
  };
  window.__tvFullscreen = function(){
    function vid(){
      var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
      return vids.filter(function(v){ return !v.paused && v.readyState>=2; })[0] || vids[0];
    }
    var btn = document.querySelector(
      '.vjs-fullscreen-control,.jw-icon-fullscreen,[class*="fullscreen"],[class*="Fullscreen"],[aria-label*="ull screen"],[title*="ull screen"]'
    );
    if (btn) { btn.click(); return 'click'; }
    var v = vid();
    if (!v) return 'none';
    try { v.play(); } catch(e) {}
    var target = v.closest('.video-js,.jwplayer,[class*="player"]') || v;
    if (target.requestFullscreen) { target.requestFullscreen(); return 'api'; }
    if (target.webkitRequestFullscreen) { target.webkitRequestFullscreen(); return 'api'; }
    if (v.webkitEnterFullscreen) { v.webkitEnterFullscreen(); return 'webkit'; }
    v.style.position='fixed'; v.style.inset='0'; v.style.width='100%'; v.style.height='100%';
    v.style.maxWidth='100%'; v.style.maxHeight='100%';
    v.style.zIndex='2147483647'; v.style.objectFit='contain'; v.style.background='#000';
    return 'css';
  };
  window.__tvExitFullscreen = function(){
    var exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit) try { exit.call(document); } catch(e) {}
    document.querySelectorAll('video').forEach(function(v){
      v.style.position=''; v.style.inset=''; v.style.width=''; v.style.height='';
      v.style.maxWidth=''; v.style.maxHeight='';
      v.style.zIndex=''; v.style.objectFit=''; v.style.background='';
    });
  };
  try {
    var obs = new MutationObserver(function(){
      document.querySelectorAll('video,audio').forEach(function(m){
        if (m.__tvPrep) return;
        m.__tvPrep = true;
        prep(m);
        if (window.__tvMuted) {
          try { m.muted = true; } catch(e){}
        } else {
          try { var p = m.play(); if (p && p.catch) p.catch(function(){}); } catch(e){}
        }
      });
    });
    obs.observe(document.documentElement, {childList:true, subtree:true});
  } catch(e) {}
})();
"""


    }
}
