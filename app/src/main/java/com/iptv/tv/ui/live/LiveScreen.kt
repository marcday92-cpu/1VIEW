package com.iptv.tv.ui.live

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.ui.components.ChannelLogo
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.ui.components.ProgressBar
import com.iptv.tv.ui.components.SectionSearchChip
import com.iptv.tv.ui.components.SectionSearchSheet
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.ui.guide.ProgrammeSheet
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.player.PlayerEngine
import com.iptv.tv.player.TvPlayerManager
import com.iptv.tv.ui.KeepAwake
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock
import com.iptv.tv.util.formatDay
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val liveBrowse by viewModel.liveBrowse.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val timeshiftWhileLive by viewModel.timeshiftWhileLive.collectAsStateWithLifecycle()
    val browsing = liveBrowse && session?.type == ContentType.LIVE
    val revealNonce by viewModel.revealNonce.collectAsStateWithLifecycle()
    val focusedProgrammeId by viewModel.focusedProgrammeId.collectAsStateWithLifecycle()
    var browseBackReady by remember { mutableStateOf(false) }
    var consumeCloseBack by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var addToGroupFor by remember { mutableStateOf<Channel?>(null) }
    var remindFor by remember { mutableStateOf<Channel?>(null) }
    var programmeFor by remember { mutableStateOf<Programme?>(null) }
    var epgFor by remember { mutableStateOf<Channel?>(null) }
    var epgText by remember { mutableStateOf("") }
    val channelListState = rememberLazyListState()
    val upcomingListState = rememberLazyListState()
    val selectedChannelFocus = remember { FocusRequester() }
    val selectedRailFocus = remember { FocusRequester() }
    val programmeFocus = remember { FocusRequester() }
    var pane by rememberSaveable { mutableStateOf(LivePane.CHANNELS) }

    if (browsing) KeepAwake()
    LaunchedEffect(browsing) {
        browseBackReady = false
        if (browsing) {
            pane = LivePane.CHANNELS
            delay(400)
            browseBackReady = true
        }
    }
    BackHandler(enabled = pane == LivePane.CHANNELS || browsing || consumeCloseBack) {
        when {
            browsing && !browseBackReady -> Unit
            pane == LivePane.CHANNELS -> pane = LivePane.CATEGORIES
            browsing -> {
                viewModel.stopPreview()
                consumeCloseBack = true
            }
        }
    }
    LaunchedEffect(consumeCloseBack) {
        if (!consumeCloseBack) return@LaunchedEffect
        delay(400)
        consumeCloseBack = false
    }
    LaunchedEffect(browsing, session?.streamId) {
        if (browsing) session?.streamId?.let { viewModel.selectChannel(it) }
    }
    // Each reveal request is honoured once: it keeps retrying while the list is still loading,
    // but a later refresh of the same list must not pull focus back to the row.
    var handledReveal by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealNonce, state.selectedRailId, state.channels, state.selected?.streamId, focusedProgrammeId) {
        if (revealNonce == 0 || revealNonce == handledReveal) return@LaunchedEffect
        val selectedId = state.selected?.streamId ?: return@LaunchedEffect
        val index = state.channels.indexOfFirst { it.streamId == selectedId }
        if (index < 0) return@LaunchedEffect
        pane = LivePane.CHANNELS
        withFrameNanos { }
        channelListState.scrollToItem(index)
        withTimeoutOrNull(500) {
            snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.any { it.key == selectedId } }.first { it }
        }
        val programmeId = focusedProgrammeId
        val restoreProgramme = programmeId != null &&
            (state.now?.id == programmeId || state.upcoming.any { it.id == programmeId })
        if (programmeId != null && restoreProgramme) {
            val lazyIndex = LiveSchedule.lazyIndexOf(
                state.now,
                state.upcoming,
                programmeId,
                formatDay(System.currentTimeMillis()),
                ::formatDay,
            )
            if (lazyIndex != null) {
                upcomingListState.scrollToItem(lazyIndex)
                withTimeoutOrNull(500) {
                    snapshotFlow {
                        upcomingListState.layoutInfo.visibleItemsInfo.any { it.key == programmeId }
                    }.first { it }
                }
            }
        }
        handledReveal = revealNonce
        val target = if (restoreProgramme) programmeFocus else selectedChannelFocus
        repeat(30) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    LaunchedEffect(state.selected?.streamId) {
        if (focusedProgrammeId != null) return@LaunchedEffect
        upcomingListState.scrollToItem(0)
    }
    LaunchedEffect(pane, state.selectedRailId, state.channels, state.selected?.streamId) {
        if (pane != LivePane.CHANNELS) return@LaunchedEffect
        if (state.selected == null && state.channels.isNotEmpty()) {
            viewModel.selectChannel(state.channels.first().streamId)
        }
    }
    LaunchedEffect(pane, state.selectedRailId) {
        if (pane != LivePane.CATEGORIES) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            runCatching { selectedRailFocus.requestFocus() }
        }
    }
    var previousPane by remember { mutableStateOf(pane) }
    LaunchedEffect(pane) {
        val from = previousPane
        previousPane = pane
        if (pane != LivePane.CHANNELS || from != LivePane.CATEGORIES) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            runCatching { selectedChannelFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceFilterRow(
                selected = state.sourceFilter,
                onSelect = viewModel::setSourceFilter,
            )
            Spacer(Modifier.width(8.dp))
            SectionSearchChip(query = search.query, onClick = viewModel::openSearch)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pane == LivePane.CATEGORIES) {
                LazyColumn(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .then(
                            if (state.selectedRailId.isNotBlank()) Modifier.focusRestorer(selectedRailFocus)
                            else Modifier.focusRestorer(),
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.rails, key = { it.id }) { rail ->
                        val selected = rail.id == state.selectedRailId
                        Surface(
                            onClick = {
                                viewModel.selectRail(rail.id)
                                pane = LivePane.CHANNELS
                            },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) LocalAccent.current.copy(alpha = 0.25f) else Color.Transparent,
                                contentColor = if (selected) TextPrimary else TextSecondary,
                                focusedContainerColor = LocalAccent.current,
                                focusedContentColor = Color.White,
                                pressedContainerColor = LocalAccent.current,
                                pressedContentColor = Color.White,
                            ),
                            modifier = Modifier.then(
                                if (selected) Modifier.focusRequester(selectedRailFocus) else Modifier,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(rail.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (rail.free) FreeBadge(selected)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.width(280.dp).fillMaxHeight()) {
                    CategoryHeader(
                        label = state.rails.firstOrNull { it.id == state.selectedRailId }?.label
                            ?: "Live",
                        onCycle = viewModel::cycleRail,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(
                                if (state.selected != null) Modifier.focusRestorer(selectedChannelFocus)
                                else Modifier.focusRestorer(),
                            ),
                        state = channelListState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val emptyMessage = state.emptyMessage
                        if (state.channels.isEmpty() && emptyMessage != null) {
                            item(key = "empty") {
                                Text(
                                    emptyMessage,
                                    color = TextMuted,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        items(state.channels, key = { it.streamId }) { ch ->
                            val playing = browsing && session?.streamId == ch.streamId
                            val selected = state.selected?.streamId == ch.streamId
                            Surface(
                                onClick = { viewModel.play(ch) },
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = when {
                                        playing -> LocalAccent.current.copy(alpha = 0.22f)
                                        selected -> Color.White.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    },
                                    contentColor = if (selected || playing) TextPrimary else TextSecondary,
                                    focusedContainerColor = LocalAccent.current,
                                    focusedContentColor = Color.White,
                                    pressedContainerColor = LocalAccent.current,
                                    pressedContentColor = Color.White,
                                ),
                                modifier = Modifier
                                    .then(if (selected) Modifier.focusRequester(selectedChannelFocus) else Modifier)
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) viewModel.onChannelFocused(ch.streamId)
                                    }
                                    .tvHoldOptions { menuFor = ch },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ChannelLogo(
                                        url = ch.logoUrl,
                                        name = ch.name,
                                        streamId = ch.streamId,
                                        epgChannelId = ch.epgChannelId,
                                        modifier = Modifier.requiredSize(56.dp, 28.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        ch.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (ch.isFree) FreeBadge(selected)
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val selected = state.selected
                PreviewBand(
                    browsing = browsing,
                    sessionTitle = session?.title,
                    selected = selected,
                    now = state.now,
                    playerManager = viewModel.playerManager,
                    engine = playerState.engine,
                    onPreviewClick = {
                        when {
                            browsing -> viewModel.enterFullscreen()
                            selected != null -> viewModel.play(selected)
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                if (selected != null) {
                    ProgrammeSchedule(
                        now = state.now,
                        upcoming = state.upcoming,
                        listState = upcomingListState,
                        focusedId = focusedProgrammeId,
                        focusRequester = programmeFocus,
                        onFocused = viewModel::onProgrammeFocused,
                        onHold = { programmeFor = it },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        if (pane == LivePane.CATEGORIES) {
                            "Choose a list, then a channel."
                        } else {
                            "OK on a channel starts it. Nothing plays until you pick one."
                        },
                        color = TextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (search.open) {
        SectionSearchSheet(
            title = "Search Live TV",
            placeholder = "Channel, or what's on now or next",
            query = search.query,
            onQueryChange = viewModel::setSearchQuery,
            onDismiss = viewModel::closeSearch,
            status = when {
                search.indexing -> "Getting channels ready…"
                search.query.isBlank() -> null
                search.results.isEmpty() -> "No channels match “${search.query}”."
                else -> "${search.results.size} channel${if (search.results.size == 1) "" else "s"} · OK to watch"
            },
        ) { firstFocus ->
            val onAirCount = search.results.count { it.onAir }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onAirCount > 0) {
                    item(key = "hdr_on_air") { SearchGroupLabel("ON NOW & NEXT") }
                }
                itemsIndexed(search.results, key = { _, hit -> hit.channel.streamId }) { index, hit ->
                    if (onAirCount in 1 until search.results.size && index == onAirCount) {
                        SearchGroupLabel("CHANNELS")
                        Spacer(Modifier.height(4.dp))
                    }
                    val first = hit === search.results.firstOrNull()
                    Surface(
                        onClick = { viewModel.playSearchHit(hit) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (first) Modifier.focusRequester(firstFocus) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.05f),
                            contentColor = TextPrimary,
                            focusedContainerColor = LocalAccent.current,
                            focusedContentColor = Color.White,
                            pressedContainerColor = LocalAccent.current,
                            pressedContentColor = Color.White,
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelLogo(
                                url = hit.channel.logoUrl,
                                name = hit.channel.name,
                                streamId = hit.channel.streamId,
                                epgChannelId = hit.channel.epgChannelId,
                                modifier = Modifier.requiredSize(80.dp, 28.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(hit.channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                                hit.programme?.let { programme ->
                                    Text(
                                        "On air: $programme",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 13.sp,
                                        color = TextMuted,
                                    )
                                }
                            }
                            if (hit.categoryName.isNotBlank()) {
                                Text(hit.categoryName, color = TextMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            if (hit.channel.isFree) FreeBadge(false)
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { ch ->
        ChannelOptions(
            channel = ch,
            isFavourite = state.isFavourite,
            hasFallback = !ch.fallbackUrl.isNullOrBlank(),
            hasGroups = state.groups.isNotEmpty(),
            onWatch = { viewModel.play(ch); menuFor = null },
            onFavourite = { viewModel.toggleFavourite(ch); menuFor = null; viewModel.restoreFocus() },
            onHide = { viewModel.hide(ch); menuFor = null; viewModel.restoreFocus() },
            onAddToGroup = { menuFor = null; addToGroupFor = ch },
            groupId = state.selectedGroupId,
            onRemoveFromGroup = { gid -> viewModel.removeFromGroup(gid, ch.streamId); menuFor = null; viewModel.restoreFocus() },
            canRemind = IptvCapabilities.canRemind(ch, state.hasIptvLogin),
            canRecord = IptvCapabilities.canRecord(ch, state.hasIptvLogin),
            onRemind = { menuFor = null; remindFor = ch },
            onRecord = { viewModel.record(ch); menuFor = null; viewModel.restoreFocus() },
            onFallback = { viewModel.playFallback(ch); menuFor = null },
            onEpg = {
                menuFor = null
                epgText = ch.epgChannelId.orEmpty()
                epgFor = ch
            },
            onEngine = { engine -> viewModel.setEngineOverride(ch, engine); menuFor = null; viewModel.restoreFocus() },
            onDismiss = { menuFor = null; viewModel.restoreFocus() },
        )
    }

    addToGroupFor?.let { ch ->
        TvModal(onDismiss = { addToGroupFor = null; viewModel.restoreFocus() }) {
            Column(
                Modifier.width(420.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add to group", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                state.groups.forEach { group ->
                    Surface(onClick = {
                        viewModel.addToGroup(group.id, ch.streamId)
                        addToGroupFor = null
                        viewModel.restoreFocus()
                    }) {
                        Text(group.name, modifier = Modifier.padding(14.dp), color = TextPrimary)
                    }
                }
                Button(onClick = { addToGroupFor = null; viewModel.restoreFocus() }) { Text("Cancel") }
            }
        }
    }

    remindFor?.let { ch ->
        TvModal(onDismiss = { remindFor = null; viewModel.restoreFocus() }) {
            Column(
                Modifier.width(420.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Remind", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Button(onClick = { viewModel.remind(ch, 5); remindFor = null; viewModel.restoreFocus() }) { Text("5 minutes before") }
                Button(onClick = { viewModel.remind(ch, 10); remindFor = null; viewModel.restoreFocus() }) { Text("10 minutes before") }
                Button(onClick = { viewModel.remind(ch, 15); remindFor = null; viewModel.restoreFocus() }) { Text("15 minutes before") }
                Button(onClick = { remindFor = null; viewModel.restoreFocus() }) { Text("Cancel") }
            }
        }
    }

    programmeFor?.let { programme ->
        val channel = state.selected
        if (channel != null) {
            ProgrammeSheet(
                programme = programme,
                channel = channel,
                onWatch = {
                    programmeFor = null
                    viewModel.watchFromSheet(channel, programme)
                },
                onWatchFromStart = {
                    programmeFor = null
                    viewModel.watchFromStart(channel, programme)
                },
                canRemind = IptvCapabilities.canRemind(channel, state.hasIptvLogin),
                canRecord = IptvCapabilities.canRecord(channel, state.hasIptvLogin),
                hasIptvLogin = state.hasIptvLogin,
                timeshiftWhileLive = timeshiftWhileLive,
                onRemind = { offset ->
                    viewModel.remindProgramme(channel, programme, offset)
                    programmeFor = null
                    viewModel.restoreFocus()
                },
                onRecord = {
                    viewModel.recordProgramme(channel, programme)
                    programmeFor = null
                    viewModel.restoreFocus()
                },
                onDismiss = { programmeFor = null; viewModel.restoreFocus() },
            )
        }
    }

    epgFor?.let { ch ->
        TvModal(onDismiss = { epgFor = null; viewModel.restoreFocus() }) {
            Column(
                Modifier.width(480.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("EPG source", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text("Override the programme guide ID if this channel shows the wrong listings.", color = TextMuted)
                TvTextField(epgText, { epgText = it }, "EPG channel ID")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.setEpgOverride(ch, epgText)
                        epgFor = null
                        viewModel.restoreFocus()
                    }) { Text("Save") }
                    Button(onClick = { epgFor = null; viewModel.restoreFocus() }) { Text("Cancel") }
                }
            }
        }
    }
}

private enum class LivePane { CATEGORIES, CHANNELS }

private val PreviewBandHeight = 120.dp

@Composable
private fun CategoryHeader(
    label: String,
    onCycle: (Int) -> Unit,
) {
    Surface(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onCycle(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onCycle(1)
                        true
                    }
                    else -> false
                }
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LocalAccent.current.copy(alpha = 0.35f),
            contentColor = Color.White,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
            pressedContainerColor = LocalAccent.current,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f))
            Text(
                label.uppercase(),
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("›", fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun PreviewBand(
    browsing: Boolean,
    sessionTitle: String?,
    selected: Channel?,
    now: Programme?,
    playerManager: TvPlayerManager,
    engine: PlayerEngine,
    onPreviewClick: () -> Unit,
) {
    val title = sessionTitle?.takeIf { browsing } ?: selected?.name
    Surface(
        onClick = onPreviewClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            contentColor = Color.White,
            focusedContainerColor = Color.Black,
            focusedContentColor = Color.White,
            pressedContainerColor = Color.Black,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PreviewBandHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (browsing) {
                    LivePreviewSurface(
                        playerManager = playerManager,
                        engine = engine,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (selected != null) {
                    ChannelLogo(
                        url = selected.logoUrl,
                        name = selected.name,
                        streamId = selected.streamId,
                        epgChannelId = selected.epgChannelId,
                        modifier = Modifier.requiredSize(96.dp, 40.dp),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xCC101018), Color(0xF2101018)),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (browsing) {
                    Text(
                        "LIVE TV",
                        color = LocalAccent.current,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    title ?: "Choose a channel",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Text(
                        now.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatClock(now.startTimeMs)} – ${formatClock(now.endTimeMs)}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(now.progressFraction, Modifier.fillMaxWidth())
                } else if (selected != null) {
                    Text("No programme listings", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeSchedule(
    now: Programme?,
    upcoming: List<Programme>,
    listState: LazyListState,
    focusedId: String?,
    focusRequester: FocusRequester,
    onFocused: (String) -> Unit,
    onHold: (Programme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { formatDay(System.currentTimeMillis()) }
    val listings = remember(now, upcoming) { LiveSchedule.listings(now, upcoming) }
    val restoreInList = focusedId != null && listings.any { it.id == focusedId }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (restoreInList) Modifier.focusRestorer(focusRequester)
                else Modifier.focusRestorer(),
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (listings.isEmpty()) {
            item(key = "schedule_empty") {
                Text(
                    "No programme listings for this channel.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            listings.groupBy { formatDay(it.startTimeMs) }.forEach { (day, programmes) ->
                if (day != today) {
                    item(key = "day_$day") {
                        Text(
                            day,
                            color = LocalAccent.current,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                }
                items(programmes, key = { it.id }) { programme ->
                    ProgrammeRow(
                        programme = programme,
                        live = programme.id == now?.id,
                        focused = programme.id == focusedId,
                        focusRequester = focusRequester,
                        onFocused = { onFocused(programme.id) },
                        onHold = { onHold(programme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    programme: Programme,
    live: Boolean,
    focused: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onHold: () -> Unit,
) {
    Surface(
        onClick = {},
        modifier = Modifier
            .then(if (focused) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvHoldOptions(onHold),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
            pressedContainerColor = LocalAccent.current,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
    ) {
        Row(
            Modifier
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "${formatClock(programme.startTimeMs)} – ${formatClock(programme.endTimeMs)}",
                color = if (live) LocalAccent.current else TextMuted,
                fontSize = 12.sp,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.width(102.dp).padding(top = 1.dp),
                maxLines = 1,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    programme.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 16.sp,
                )
                programme.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFilterRow(
    selected: LiveSourceFilter,
    onSelect: (LiveSourceFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveSourceFilter.entries.forEach { filter ->
            val on = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (on) LocalAccent.current.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                    contentColor = if (on) TextPrimary else TextSecondary,
                    focusedContainerColor = LocalAccent.current,
                    focusedContentColor = Color.White,
                    pressedContainerColor = LocalAccent.current,
                    pressedContentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Text(
                    filter.label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun FreeBadge(selected: Boolean) {
    Text(
        "FREE",
        color = if (selected) Color.White else LocalAccent.current,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun ChannelOptions(
    channel: Channel,
    isFavourite: Boolean,
    hasFallback: Boolean,
    hasGroups: Boolean,
    onWatch: () -> Unit,
    onFavourite: () -> Unit,
    onHide: () -> Unit,
    onAddToGroup: () -> Unit,
    groupId: Long? = null,
    onRemoveFromGroup: (Long) -> Unit = {},
    canRemind: Boolean = false,
    canRecord: Boolean = false,
    onRemind: () -> Unit,
    onRecord: () -> Unit,
    onFallback: () -> Unit,
    onEpg: () -> Unit,
    onEngine: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(channel.streamId) { runCatching { firstAction.requestFocus() } }
    TvModal(onDismiss = onDismiss) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C22))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(channel.name, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            val actions = buildList {
                add("Watch" to onWatch)
                add((if (isFavourite) "Remove favourite" else "Favourite") to onFavourite)
                if (hasGroups) add("Add to group" to onAddToGroup)
                if (groupId != null) add("Remove from this group" to { onRemoveFromGroup(groupId) })
                add("Hide channel" to onHide)
                if (canRemind) add("Remind" to onRemind)
                if (canRecord) add("Record" to onRecord)
                add("Programme info" to onDismiss)
                if (hasFallback) add("Choose fallback stream" to onFallback)
                add("Override EPG ID" to onEpg)
                add("Force Media3 for this stream" to { onEngine("MEDIA3") })
                add("Force VLC for this stream" to { onEngine("VLC") })
                add("Auto player for this stream" to { onEngine("AUTO") })
                add("Close" to onDismiss)
            }
            actions.forEachIndexed { index, (label, action) ->
                Surface(
                    onClick = action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.06f),
                        contentColor = TextPrimary,
                        focusedContainerColor = LocalAccent.current,
                        focusedContentColor = Color.White,
                        pressedContainerColor = LocalAccent.current,
                        pressedContentColor = Color.White,
                    ),
                ) {
                    Text(label, modifier = Modifier.padding(14.dp), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Press BACK to close", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SearchGroupLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}
