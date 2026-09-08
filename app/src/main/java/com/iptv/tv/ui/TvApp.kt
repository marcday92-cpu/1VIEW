package com.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.AppFeature
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackSession
import com.iptv.tv.service.ReminderReceiver
import com.iptv.tv.ui.catalog.MoviesScreen
import com.iptv.tv.ui.catalog.SeriesScreen
import com.iptv.tv.ui.catchup.CatchupScreen
import com.iptv.tv.ui.guide.GuideScreen
import com.iptv.tv.ui.home.HomeScreen
import com.iptv.tv.ui.live.LiveScreen
import com.iptv.tv.ui.live.LiveViewModel
import com.iptv.tv.ui.multi.MultiScreen
import com.iptv.tv.ui.player.PlayerScreen
import com.iptv.tv.ui.profile.ProfilePickerScreen
import com.iptv.tv.ui.recordings.RecordingsScreen
import com.iptv.tv.ui.search.SearchScreen
import com.iptv.tv.ui.search.VoiceSearchEntryPoint
import com.iptv.tv.ui.search.VoiceSearchRequest
import com.iptv.tv.ui.settings.SettingsScreen
import com.iptv.tv.ui.theme.Charcoal
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TvTheme
import com.iptv.tv.ui.web.WebLaunch
import com.iptv.tv.ui.web.WebScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay

private data class MainTab(val title: String, val route: String)

/** Tabs the user can reach, in order. Home is always first; the rest follow Manage features. */
private fun mainTabs(hasIptvLogin: Boolean, enabled: Set<AppFeature>) = buildList {
    add(MainTab("Home", "home"))
    if (AppFeature.LIVE in enabled) add(MainTab("Live", "live"))
    if (hasIptvLogin && AppFeature.CATCHUP in enabled) add(MainTab("Catch-up", "catchup"))
    if (AppFeature.MOVIES in enabled) add(MainTab("Movies", "movies"))
    if (AppFeature.SERIES in enabled) add(MainTab("Series", "series"))
    if (AppFeature.WEB in enabled) add(MainTab("Web", "web"))
    if (AppFeature.GUIDE in enabled) add(MainTab("Guide", "guide"))
    if (AppFeature.MULTI in enabled) add(MainTab("Multi", "multi"))
    if (hasIptvLogin && AppFeature.RECORDINGS in enabled) add(MainTab("Recordings", "recordings"))
}

/** Routes that always exist regardless of feature toggles. */
private val ALWAYS_ROUTES = setOf("home", "settings", "search")

