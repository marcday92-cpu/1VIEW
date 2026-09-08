package com.iptv.tv.ui.multi

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.R
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.player.MultiViewPool
import com.iptv.tv.player.TileStatus
import com.iptv.tv.ui.KeepAwake
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.ui.web.BrowserHost
import com.iptv.tv.ui.web.BrowserPersistence
import com.iptv.tv.ui.web.WebPointerOverlay
import com.iptv.tv.ui.web.WebPointerState
import com.iptv.tv.ui.web.WebViewModel
import kotlinx.coroutines.delay

@Composable
fun MultiScreen(
    viewModel: MultiViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.tileStatus.collectAsStateWithLifecycle()
    val playerGeneration by viewModel.pool.generation.collectAsStateWithLifecycle()
    val pickerChannels by viewModel.pickerChannels.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    var browsingWeb by remember { mutableStateOf<Int?>(null) }
    val pointer = remember { WebPointerState() }
    var webHost by remember { mutableStateOf<BrowserHost?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }
    val layoutFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    KeepAwake()
    val lineLabels by viewModel.lineLabels.collectAsStateWithLifecycle()
    val webView = webHost?.webView
    val webFocused = state.webSlotIndex != null &&
        (browsingWeb != null || state.activeSlot == state.webSlotIndex)

    LaunchedEffect(state.ready) { if (state.ready) viewModel.onEnter() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Fire TV Home over a Multi grid: release every tile decoder; restart them on return.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    webHost?.webView?.onPause()
                    viewModel.onLeave()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    webHost?.webView?.onResume()
                    viewModel.onEnter()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            BrowserPersistence.flush()
            viewModel.onLeave()
        }
    }

    // Live tiles keep decoding while the Web tile is in use; only the audio follows the
    // active tile. (An earlier build released the live decoders here; on the 4K stick that
    // is not needed and it read as the channel stopping.)
    LaunchedEffect(webFocused, webView) {
        val wv = webView ?: return@LaunchedEffect
        wv.onResume()
        if (webFocused) {
            delay(200)
            wv.setMuted(false)
            wv.tryPlay()
        } else {
            wv.setMuted(true)
            wv.pauseMedia()
        }
    }

    LaunchedEffect(state.limitNote) {
        if (state.limitNote != null) {
            delay(14_000)
            viewModel.clearLimitNote()
        }
    }

    val overlayOpen = state.pickerForSlot != null || state.menuForSlot != null
    LaunchedEffect(chrome, overlayOpen, browsingWeb) {
        if (chrome && !overlayOpen && browsingWeb == null) {
            delay(5_000)
            runCatching { gridFocus.requestFocus() }
            chrome = false
        }
    }

    BackHandler {
        when {
            state.pickerForSlot != null -> viewModel.closePicker()
            state.menuForSlot != null -> viewModel.closeMenu()
            browsingWeb != null -> {
                if (webHost?.isFullscreen == true) {
                    webHost?.hideCustomView()
                } else {
                    pointer.hide()
                    webView?.setBrowsePage(false)
                    browsingWeb = null
                }
            }
            chrome -> onExit()
            else -> chrome = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (overlayOpen) return@onPreviewKeyEvent false
                if (browsingWeb != null) {
                    if (webHost?.isFullscreen == true) {
                        return@onPreviewKeyEvent webHost?.handleFullscreenKey(native) == true
                    }
                    // One BACK always leaves the page. Left to the WebView, BACK walks the page's
                    // own history first, which on a site like DeeTV feels like being stuck.
                    if (native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (native.action == KeyEvent.ACTION_UP) {
                            pointer.hide()
                            webView?.setBrowsePage(false)
                            browsingWeb = null
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == KeyEvent.KEYCODE_MENU ||
                        native.keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU
                    ) {
                        if (native.action == KeyEvent.ACTION_UP) {
                            pointer.hide()
                            browsingWeb = null
                        }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent pointer.onKey(native, webView)
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                if (!chrome && native.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    state.activeSlot in topRowSlots(state.layout)
                ) {
                    chrome = true
                    true
                } else false
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { canFocus = !overlayOpen },
        ) {
            TileGrid(
                state = state,
                status = status,
                pool = viewModel.pool,
                playerGeneration = playerGeneration,
                lineLabels = lineLabels,
                slotNotices = state.slotNotices,
                // Only the top row hands UP to the layout bar; lower rows move up a row.
                topRowUp = if (chrome) layoutFocus else FocusRequester.Cancel,
                interactive = !overlayOpen && browsingWeb == null,
                browsingWeb = browsingWeb,
                webError = webError,
                activeFocusRequester = gridFocus,
                onFocus = viewModel::setActive,
                onSelect = { slot ->
                    when (val tile = state.slots.getOrNull(slot)) {
                        null -> viewModel.openPicker(slot)
                        is MultiSlot.Web -> {
                            browsingWeb = slot
                            pointer.showCentered()
                            webView?.onResume()
                            webView?.setMuted(false)
                            webView?.tryPlay()
                        }
                        is MultiSlot.Live -> viewModel.openMenu(slot)
                    }
                },
                onOptions = { slot ->
                    pointer.hide()
                    browsingWeb = null
                    viewModel.openMenu(slot)
                },
                onWebReady = { host ->
                    webHost = host
                    host?.webView?.onResume()
                    if (webFocused) {
                        host?.webView?.setMuted(false)
                        host?.webView?.tryPlay()
                    }
                },
                onWebFailed = { webError = it },
                pointer = pointer,
            )
        }

        if (chrome && browsingWeb == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.focusProperties { down = gridFocus },
                ) {
                    MultiLayout.values().forEach { option ->
                        val isCurrent = option == state.layout
                        Surface(
                            onClick = { viewModel.setLayout(option) },
                            modifier = if (option == MultiLayout.values().first()) {
                                Modifier.focusRequester(layoutFocus)
                            } else Modifier,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isCurrent) LocalAccent.current.copy(alpha = 0.35f)
                                else Color.White.copy(alpha = 0.07f),
                                contentColor = TextPrimary,
                                focusedContainerColor = LocalAccent.current,
                                focusedContentColor = Color.White,
                                pressedContainerColor = LocalAccent.current,
                                pressedContentColor = Color.White,
                            ),
                        ) {
                            Text(
                                option.label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("BACK leaves multi  •  OK changes channel / browses Web", color = TextMuted, fontSize = 12.sp)
                }
                Text(
                    MULTI_LIMIT_NOTE,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            LaunchedEffect(chrome) { runCatching { layoutFocus.requestFocus() } }
        }

        if (browsingWeb != null) {
            Text(
                "Cursor on page  ·  hold arrows to speed up  ·  BACK returns to the grid",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        state.limitNote?.let { note ->
            Text(
                note,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6B45309))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    state.pickerForSlot?.let { slot ->
        ChannelPicker(
            slot = slot,
            filter = state.filter,
            rails = state.pickerRails,
            selectedRailId = state.pickerRailId,
            channels = pickerChannels,
            webBlocked = viewModel.webBlockedOn(slot),
            webOccupiedSlot = state.webSlotIndex,
            featuredSite = viewModel.edition.featuredSite,
            onSelectRail = viewModel::selectPickerRail,
            onFilter = viewModel::setFilter,
            onPick = { viewModel.assign(slot, it) },
            onPickWeb = { url, title -> viewModel.assignWeb(slot, url, title) },

            onWebBlocked = viewModel::showWebLimit,
            onDismiss = viewModel::closePicker,
        )
    }

    state.menuForSlot?.let { slot ->
        val tile = state.slots.getOrNull(slot)
        TileMenu(
            title = tile?.label ?: "Screen",
            isWeb = tile is MultiSlot.Web,
            onChange = { viewModel.openPicker(slot) },
            onChangeWeb = { viewModel.openPicker(slot, WEB_RAIL_ID) },
            onFullscreen = {
                if (tile is MultiSlot.Web) {
                    viewModel.closeMenu()
                    onOpenWeb(tile.url)
                } else {
                    viewModel.fullscreen(slot)
                }
            },
            onRetry = { viewModel.retry(slot); viewModel.closeMenu() },
            onClear = {
                webError = null
                viewModel.clear(slot)
            },
            onDismiss = viewModel::closeMenu,
        )
    }
}

@Composable
private fun TileGrid(
    state: MultiUiState,
    status: Map<Int, TileStatus>,
    pool: MultiViewPool,
    playerGeneration: Int,
    lineLabels: Map<Int, String>,
    slotNotices: Map<Int, String>,
    topRowUp: FocusRequester,
    interactive: Boolean,
    browsingWeb: Int?,
    webError: String?,
    activeFocusRequester: FocusRequester,
    onFocus: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onOptions: (Int) -> Unit,
    onWebReady: (BrowserHost?) -> Unit,
    onWebFailed: (String) -> Unit,
    pointer: WebPointerState,
) {
    val topRow = topRowSlots(state.layout)
    val tile: @Composable (Int, Modifier) -> Unit = { slot, modifier ->
        Tile(
            slot = slot,
            content = state.slots.getOrNull(slot),
            isActive = state.activeSlot == slot,
            buffering = status[slot]?.buffering == true,
            error = status[slot]?.error,
            pool = pool,
            playerGeneration = playerGeneration,
            lineLabels = lineLabels,
            slotNotices = slotNotices,
            interactive = interactive || browsingWeb == slot,
            browsing = browsingWeb == slot,
            webError = webError,
            activeFocusRequester = if (state.activeSlot == slot) activeFocusRequester else null,
            onFocus = { onFocus(slot) },
            onSelect = { onSelect(slot) },
            onOptions = { onOptions(slot) },
            onWebReady = onWebReady,
            onWebFailed = onWebFailed,
            pointer = pointer,
            modifier = modifier.focusProperties { up = if (slot in topRow) topRowUp else FocusRequester.Default },
        )
    }

    when (state.layout) {
        MultiLayout.TWO -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            tile(0, Modifier.weight(1f).fillMaxHeight())
            tile(1, Modifier.weight(1f).fillMaxHeight())
        }

        MultiLayout.THREE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            tile(0, Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                tile(1, Modifier.weight(1f).fillMaxWidth())
                tile(2, Modifier.weight(1f).fillMaxWidth())
            }
        }

        MultiLayout.FOUR -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(2, Modifier.weight(1f).fillMaxHeight())
                tile(3, Modifier.weight(1f).fillMaxHeight())
            }
        }

        MultiLayout.SIX -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                tile(1, Modifier.weight(1f).fillMaxHeight())
                tile(2, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                tile(3, Modifier.weight(1f).fillMaxHeight())
                tile(4, Modifier.weight(1f).fillMaxHeight())
                tile(5, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Tile(
    slot: Int,
    content: MultiSlot?,
    isActive: Boolean,
    buffering: Boolean,
    error: String?,
    pool: MultiViewPool,
    playerGeneration: Int,
    lineLabels: Map<Int, String>,
    slotNotices: Map<Int, String>,
    interactive: Boolean,
    browsing: Boolean,
    webError: String?,
    activeFocusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onSelect: () -> Unit,
    onOptions: () -> Unit,
    onWebReady: (BrowserHost?) -> Unit,
    onWebFailed: (String) -> Unit,
    pointer: WebPointerState,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val focusRequester = remember { FocusRequester() }
    val requester = activeFocusRequester ?: focusRequester
    LaunchedEffect(interactive, isActive, browsing) {
        if ((interactive || browsing) && isActive) runCatching { requester.requestFocus() }
    }

    var heldOk by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        enabled = interactive || browsing,
        modifier = modifier
            .focusRequester(requester)
            .focusProperties { canFocus = interactive || browsing }
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { event ->
                if (browsing) return@onPreviewKeyEvent false
                if (!interactive) return@onPreviewKeyEvent true
                val native = event.nativeKeyEvent
                // A held OK opens the options; the key-up that follows must not also count as
                // the click that starts browsing or the channel picker.
                if (event.key == Key.DirectionCenter && native.action == KeyEvent.ACTION_UP && heldOk) {
                    heldOk = false
                    return@onPreviewKeyEvent true
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val isOptions = event.key == Key.Menu ||
                    native.keyCode == KeyEvent.KEYCODE_INFO ||
                    (event.key == Key.DirectionCenter && native.isLongPress)
                if (isOptions) {
                    if (event.key == Key.DirectionCenter) heldOk = true
                    onOptions()
                    true
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            contentColor = TextPrimary,
            focusedContainerColor = Color.Black,
            focusedContentColor = TextPrimary,
            pressedContainerColor = Color.Black,
            pressedContentColor = TextPrimary,
            disabledContainerColor = Color.Black,
            disabledContentColor = TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    if (isActive) 2.dp else 1.dp,
                    if (isActive) accent else Color.White.copy(alpha = 0.12f),
                ),
                shape = RoundedCornerShape(10.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(3.dp, accent),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Only a buffering spell that drags on gets the "Connecting" notice; the quick
            // reconnect after a provider cut should look like a brief pause, not a black screen.
            var showConnecting by remember { mutableStateOf(false) }
            LaunchedEffect(buffering) {
                if (!buffering) { showConnecting = false; return@LaunchedEffect }
                delay(2_500)
                showConnecting = true
            }
            when (content) {
                is MultiSlot.Live -> {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.multi_tile_player, null) as PlayerView)
                                .apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    player = pool.playerFor(slot)
                                    // A reconnect resets the player; keep the last frame up
                                    // rather than dropping to black for the second it takes.
                                    setKeepContentOnPlayerReset(true)
                                    keepScreenOn = true
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    isClickable = false
                                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                }
                        },
                        update = { view ->
                            // Reading playerGeneration here re-runs this block when the pool
                            // recreates players (Home and back), so the view never keeps a released one.
                            @Suppress("UNUSED_VARIABLE") val rebind = playerGeneration
                            runCatching {
                                val wanted = pool.playerFor(slot)
                                if (view.player !== wanted) view.player = wanted
                            }
                        },
                        onRelease = { view -> runCatching { view.player = null } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    val viaLine = lineLabels[slot]
                    slotNotices[slot]?.let { TileNotice(it, Alignment.TopCenter) }
                    TileCaption(
                        if (viaLine != null) "${content.channel.name}  ·  via $viaLine" else content.channel.name,
                        isActive,
                        if (isActive) "OK to change channel" else null,
                    )
                    when {
                        error != null -> TileNotice(error, Alignment.Center)
                        showConnecting -> TileNotice("Connecting…", Alignment.Center)
                    }
                }
                is MultiSlot.Web -> {
                    MultiWebTile(
                        url = content.url,
                        muted = !isActive,
                        browsing = browsing,
                        onReady = onWebReady,
                        onFailed = onWebFailed,
                    )
                    WebPointerOverlay(pointer = pointer, enabled = browsing)
                    TileCaption(
                        content.label,
                        isActive,
                        if (browsing) "Arrows move cursor  ·  at the edge the page scrolls  ·  OK clicks  ·  BACK returns to the grid"
                        else "OK to browse  ·  Hold OK or Menu: options",
                    )
                    webError?.let { TileNotice(it, Alignment.Center) }
                }
                null -> {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("+", color = TextSecondary, fontSize = 30.sp)
                        Text("Add a channel or a Web page", color = TextSecondary, fontSize = 13.sp)
                        Text(
                            "OK to choose",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TileCaption(title: String, isActive: Boolean, hint: String?) {
    val accent = LocalAccent.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (hint != null) {
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Spacer(Modifier)
            }
            if (isActive) {
                Text("♪", color = accent, fontSize = 16.sp)
            }
        }
        Text(
            title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Mutable holder so the WebViewClient sees the *current* mute state, not the one at creation. */
private class WebTileFlags(@Volatile var muted: Boolean, @Volatile var loadedUrl: String? = null)

@Composable
private fun MultiWebTile(
    url: String,
    muted: Boolean,
    browsing: Boolean,
    onReady: (BrowserHost?) -> Unit,
    onFailed: (String) -> Unit,
) {
    val flags = remember { WebTileFlags(muted) }
    flags.muted = muted
    AndroidView(
        factory = { ctx ->
            runCatching {
                BrowserHost(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useWindowOverlay = false
                    webView.applyUserAgent(WebViewModel.DESKTOP_UA)
                    webView.setBrowsePage(false)
                    webView.isFocusable = false
                    webView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            webView.injectMediaHelpers()
                        }
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            webView.injectMediaHelpers()
                            webView.setMuted(flags.muted)
                            if (!flags.muted) webView.tryPlay()
                            BrowserPersistence.flush()
                        }
                    }
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                            showCustomView(view, callback)
                        }
                        override fun onHideCustomView() {
                            hideCustomView()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onShowCustomView(
                            view: android.view.View?,
                            requestedOrientation: Int,
                            callback: CustomViewCallback?,
                        ) {
                            onShowCustomView(view, callback)
                        }
                    }
                    webView.onResume()
                    flags.loadedUrl = url
                    webView.loadUrl(url)
                    onReady(this)
                }
            }.getOrElse {
                onFailed("Can't play Web next to live tiles on this stick. Menu → Open in Browser.")
                FrameLayout(ctx)
            }
        },
        update = { view ->
            if (view is BrowserHost) {
                view.setBrowsePage(browsing)
                // Only a *new* tile address reloads; in-page navigation and redirects are the user's.
                if (flags.loadedUrl != url) {
                    flags.loadedUrl = url
                    view.webView.loadUrl(url)
                }
            }
        },
        onRelease = { view ->
            if (view is BrowserHost) {
                view.releaseMedia()
                view.webView.destroy()
            }
            onReady(null)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun TileNotice(text: String, alignment: Alignment) {
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ChannelPicker(
    slot: Int,
    filter: String,
    rails: List<MultiPickerRail>,
    selectedRailId: String,
    channels: List<Channel>,
    webBlocked: Boolean,
    webOccupiedSlot: Int?,
    featuredSite: com.iptv.tv.edition.FeaturedSite?,
    onSelectRail: (String) -> Unit,
    onFilter: (String) -> Unit,
    onPick: (Channel) -> Unit,
    onPickWeb: (String, String) -> Unit,
    onWebBlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    var showFilter by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    val webRail = selectedRailId == WEB_RAIL_ID

    LaunchedEffect(showFilter, webRail, channels.firstOrNull()?.streamId) {
        delay(60)
        if (webRail) {
            runCatching { firstItem.requestFocus() }
        } else if (showFilter) {
            runCatching { filterFocus.requestFocus() }
        } else if (channels.isNotEmpty()) {
            runCatching { firstItem.requestFocus() }
        } else {
            runCatching { filterFocus.requestFocus() }
        }
    }

    TvModal(onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(620.dp)
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C22))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                Text(
                    "Choose for screen ${slot + 1}",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    MULTI_LIMIT_NOTE,
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rails, key = { it.id }) { rail ->
                        val selected = rail.id == selectedRailId
                        Surface(
                            onClick = { onSelectRail(rail.id) },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) LocalAccent.current.copy(alpha = 0.3f)
                                else Color.White.copy(alpha = 0.06f),
                                contentColor = TextPrimary,
                                focusedContainerColor = LocalAccent.current,
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Text(
                                rail.label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (webRail) {
                    if (webBlocked) {
                        Text(
                            MULTI_SECOND_WEB.format((webOccupiedSlot ?: 0) + 1),
                            color = Color(0xFFFFC107),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onWebBlocked, modifier = Modifier.focusRequester(firstItem)) {
                            Text("Web already in use")
                        }
                    } else {
                        Text("Web pages use the same login as the Web tab.", color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        if (featuredSite != null) {
                            Button(
                                onClick = { onPickWeb(featuredSite.url, featuredSite.label) },
                                modifier = Modifier.fillMaxWidth().focusRequester(firstItem),
                            ) { Text(featuredSite.label) }
                            Spacer(Modifier.height(8.dp))
                            TvTextField(urlText, { urlText = it }, "Web address, e.g. bbc.co.uk", Modifier.fillMaxWidth())
                        } else {
                            TvTextField(
                                urlText,
                                { urlText = it },
                                "Web address, e.g. bbc.co.uk",
                                Modifier.fillMaxWidth().focusRequester(firstItem),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onPickWeb(urlText, urlText) }) { Text("Open address") }
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    if (showFilter) {
                        TvTextField(
                            filter,
                            onFilter,
                            "Type to filter",
                            Modifier.fillMaxWidth().focusRequester(filterFocus),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showFilter = false; onFilter("") }) { Text("Show all") }
                    } else {
                        Button(
                            onClick = { showFilter = true },
                            modifier = Modifier.focusRequester(filterFocus),
                        ) { Text("Filter list") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (channels.isEmpty()) {
                        Text("No channels match.", color = TextMuted)
                    }
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, channel ->
                            Surface(
                                onClick = {
                                    onPick(channel)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstItem) else Modifier),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.05f),
                                    contentColor = TextPrimary,
                                    focusedContainerColor = LocalAccent.current,
                                    focusedContentColor = Color.White,
                                    pressedContainerColor = LocalAccent.current,
                                    pressedContentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    channel.name,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Up / down to move  •  OK to pick  •  BACK to cancel", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TileMenu(
    title: String,
    isWeb: Boolean,
    onChange: () -> Unit,
    onChangeWeb: () -> Unit,
    onFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    TvModal(onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C22))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Button(onClick = onChange, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                    Text(if (isWeb) "Put a channel here instead" else "Change channel")
                }
                if (isWeb) {
                    Button(onClick = onChangeWeb, modifier = Modifier.fillMaxWidth()) { Text("Change web page") }
                }
                Button(onClick = onFullscreen, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isWeb) "Open in Browser" else "Watch fullscreen")
                }
                if (!isWeb) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                }
                Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Remove from grid") }
                if (isWeb) {
                    Text(
                        "The page plays video while this screen is highlighted; the other screens keep playing. If it stays black, Open in Browser uses the same login.",
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Press BACK to close", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

private val GAP = 3.dp

/** Slots on the top row of a layout: UP from these opens the layout bar. */
private fun topRowSlots(layout: MultiLayout): Set<Int> = when (layout) {
    MultiLayout.TWO, MultiLayout.THREE, MultiLayout.FOUR -> setOf(0, 1)
    MultiLayout.SIX -> setOf(0, 1, 2)
}
