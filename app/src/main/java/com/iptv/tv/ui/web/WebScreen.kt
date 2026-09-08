package com.iptv.tv.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.Charcoal
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    viewModel: WebViewModel = hiltViewModel(),
    onLeaveTab: () -> Unit = {},
    onFullscreen: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rendererGeneration by viewModel.rendererGeneration.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf<BrowserHost?>(null) }
    /** The last address this screen asked the WebView to load; redirects must not re-trigger it. */
    val requestedUrl = remember { arrayOf<String?>(null) }
    var urlEdit by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var hostFullscreen by remember { mutableStateOf(false) }
    var layoutFullscreen by remember { mutableStateOf(false) }
    var browsePage by remember { mutableStateOf(true) }
    var fullscreenNonce by remember { mutableStateOf(0) }
    val pointer = remember { WebPointerState() }
    val barFocus = remember { FocusRequester() }
    val pageRequester = remember { FocusRequester() }
    val browsing = state.url != WebViewModel.START
    val hideChrome = hostFullscreen || layoutFullscreen
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                BrowserPersistence.flush()
                host?.webView?.onPause()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                host?.webView?.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            BrowserPersistence.flush()
            lifecycleOwner.lifecycle.removeObserver(observer)
            host?.releaseMedia()
        }
    }

    LaunchedEffect(Unit) {
        WebLaunch.pendingUrl?.let { pending ->
            WebLaunch.pendingUrl = null
            browsePage = true
            viewModel.open(pending)
        }
    }

    LaunchedEffect(hideChrome, host) {
        onFullscreen(hideChrome)
        if (hideChrome) {
            delay(16)
            host?.relayoutFullscreen()
        }
    }

    LaunchedEffect(fullscreenNonce, host) {
        if (fullscreenNonce == 0 || host == null) return@LaunchedEffect
        delay(48)
        host?.relayoutFullscreen()
        host?.webView?.requestHtml5Fullscreen()
    }

    val interceptBack = browsing && (hideChrome || browsePage)
    BackHandler(enabled = interceptBack) {
        when {
            hostFullscreen -> host?.hideCustomView()
            layoutFullscreen -> {
                host?.webView?.exitHtml5Fullscreen()
                layoutFullscreen = false
            }
            host?.webView?.canGoBack() == true -> host?.webView?.goBack()
            else -> browsePage = false
        }
    }
    BackHandler(enabled = browsing && !browsePage && !hideChrome) {
        onLeaveTab()
    }

    LaunchedEffect(browsing, browsePage, hideChrome, host) {
        if (!browsing || hideChrome) {
            pointer.hide()
            return@LaunchedEffect
        }
        delay(80)
        if (browsePage) {
            runCatching { pageRequester.requestFocus() }
            host?.setBrowsePage(true)
            pointer.showCentered()
        } else {
            host?.setBrowsePage(false)
            pointer.hide()
            runCatching { barFocus.requestFocus() }
        }
    }

    fun enterPage() {
        browsePage = true
        pointer.showCentered()
    }

    fun enterChrome() {
        browsePage = false
        pointer.hide()
    }

    fun requestFullscreen() {
        layoutFullscreen = true
        browsePage = true
        onFullscreen(true)
        fullscreenNonce += 1
    }

    // The WebView's own video keeps the screen on, but a plain page does not; the Web tab
    // is used for long streams so keep the stick awake while a page is open.
    if (browsing) com.iptv.tv.ui.KeepAwake()

    Box(Modifier.fillMaxSize().background(Charcoal)) {
        Column(Modifier.fillMaxSize()) {
            if (browsing && !hideChrome) {
                BrowserBar(
                    title = state.title.ifBlank { state.url },
                    browsePage = browsePage,
                    desktopUa = state.desktopUa,
                    barFocus = barFocus,
                    onToggleMode = { if (browsePage) enterChrome() else enterPage() },
                    onBack = { host?.webView?.goBack() },
                    onForward = { host?.webView?.goForward() },
                    onReload = { host?.webView?.reload() },
                    onStart = {
                        layoutFullscreen = false
                        host?.hideCustomView()
                        viewModel.goHome()
                        browsePage = true
                    },
                    featuredLabel = viewModel.edition.featuredSite?.label,
                    onFeatured = {
                        browsePage = true
                        viewModel.openFeatured()
                    },
                    onAddress = {
                        urlText = state.url
                        urlEdit = true
                    },
                    onFullscreen = { requestFullscreen() },
                    onToggleUa = {
                        viewModel.toggleDesktopUa()
                        host?.webView?.applyUserAgent(viewModel.userAgent())
                        host?.webView?.reload()
                    },
                )
            }
            if (!browsing) {
                val recents by viewModel.recentSites.collectAsStateWithLifecycle()
                StartPage(
                    featuredLabel = viewModel.edition.featuredSite?.label,
                    hint = viewModel.edition.startPageHint,
                    recents = recents,
                    onFeatured = {
                        browsePage = true
                        viewModel.openFeatured()
                    },
                    onOpenUrl = {
                        urlText = "https://"
                        urlEdit = true
                    },
                    onOpenRecent = { site ->
                        browsePage = true
                        viewModel.open(site.url)
                    },
                    onForgetRecent = viewModel::forgetRecent,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .focusRequester(pageRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (hideChrome) {
                                return@onPreviewKeyEvent host?.handleFullscreenKey(native) == true
                            }
                            if (!browsePage) return@onPreviewKeyEvent false
                            if (isMenuKey(native)) {
                                if (native.action == KeyEvent.ACTION_UP) enterChrome()
                                return@onPreviewKeyEvent true
                            }
                            pointer.onKey(native, host?.webView)
                        },
                ) {
                    androidx.compose.runtime.key(rendererGeneration) {
                    AndroidView(
                        factory = { ctx ->
                            requestedUrl[0] = null
                            BrowserHost(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                useWindowOverlay = true
                                webView.applyUserAgent(viewModel.userAgent())
                                webView.onResume()
                                webView.webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        url?.let { viewModel.onPageStarted(it) }
                                        webView.injectMediaHelpers()
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        url?.let { viewModel.onNavigated(it, view?.title.orEmpty()) }
                                        webView.injectMediaHelpers()
                                        BrowserPersistence.flush()
                                    }
                                    override fun onRenderProcessGone(
                                        view: WebView?,
                                        detail: android.webkit.RenderProcessGoneDetail?,
                                    ): Boolean {
                                        // Returning true keeps the app alive; the host is rebuilt via rendererGeneration.
                                        host = null
                                        viewModel.onRendererGone()
                                        return true
                                    }
                                }
                                webView.webChromeClient = object : WebChromeClient() {
                                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                        showCustomView(view, callback)
                                    }
                                    override fun onHideCustomView() {
                                        hideCustomView()
                                    }

                                    @Deprecated("Deprecated in Java")
                                    override fun onShowCustomView(
                                        view: View?,
                                        requestedOrientation: Int,
                                        callback: CustomViewCallback?,
                                    ) {
                                        onShowCustomView(view, callback)
                                    }
                                }
                                onFullscreenChanged = { fs ->
                                    hostFullscreen = fs
                                    // Leaving HTML5 fullscreen must also drop our own fullscreen layout,
                                    // or the chrome stays hidden and the page cannot take focus.
                                    layoutFullscreen = fs
                                    onFullscreen(fs)
                                    if (fs) post { relayoutFullscreen() }
                                }
                                host = this
                            }
                        },
                        update = { view ->
                            view.setBrowsePage(browsePage && !hideChrome)
                            // Load only when the *requested* address changes; the WebView's own URL
                            // moves with redirects and in-page navigation and must be left alone.
                            if (state.url != WebViewModel.START && requestedUrl[0] != state.url &&
                                view.webView.url != state.url
                            ) {
                                requestedUrl[0] = state.url
                                view.webView.loadUrl(state.url)
                            }
                        },
                        onRelease = {
                            it.releaseMedia()
                            it.webView.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                    WebPointerOverlay(
                        pointer = pointer,
                        enabled = browsePage && !hideChrome,
                    )
                    if (!browsePage && !hideChrome) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .focusable()
                                .clickable { enterPage() }
                                .onPreviewKeyEvent { event ->
                                    val code = event.nativeKeyEvent.keyCode
                                    val ok = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        code == KeyEvent.KEYCODE_ENTER
                                    if (ok && event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                        enterPage()
                                        true
                                    } else false
                                },
                        )
                    }
                }
            }
        }
    }

    if (urlEdit) {
        TvModal(onDismiss = { urlEdit = false }) {
            Column(
                Modifier.width(560.dp).background(Color(0xFF1C1C22)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Open address", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                TvTextField(urlText, { urlText = it }, "https://…")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        browsePage = true
                        viewModel.open(urlText)
                        urlEdit = false
                    }) { Text("Go") }
                    Button(onClick = { urlEdit = false }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun BrowserBar(
    title: String,
    browsePage: Boolean,
    desktopUa: Boolean,
    barFocus: FocusRequester,
    onToggleMode: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStart: () -> Unit,
    featuredLabel: String?,
    onFeatured: () -> Unit,
    onAddress: () -> Unit,
    onFullscreen: () -> Unit,
    onToggleUa: () -> Unit,
) {
    val chromeFocus = Modifier.focusProperties { canFocus = !browsePage }
    Column(Modifier.fillMaxWidth().background(Color(0xE61C1C22))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onToggleMode,
                modifier = chromeFocus.focusRequester(barFocus),
            ) {
                Text(if (browsePage) "1VIEW menu" else "Browse page")
            }
            Button(onClick = onBack, modifier = chromeFocus) { Text("Back") }
            Button(onClick = onForward, modifier = chromeFocus) { Text("Forward") }
            Button(onClick = onReload, modifier = chromeFocus) { Text("Reload") }
            Button(onClick = onStart, modifier = chromeFocus) { Text("Start") }
            if (featuredLabel != null) {
                Button(onClick = onFeatured, modifier = chromeFocus) { Text(featuredLabel) }
            }
            Button(onClick = onAddress, modifier = chromeFocus) { Text("Address") }
            Button(onClick = onFullscreen, modifier = chromeFocus) { Text("Full screen") }
            Button(onClick = onToggleUa, modifier = chromeFocus) {
                Text(if (desktopUa) "Mobile site" else "Desktop site")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (browsePage) {
                    "Move circle  ·  at the edge the page scrolls  ·  OK clicks  ·  BACK: 1VIEW controls  ·  Menu: address bar"
                } else {
                    "1VIEW controls  ·  UP: tabs  ·  BACK: leave Web  ·  Browse page returns the cursor"
                },
                color = TextMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(title, color = TextMuted, maxLines = 1)
        }
    }
}

private fun isMenuKey(event: KeyEvent): Boolean =
    event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU

@Composable
private fun StartPage(
    featuredLabel: String?,
    hint: String,
    recents: List<RecentSite>,
    onFeatured: () -> Unit,
    onOpenUrl: () -> Unit,
    onOpenRecent: (RecentSite) -> Unit,
    onForgetRecent: (RecentSite) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Browser", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(hint, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (featuredLabel != null) {
                Button(onClick = onFeatured, modifier = Modifier.focusRequester(firstFocus)) {
                    Text(featuredLabel)
                }
                Button(onClick = onOpenUrl) { Text("Open a web address") }
            } else {
                Button(onClick = onOpenUrl, modifier = Modifier.focusRequester(firstFocus)) {
                    Text("Open a web address")
                }
            }
        }
        if (recents.isNotEmpty()) {
            Text("RECENT SITES", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            Text("OK opens a site. Hold OK to remove it from this list.", color = TextMuted)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(recents, key = { it.url }) { site ->
                    Surface(
                        onClick = { onOpenRecent(site) },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .tvHoldOptions { onForgetRecent(site) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            contentColor = TextPrimary,
                            focusedContainerColor = LocalAccent.current,
                            focusedContentColor = Color.White,
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(site.title.ifBlank { site.url }, maxLines = 1)
                            if (site.title.isNotBlank()) {
                                Text(site.url, color = TextMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