@Composable
fun TvApp(
    deepLinkIntent: Intent?,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val enabledFeatures by viewModel.enabledFeatures.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playback = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, PlaybackEntryPoint::class.java)
            .playbackController()
    }
    val session by playback.session.collectAsStateWithLifecycle()
    val liveBrowse by playback.liveBrowse.collectAsStateWithLifecycle()
    val fullscreenPlayer = session != null && (session?.type != ContentType.LIVE || !liveBrowse)
    val nav = rememberNavController()
    val liveVm: LiveViewModel = hiltViewModel()
    var lastReturn by remember { mutableStateOf<PlaybackSession?>(null) }
    val voice = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, VoiceSearchEntryPoint::class.java)
            .voiceSearch()
    }
    var pendingVoice by remember { mutableStateOf<VoiceSearchRequest?>(null) }
    var navReady by remember { mutableStateOf(false) }

    // Fire TV Home / sleep: stop decoding and counting watch time; resume on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playback.onAppBackgrounded()
                Lifecycle.Event.ON_START -> playback.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        voice.requests.collect { request ->
            voice.consumed()
            lastReturn = null
            if (playback.session.value != null) playback.close()
            pendingVoice = request
        }
    }

    LaunchedEffect(deepLinkIntent) {
        val streamId = deepLinkIntent?.getIntExtra(ReminderReceiver.EXTRA_CHANNEL_STREAM_ID, -1) ?: -1
        if (streamId > 0 && AppFeature.LIVE in enabledFeatures) viewModel.handleWatchNow(streamId)
    }

    fun openReturnTab(route: String) {
        if (!navReady) return
        runCatching {
            nav.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo("home") { saveState = true }
            }
        }
    }

    var pendingLiveReveal by remember { mutableStateOf<PlaybackSession?>(null) }

    fun revealLiveFrom(session: PlaybackSession?) {
        if (session == null) return
        val rail = session.liveRailId
            ?: session.channel?.categoryId?.takeIf { it.isNotBlank() }?.let { "cat_$it" }
            ?: return
        liveVm.selectRail(rail, clearChannel = false)
        liveVm.selectChannel(session.streamId, requestFocus = true)
    }

    LaunchedEffect(fullscreenPlayer) {
        if (fullscreenPlayer) return@LaunchedEffect
        val pending = pendingLiveReveal ?: return@LaunchedEffect
        pendingLiveReveal = null
        revealLiveFrom(pending)
    }

    LaunchedEffect(session) {
        val current = session
        if (current != null) {
            lastReturn = current
            return@LaunchedEffect
        }
        val closed = lastReturn ?: return@LaunchedEffect
        lastReturn = null
        if (closed.type == ContentType.LIVE) revealLiveFrom(closed)
        val route = closed.returnRoute.takeIf { it in ALWAYS_ROUTES || routeEnabled(it, enabledFeatures) } ?: "home"
        if (nav.currentBackStackEntry?.destination?.route != route) {
            openReturnTab(route)
        }
    }

    TvTheme(accent = Color(state.accent)) {
        when {
            state.checking -> Box(Modifier.fillMaxSize().background(Charcoal))
            !state.profileChosen -> ProfilePickerScreen(
                profiles = profiles,
                onSelect = viewModel::onProfileSelected,
                onCreate = { name ->
                    viewModel.createProfile(name)
                },
                onRename = viewModel::renameProfile,
            )
            else -> Box(Modifier.fillMaxSize()) {
                MainShell(
                    nav = nav,
                    liveVm = liveVm,
                    activeProfile = activeProfile,
                    onProfile = viewModel::showProfilePicker,
                    onLogout = viewModel::logout,
                    onLoggedIn = viewModel::onLoggedIn,
                    pendingVoice = pendingVoice,
                    onVoiceConsumed = { pendingVoice = null },
                    playerOpen = fullscreenPlayer,
                    startTab = state.startTab,
                    hasIptvLogin = state.providerConnected,
                    enabled = enabledFeatures,
                    onNavReady = { navReady = true },
                )
                if (fullscreenPlayer) {
                    PlayerScreen(
                        onGuide = {
                            lastReturn = null
                            openReturnTab(if (AppFeature.GUIDE in enabledFeatures) "guide" else "live")
                        },
                        onCollapseLive = {
                            // The reveal (scroll + focus the playing channel) waits until the
                            // player overlay is gone; while it is up nothing below can take focus.
                            pendingLiveReveal = playback.session.value
                            playback.setLiveBrowse(true)
                            openReturnTab("live")
                        },
                    )
                }
            }
        }
    }
}

/** How many frames (about a second) a focus hand-off keeps retrying before giving up. */
private const val FOCUS_RETRY_FRAMES = 60

private fun routeEnabled(route: String, enabled: Set<AppFeature>): Boolean = when (route) {
    "live" -> AppFeature.LIVE in enabled
    "catchup" -> AppFeature.CATCHUP in enabled
    "movies" -> AppFeature.MOVIES in enabled
    "series" -> AppFeature.SERIES in enabled
    "web" -> AppFeature.WEB in enabled
    "guide" -> AppFeature.GUIDE in enabled
    "multi" -> AppFeature.MULTI in enabled
    "recordings" -> AppFeature.RECORDINGS in enabled
    "search" -> AppFeature.SEARCH in enabled
    else -> true
}

