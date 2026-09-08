package com.iptv.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iptv.tv.domain.model.AppFeature
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.ui.components.HomeChannelArt
import com.iptv.tv.ui.components.FreeBadge
import com.iptv.tv.domain.model.NowOnTvItem
import com.iptv.tv.domain.model.ReminderItem
import com.iptv.tv.domain.model.ResumeItem
import com.iptv.tv.ui.components.ProgressBar
import com.iptv.tv.ui.theme.Charcoal
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.util.formatClock
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.iptv.tv.ui.LocalPageHasFocus

private val HOME_POSTER_WIDTH = 200.dp
private const val HOME_POSTER_ASPECT = 16f / 9f
private val HOME_VOD_POSTER_WIDTH = 150.dp
private val SEE_ALL_LIVE = Modifier.width(HOME_POSTER_WIDTH).aspectRatio(HOME_POSTER_ASPECT)
private val SEE_ALL_VOD = Modifier.width(HOME_VOD_POSTER_WIDTH).aspectRatio(2f / 3f)
private val SEE_ALL_REMINDER = Modifier.width(HOME_POSTER_WIDTH).height(108.dp)

@Composable
fun HomeScreen(
    enabled: Set<AppFeature> = AppFeature.entries.toSet(),
    hasIptvLogin: Boolean = false,
    onPlayChannel: (streamId: Int, railId: String) -> Unit,
    onOpenLiveRail: (railId: String) -> Unit = {},
    onOpenGuide: (jumpToNow: Boolean) -> Unit = {},
    onOpenMovies: () -> Unit = {},
    onOpenSeries: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var continueMenu by remember { mutableStateOf<ResumeItem?>(null) }
    var continueAll by remember { mutableStateOf(false) }
    val liveOn = AppFeature.LIVE in enabled
    val moviesOn = AppFeature.MOVIES in enabled
    val seriesOn = AppFeature.SERIES in enabled
    val vodOn = moviesOn || seriesOn
    val liveRails = liveOn && (
        state.continueLive.isNotEmpty() || state.recentlyWatched.isNotEmpty() ||
            state.favourites.isNotEmpty() || state.mostWatched.isNotEmpty() || state.upcomingReminders.isNotEmpty()
        )
    val vodRails = (moviesOn && state.recentMovies.isNotEmpty()) || (seriesOn && state.recentSeries.isNotEmpty())
    val continueLive = if (liveOn) state.continueLive.distinctBy { it.channel.streamId } else emptyList()
    val continueVod = if (vodOn) state.continueWatching.distinctBy { it.contentId } else emptyList()
    val continueRail = continueLive.isNotEmpty() || continueVod.isNotEmpty()
    val nothingYet = !liveRails && !vodRails && !continueRail
    // Rails arrive after the page's first focus request on a cold start. When they do and the
    // remote is not on the page (nowhere, or bounced to the avatar), put it on the first card.
    val homeFocus = remember { FocusRequester() }
    val pageHasFocus by LocalPageHasFocus.current
    LaunchedEffect(nothingYet) {
        if (nothingYet) return@LaunchedEffect
        // Per frame for about a second: the rails are measured after they are composed.
        repeat(60) {
            withFrameNanos { }
            if (pageHasFocus) return@LaunchedEffect
            runCatching { homeFocus.requestFocus() }
        }
    }
    val update by viewModel.update.collectAsStateWithLifecycle()
    val updateDismissed by viewModel.updateDismissed.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
    val updateError by viewModel.updateError.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 16.dp)
            .focusRequester(homeFocus)
            .focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val pendingUpdate = update
        if (pendingUpdate != null && !updateDismissed) {
            item(key = "update") {
                UpdateBanner(
                    versionName = pendingUpdate.version.versionName,
                    notes = pendingUpdate.version.notes,
                    progress = updateProgress,
                    error = updateError,
                    onInstall = viewModel::installUpdate,
                    onLater = viewModel::dismissUpdate,
                )
            }
        }
        if (nothingYet) {
            item(key = "welcome") {
                HomeWelcome(
                    liveOn = liveOn,
                    vodOn = vodOn,
                    hasIptvLogin = hasIptvLogin,
                    busy = state.busy,
                    onOpenLive = { onOpenLiveRail("") },
                    onOpenMovies = onOpenMovies,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        if (continueRail) {
            item(key = "continue_header") {
                Text("CONTINUE WATCHING", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "continue_rail") {
                // Live programmes still on air lead, then films and episodes with a saved position.
                val entries: List<Any> = continueLive + continueVod
                // The live card arrives a moment after the film cards. If the remote was on the
                // first card when the list changed, keep it on the first card.
                fun keyOf(entry: Any): String = when (entry) {
                    is NowOnTvItem -> "live_${entry.channel.streamId}"
                    is ResumeItem -> entry.contentId
                    else -> entry.hashCode().toString()
                }
                val firstFocus = remember { FocusRequester() }
                var focusedKey by remember { mutableStateOf<String?>(null) }
                var previousFirstKey by remember { mutableStateOf<String?>(null) }
                val firstKey = entries.firstOrNull()?.let(::keyOf)
                LaunchedEffect(firstKey) {
                    val oldFirst = previousFirstKey
                    previousFirstKey = firstKey
                    if (firstKey == null || oldFirst == null || oldFirst == firstKey) return@LaunchedEffect
                    if (focusedKey != oldFirst) return@LaunchedEffect
                    // The new first card is measured a frame or two after it is composed.
                    repeat(30) {
                        withFrameNanos { }
                        if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    }
                }
                HomeRailRow(
                    items = entries,
                    key = ::keyOf,
                    showSeeAll = continueVod.size > HOME_RAIL_CAP,
                    onSeeAll = { continueAll = true },
                    seeAllModifier = SEE_ALL_LIVE,
                ) { entry ->
                    val entryKey = keyOf(entry)
                    val firstModifier = Modifier
                        .then(if (entry === entries.firstOrNull()) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = entryKey }
                    when (entry) {
                        is NowOnTvItem -> NowOnTvCard(entry, firstModifier) { onPlayChannel(entry.channel.streamId, "recent") }
                        is ResumeItem -> ContinueCard(
                            item = entry,
                            onClick = { viewModel.playContinue(entry) },
                            onOptions = { continueMenu = entry },
                            modifier = firstModifier,
                        )
                    }
                }
            }
        }

        if (liveOn && state.recentlyWatched.isNotEmpty()) {
            item(key = "recently_watched_header") {
                Text("RECENTLY WATCHED", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "recently_watched_rail") {
                val recent = state.recentlyWatched.distinctBy { it.streamId }
                HomeRailRow(
                    items = recent,
                    key = { "recent_${it.streamId}" },
                    showSeeAll = true,
                    onSeeAll = { onOpenLiveRail("recent") },
                    seeAllModifier = SEE_ALL_LIVE,
                ) { ch ->
                    ChannelCard(ch) { onPlayChannel(ch.streamId, "recent") }
                }
            }
        }

        if (liveOn && state.favourites.isNotEmpty()) {
            val favLabel = if (state.profileName.isNotBlank()) "${state.profileName.uppercase()}'S FAVOURITES" else "FAVOURITES"
            item(key = "favourites_header") {
                Text(favLabel, style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "favourites_rail") {
                val favs = state.favourites.distinctBy { it.streamId }
                HomeRailRow(
                    items = favs,
                    key = { it.streamId },
                    showSeeAll = true,
                    onSeeAll = { onOpenLiveRail("favourites") },
                    seeAllModifier = SEE_ALL_LIVE,
                ) { ch ->
                    ChannelCard(ch) { onPlayChannel(ch.streamId, "favourites") }
                }
            }
        }

        if (liveOn && state.mostWatched.isNotEmpty()) {
            item(key = "most_watched_header") {
                Text("MOST WATCHED", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "most_watched_rail") {
                val most = state.mostWatched.distinctBy { it.streamId }
                HomeRailRow(
                    items = most,
                    key = { it.streamId },
                    showSeeAll = true,
                    onSeeAll = { onOpenLiveRail("most_watched") },
                    seeAllModifier = SEE_ALL_LIVE,
                ) { ch ->
                    ChannelCard(ch) { onPlayChannel(ch.streamId, "most_watched") }
                }
            }
        }

        if (liveOn && state.upcomingReminders.isNotEmpty()) {
            item(key = "upcoming_reminders_header") {
                Text("UPCOMING REMINDERS", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "upcoming_reminders_rail") {
                HomeRailRow(
                    items = state.upcomingReminders,
                    key = { "rem_${it.id}" },
                    showSeeAll = true,
                    onSeeAll = { onOpenGuide(false) },
                    seeAllModifier = SEE_ALL_REMINDER,
                ) { item ->
                    ReminderCard(item) { viewModel.playReminder(item) }
                }
            }
        }

        if (moviesOn && state.recentMovies.isNotEmpty()) {
            item(key = "recently_added_movies_header") {
                Text("RECENTLY ADDED MOVIES", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "recently_added_movies_rail") {
                val movies = state.recentMovies.distinctBy { it.streamId }
                HomeRailRow(
                    items = movies,
                    key = { "vod_${it.streamId}" },
                    showSeeAll = true,
                    onSeeAll = onOpenMovies,
                    seeAllModifier = SEE_ALL_VOD,
                ) { item ->
                    PosterRailCard(item.posterUrl, item.name, item.year, free = item.isFree) { viewModel.playMovie(item) }
                }
            }
        }

        if (seriesOn && state.recentSeries.isNotEmpty()) {
            item(key = "recently_added_series_header") {
                Text("RECENTLY ADDED SERIES", style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
            item(key = "recently_added_series_rail") {
                val series = state.recentSeries.distinctBy { it.seriesId }
                HomeRailRow(
                    items = series,
                    key = { "ser_${it.seriesId}" },
                    showSeeAll = true,
                    onSeeAll = onOpenSeries,
                    seeAllModifier = SEE_ALL_VOD,
                ) { item ->
                    PosterRailCard(item.posterUrl, item.name, null, free = item.isFree) { viewModel.playSeries(item) }
                }
            }
        }

        item(key = "maintenance") {
            HomeMaintenanceRow(
                busy = state.busy,
                status = state.status,
                onRefresh = viewModel::refresh,
                onClearCache = viewModel::clearCache,
            )
        }
    }

    if (continueAll) {
        ContinueAllModal(
            items = state.continueWatching.distinctBy { it.contentId },
            onPlay = { item ->
                continueAll = false
                viewModel.playContinue(item)
            },
            onOptions = { continueMenu = it },
            onDismiss = { continueAll = false },
        )
    }

    continueMenu?.let { item ->
        val menuFocus = remember(item.contentId) { FocusRequester() }
        LaunchedEffect(item.contentId) {
            runCatching { menuFocus.requestFocus() }
        }
        TvModal(onDismiss = { continueMenu = null }) {
            Column(
                Modifier.width(480.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.headlineSmall)
                Text("Continue Watching options", color = TextMuted)
                Button(
                    onClick = {
                        viewModel.removeFromContinue(item)
                        continueMenu = null
                    },
                    modifier = Modifier.focusRequester(menuFocus),
                ) { Text("Remove from Continue Watching") }
                Button(onClick = { continueMenu = null }) { Text("Cancel") }
            }
        }
    }
}

/**
 * First launch, or nothing watched yet: instead of a bare "Library tools" row, say what
 * to do next. Every button is a real destination so the remote never lands on nothing.
 */
@Composable
private fun HomeWelcome(
    liveOn: Boolean,
    vodOn: Boolean,
    hasIptvLogin: Boolean,
    busy: Boolean,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to 1VIEW", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            when {
                busy -> "Your lists are loading. Channels and films appear here as soon as they are ready."
                !hasIptvLogin -> "Free TV is ready to browse. Sign in to your IPTV account in Settings for your full channel, movie and series lists."
                else -> "Channels you watch, your favourites and recently added films will show up here."
            },
            color = TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (liveOn) Button(onClick = onOpenLive) { Text("Browse Live TV") }
            if (vodOn) Button(onClick = onOpenMovies) { Text("Browse Movies") }
            Button(onClick = onOpenSettings) { Text(if (hasIptvLogin) "Settings" else "Sign in to IPTV") }
        }
    }
}

@Composable
private fun <T> HomeRailRow(
    items: List<T>,
    key: (T) -> Any,
    showSeeAll: Boolean,
    onSeeAll: () -> Unit,
    seeAllModifier: Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items(items.take(HOME_RAIL_CAP), key = key) { item ->
            itemContent(item)
        }
        if (showSeeAll) {
            item(key = "see_all") {
                SeeAllTile(onClick = onSeeAll, modifier = seeAllModifier)
            }
        }
    }
}

@Composable
private fun SeeAllTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("See all", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ContinueAllModal(
    items: List<ResumeItem>,
    onPlay: (ResumeItem) -> Unit,
    onOptions: (ResumeItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(items.firstOrNull()?.contentId) {
        runCatching { gridFocus.requestFocus() }
    }
    TvModal(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .background(Color(0xFF1C1C22), RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text("Continue Watching", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(HOME_POSTER_WIDTH),
                modifier = Modifier.weight(1f).focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { it.contentId }) { item ->
                    val first = item.contentId == items.firstOrNull()?.contentId
                    ContinueCard(
                        item = item,
                        onClick = { onPlay(item) },
                        onOptions = { onOptions(item) },
                        modifier = if (first) Modifier.focusRequester(gridFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMaintenanceRow(
    busy: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
) {
    Column(
        Modifier.padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LIBRARY TOOLS", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onRefresh,
                enabled = !busy,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = TextMuted,
                    focusedContainerColor = Color.White.copy(alpha = 0.12f),
                    focusedContentColor = TextPrimary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = TextMuted.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    if (busy) "Working…" else "Refresh IPTV",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Surface(
                onClick = onClearCache,
                enabled = !busy,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = TextMuted,
                    focusedContainerColor = Color.White.copy(alpha = 0.12f),
                    focusedContentColor = TextPrimary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = TextMuted.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    "Clear cache & reload",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (status.isNotBlank()) {
                Text(status, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ContinueCard(
    item: ResumeItem,
    onClick: () -> Unit,
    onOptions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(HOME_POSTER_WIDTH).tvHoldOptions(onOptions),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Charcoal,
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.posterUrl)
                    .memoryCacheKey(item.posterUrl)
                    .diskCacheKey(item.posterUrl)
                    .crossfade(false)
                    .build(),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(HOME_POSTER_ASPECT)
                    .background(Charcoal),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(item.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                ProgressBar(item.progressFraction)
                Text("${(item.progressFraction * 100).toInt()}%", color = TextMuted)
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(HOME_POSTER_WIDTH),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Charcoal,
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            HomeChannelArt(
                url = channel.logoUrl,
                name = channel.name,
                streamId = channel.streamId,
                epgChannelId = channel.epgChannelId,
                modifier = Modifier.fillMaxWidth().aspectRatio(HOME_POSTER_ASPECT),
            )
            Text(
                channel.name,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun NowOnTvCard(item: NowOnTvItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(HOME_POSTER_WIDTH),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Charcoal,
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            HomeChannelArt(
                url = item.channel.logoUrl,
                name = item.channel.name,
                streamId = item.channel.streamId,
                epgChannelId = item.channel.epgChannelId,
                modifier = Modifier.fillMaxWidth().aspectRatio(HOME_POSTER_ASPECT),
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(item.channel.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text(item.programme.title, maxLines = 2, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                ProgressBar(item.programme.progressFraction)
            }
        }
    }
}

@Composable
private fun ReminderCard(item: ReminderItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.width(200.dp).padding(12.dp)) {
            Text(item.channelName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Text(item.programmeTitle, maxLines = 2, color = TextSecondary)
            Text(formatClock(item.programmeStartMs), color = TextMuted)
        }
    }
}

@Composable
private fun PosterRailCard(url: String?, title: String, subtitle: String?, free: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(HOME_VOD_POSTER_WIDTH),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Charcoal,
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(url)
                        .diskCacheKey(url)
                        .crossfade(false)
                        .build(),
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(Charcoal),
                    contentScale = ContentScale.Crop,
                )
                if (free) {
                    FreeBadge(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, color = TextMuted)
            }
        }
    }
}

/**
 * A quiet strip above the rails when a newer build is on GitHub. It never takes focus by
 * itself; the remote lands on the first card as usual and UP reaches the buttons.
 */
@Composable
private fun UpdateBanner(
    versionName: String,
    notes: String,
    progress: Int?,
    error: String?,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C2A3F), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("1VIEW $versionName is ready to install", style = MaterialTheme.typography.titleMedium)
            val detail = when {
                progress != null -> "Downloading… $progress%"
                error != null -> error
                notes.isNotBlank() -> notes.lineSequence().first()
                else -> "Playback stops while it installs; your settings are kept."
            }
            Text(detail, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onInstall, enabled = progress == null) {
            Text(if (progress != null) "Downloading" else "Install now")
        }
        Button(onClick = onLater) { Text("Later") }
    }
}