@Composable
private fun ConsumeBack(enabled: Boolean) {
    BackHandler(enabled = enabled) { }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PlaybackEntryPoint {
    fun playbackController(): PlaybackController
}

@Composable
private fun MainShell(
    nav: androidx.navigation.NavHostController,
    liveVm: LiveViewModel,
    activeProfile: Profile?,
    onProfile: () -> Unit,
    onLogout: () -> Unit,
    onLoggedIn: () -> Unit,
    pendingVoice: VoiceSearchRequest? = null,
    onVoiceConsumed: () -> Unit = {},
    playerOpen: Boolean = false,
    startTab: String = "home",
    hasIptvLogin: Boolean = false,
    enabled: Set<AppFeature> = AppFeature.entries.toSet(),
    onNavReady: () -> Unit = {},
) {
    val tabs = mainTabs(hasIptvLogin, enabled)
    val tabFocus = remember(tabs) { List(tabs.size) { FocusRequester() } }
    val searchGlyphFocus = remember { FocusRequester() }
    val settingsButtonFocus = remember { FocusRequester() }
    val activity = LocalContext.current as? Activity
    var confirmExit by remember { mutableStateOf(false) }
    var swallowBack by remember { mutableStateOf(false) }
    var hadPlayer by remember { mutableStateOf(false) }
    var searchBoxNonce by remember { mutableIntStateOf(0) }
    val contentFocus = remember { FocusRequester() }
    // True while any control in the app holds focus (the root Column reports ActiveParent).
    val focusPresentState = remember { mutableStateOf(false) }
    var focusPresent by focusPresentState
    // True while the focused control is inside the page area (not the top bar).
    val contentHasFocusState = remember { mutableStateOf(false) }
    var contentHasFocus by contentHasFocusState
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val tabIndex = tabs.indexOfFirst { it.route == route }
    val inSettings = route == "settings"
    val onSearch = route == "search"
    val searchEnabled = AppFeature.SEARCH in enabled
    val accent = LocalAccent.current
    val tabColors = TabDefaults.pillIndicatorTabColors(
        contentColor = TextMuted,
        inactiveContentColor = TextMuted,
        selectedContentColor = TextPrimary,
        focusedContentColor = accent,
        focusedSelectedContentColor = TextPrimary,
    )

    LaunchedEffect(Unit) { onNavReady() }

    fun openTab(title: String) {
        val dest = when (title.lowercase()) {
            "search" -> "search"
            "multiview", "multi" -> "multi"
            else -> tabs.firstOrNull { it.title.equals(title, ignoreCase = true) }?.route
                ?: title.lowercase()
        }
        runCatching {
            nav.navigate(dest) {
                launchSingleTop = true
                restoreState = true
                popUpTo("home") { saveState = true }
            }
        }
    }

    fun openTypedSearch() {
        searchBoxNonce += 1
        openTab("Search")
    }

    LaunchedEffect(playerOpen) {
        if (playerOpen) {
            hadPlayer = true
            swallowBack = true
            confirmExit = false
        } else if (hadPlayer) {
            // Swallow a repeated BACK from the remote for a moment so it cannot exit the app.
            swallowBack = true
            // When the player's focused control is disposed, Android re-focuses the window from
            // the top (the avatar) in the same frame. Put the remote back on the page unless the
            // page already placed it (Live revealing the playing channel).
            // Retry per frame: the page's lazy lists are measured a frame or two later.
            repeat(FOCUS_RETRY_FRAMES) {
                withFrameNanos { }
                if (contentHasFocus) return@repeat
                runCatching { contentFocus.requestFocus() }
            }
            delay(500)
            swallowBack = false
        }
    }

    // Focus fence: whenever nothing in the app holds focus while a page is visible, put the remote
    // back on that page. Covers cold start (rails arrive after the first request), lists whose
    // focused row disappears, and any screen that forgets to request focus. The player, the
    // Web tab (the WebView owns key events) and modal windows are left alone.
    LaunchedEffect(Unit) {
        snapshotFlow { !focusPresent && windowFocused && !playerOpen && route != null && route != "web" }
            .collectLatest { vacant ->
                if (!vacant) return@collectLatest
                repeat(FOCUS_RETRY_FRAMES) {
                    withFrameNanos { }
                    if (focusPresent) return@collectLatest
                    runCatching { contentFocus.requestFocus() }
                }
            }
    }

    LaunchedEffect(pendingVoice) {
        val request = pendingVoice ?: return@LaunchedEffect
        if (!searchEnabled) {
            onVoiceConsumed()
            return@LaunchedEffect
        }
        if (request.openTypeBox) openTypedSearch() else openTab("Search")
    }

    // A section switched off while it is on screen (or a login that goes away) sends the user Home.
    LaunchedEffect(hasIptvLogin, route, enabled) {
        val current = route ?: return@LaunchedEffect
        val stillAllowed = when {
            current == "recordings" -> hasIptvLogin && AppFeature.RECORDINGS in enabled
            current == "catchup" -> hasIptvLogin && AppFeature.CATCHUP in enabled
            current in ALWAYS_ROUTES -> current != "search" || searchEnabled
            else -> routeEnabled(current, enabled)
        }
        if (!stillAllowed) openTab("Home")
    }

    // Visible screen owns the highlight. After a real navigation, put the remote
    // on that page — not a leftover tab.
    LaunchedEffect(backStackEntry) {
        val entry = backStackEntry ?: return@LaunchedEffect
        if (playerOpen) return@LaunchedEffect
        // Not before the page is RESUMED: until then the previous page is still composed and the
        // NavHost's focus restorer would hand focus back to it, only for that node to be disposed.
        entry.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) { }
        // Retry per frame until the page holds focus: its lists are measured a frame or two
        // after they appear, and a one-shot request that lands too early is simply lost.
        // A page that already placed focus itself (Live revealing the playing channel) keeps it.
        repeat(FOCUS_RETRY_FRAMES) {
            withFrameNanos { }
            if (contentHasFocus) return@LaunchedEffect
            runCatching { contentFocus.requestFocus() }
        }
    }

    LaunchedEffect(confirmExit) {
        if (confirmExit) {
            delay(3000)
            confirmExit = false
        }
    }

    // Only reached once the NavHost has nothing left to pop, i.e. on Home.
    // Swallow leftover BACK after the player closes so it does not pop the
    // Live tab back to Home or confirm-exit.
    BackHandler(enabled = swallowBack) { }
    BackHandler(enabled = !swallowBack) {
        if (confirmExit) activity?.finish() else confirmExit = true
    }

    var pendingGuideNow by remember { mutableStateOf(false) }
    var webFullscreen by remember { mutableStateOf(false) }

    val multiImmersive = route == "multi"
    val hideTopBar = multiImmersive || webFullscreen

    LaunchedEffect(route) {
        if (route != "web") webFullscreen = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Charcoal)
            // Nothing under the fullscreen player may take focus, or OK/D-pad drive hidden UI.
            // Only ever *removes* focusability: an ancestor's focus properties override every
            // descendant's, so setting canFocus = true here would make lazy lists and the tab row
            // focusable containers that swallow the highlight.
            .focusProperties { if (playerOpen) canFocus = false }
            .onFocusChanged { focusPresent = it.hasFocus }
            .focusGroup(),
    ) {
        if (!hideTopBar) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onProfile,
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = TextPrimary,
                        focusedContainerColor = Color.White,
                        focusedContentColor = TextPrimary,
                    ),
                ) {
                    // The disc keeps the profile colour; focus shows as a white ring around it.
                    Box(
                        Modifier.padding(3.dp).size(40.dp).clip(CircleShape).background(
                            activeProfile?.let { Color(it.avatarColor) } ?: accent,
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text(activeProfile?.initials ?: "?") }
                }
                Spacer(Modifier.width(16.dp))
                if (searchEnabled) {
                    Surface(
                        onClick = { openTypedSearch() },
                        modifier = Modifier.focusRequester(searchGlyphFocus),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (onSearch) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                            contentColor = if (onSearch) TextPrimary else TextMuted,
                            focusedContainerColor = accent,
                            focusedContentColor = Color.White,
                        ),
                    ) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            SearchGlyph(if (onSearch) TextPrimary else TextMuted)
                        }
                    }
                }
                TabRow(
                    selectedTabIndex = tabIndex.coerceAtLeast(0),
                    containerColor = Color.Transparent,
                    contentColor = TextMuted,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    indicator = { tabPositions, isTabRowFocused ->
                        if (tabIndex >= 0 && tabIndex < tabPositions.size) {
                            TabRowDefaults.PillIndicator(
                                currentTabPosition = tabPositions[tabIndex],
                                doesTabRowHaveFocus = isTabRowFocused,
                                activeColor = Color.White.copy(alpha = 0.16f),
                                inactiveColor = Color.White.copy(alpha = 0.10f),
                            )
                        }
                    },
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = tabIndex == index,
                            onFocus = { },
                            modifier = Modifier.focusRequester(tabFocus[index]),
                            onClick = { openTab(tab.title) },
                            colors = tabColors,
                        ) {
                            Text(tab.title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                Surface(
                    onClick = { runCatching { nav.navigate("settings") { launchSingleTop = true } } },
                    modifier = Modifier.focusRequester(settingsButtonFocus),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (inSettings) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                        contentColor = if (inSettings) TextPrimary else TextMuted,
                        focusedContainerColor = accent,
                        focusedContentColor = Color.White,
                    ),
                ) {
                    Text("Settings", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        CompositionLocalProvider(LocalPageHasFocus provides contentHasFocusState) {
        NavHost(
            navController = nav,
            startDestination = if (startTab == "live" && AppFeature.LIVE in enabled) "live" else "home",
            // No crossfade: a page switch is instant on the remote and the outgoing page is
            // disposed at once instead of lingering (and stealing focus) for a 700 ms fade.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                // UP out of the page lands on the page's own tab (Live → Live, Movies → Movies)
                // instead of whichever top-bar control is geometrically nearest.
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Up) {
                            val target = when {
                                tabIndex in tabFocus.indices -> tabFocus[tabIndex]
                                inSettings -> settingsButtonFocus
                                onSearch && searchEnabled -> searchGlyphFocus
                                else -> null
                            }
                            target?.let { runCatching { it.requestFocus() } }
                        }
                    }
                }
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup()
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup(),
        ) {
            composable("home") {
                ConsumeBack(swallowBack)
                HomeScreen(
                    enabled = enabled,
                    hasIptvLogin = hasIptvLogin,
                    onPlayChannel = { streamId, railId -> liveVm.playFromHome(streamId, railId) },
                    onOpenLiveRail = { railId ->
                        liveVm.selectRail(railId)
                        openTab("Live")
                    },
                    onOpenGuide = { jumpToNow ->
                        pendingGuideNow = jumpToNow
                        openTab("Guide")
                    },
                    onOpenMovies = { openTab("Movies") },
                    onOpenSeries = { openTab("Series") },
                    onOpenSettings = { runCatching { nav.navigate("settings") { launchSingleTop = true } } },
                )
            }
            composable("live") {
                ConsumeBack(swallowBack)
                LiveScreen(viewModel = liveVm)
            }
            composable("catchup") {
                ConsumeBack(swallowBack)
                CatchupScreen()
            }
            composable("guide") {
                ConsumeBack(swallowBack)
                GuideScreen(
                    jumpToNowOnOpen = pendingGuideNow,
                    onJumpToNowConsumed = { pendingGuideNow = false },
                )
            }
            composable("multi") {
                ConsumeBack(swallowBack)
                MultiScreen(
                    onExit = { openTab("Live") },
                    onOpenWeb = { url ->
                        WebLaunch.pendingUrl = url
                        openTab("Web")
                    },
                )
            }
            composable("movies") {
                ConsumeBack(swallowBack)
                MoviesScreen()
            }
            composable("series") {
                ConsumeBack(swallowBack)
                SeriesScreen()
            }
            composable("search") {
                ConsumeBack(swallowBack)
                SearchScreen(
                    pendingVoice = pendingVoice,
                    onVoiceConsumed = onVoiceConsumed,
                    openTypeBoxNonce = searchBoxNonce,
                )
            }
            composable("recordings") {
                ConsumeBack(swallowBack)
                RecordingsScreen()
            }
            composable("web") {
                ConsumeBack(swallowBack)
                WebScreen(
                    onLeaveTab = { if (!nav.popBackStack()) openTab("Home") },
                    onFullscreen = { webFullscreen = it },
                )
            }
            composable("settings") {
                ConsumeBack(swallowBack)
                SettingsScreen(onLogout = onLogout, onLoggedIn = onLoggedIn)
            }
        }
        }
    }

    if (confirmExit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                "Press BACK again to exit",
                color = TextPrimary,
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE61C1C22))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SearchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val strokeWidth = 2.4.dp.toPx()
        val radius = size.minDimension * 0.32f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = color,
            start = Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
