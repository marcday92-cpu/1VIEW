package com.iptv.tv.ui.live

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import com.iptv.tv.ui.components.ChannelLogo
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.ui.components.ProgressBar
import com.iptv.tv.ui.components.SectionSearchChip
import com.iptv.tv.ui.components.SectionSearchSheet
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.ui.KeepAwake
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock
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
    val browsing = liveBrowse && session?.type == ContentType.LIVE
    val revealNonce by viewModel.revealNonce.collectAsStateWithLifecycle()
    var browseBackReady by remember { mutableStateOf(false) }
    var consumeCloseBack by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var addToGroupFor by remember { mutableStateOf<Channel?>(null) }
    var remindFor by remember { mutableStateOf<Channel?>(null) }
    var epgFor by remember { mutableStateOf<Channel?>(null) }
    var epgText by remember { mutableStateOf("") }
    val channelListState = rememberLazyListState()
    val selectedChannelFocus = remember { FocusRequester() }

    if (browsing) KeepAwake()
    LaunchedEffect(browsing) {
        browseBackReady = false
        if (browsing) {
            delay(400)
            browseBackReady = true
        }
    }
    BackHandler(enabled = browsing || consumeCloseBack) {
        if (browsing && browseBackReady) {
            viewModel.stopPreview()
            consumeCloseBack = true
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
    LaunchedEffect(revealNonce, state.selectedRailId, state.channels, state.selected?.streamId) {
        if (revealNonce == 0 || revealNonce == handledReveal) return@LaunchedEffect
        val selectedId = state.selected?.streamId ?: return@LaunchedEffect
        val index = state.channels.indexOfFirst { it.streamId == selectedId }
        if (index < 0) return@LaunchedEffect
        channelListState.scrollToItem(index)
        // Wait for the row to be laid out (its FocusRequester attaches with it), not a timer.
        withTimeoutOrNull(500) {
            snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.any { it.key == selectedId } }.first { it }
        }
        handledReveal = revealNonce
        runCatching { selectedChannelFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 8.dp),
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
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        LazyColumn(
            modifier = Modifier.width(if (browsing) 200.dp else 240.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.rails, key = { it.id }) { rail ->
                val selected = rail.id == state.selectedRailId
                Surface(
                    onClick = { viewModel.selectRail(rail.id) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) LocalAccent.current.copy(alpha = 0.25f) else Color.Transparent,
                        contentColor = if (selected) TextPrimary else TextSecondary,
                        focusedContainerColor = LocalAccent.current,
                        focusedContentColor = Color.White,
                        pressedContainerColor = LocalAccent.current,
                        pressedContentColor = Color.White,
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

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            state = channelListState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            if (focus.isFocused) viewModel.selectChannel(ch.streamId)
                        }
                        .tvHoldOptions { menuFor = ch },
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelLogo(
                            url = ch.logoUrl,
                            name = ch.name,
                            streamId = ch.streamId,
                            epgChannelId = ch.epgChannelId,
                            modifier = Modifier.requiredSize(if (browsing) 56.dp else 80.dp, 28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            ch.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (ch.isFree) FreeBadge(selected)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .width(if (browsing) 360.dp else 320.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.06f))
                .padding(16.dp),
        ) {
            val selected = state.selected
            if (browsing && session != null) {
                Surface(
                    onClick = { viewModel.enterFullscreen() },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                        focusedContainerColor = Color.Black,
                        focusedContentColor = Color.White,
                        pressedContainerColor = Color.Black,
                        pressedContentColor = Color.White,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                    ) {
                        LivePreviewSurface(
                            playerManager = viewModel.playerManager,
                            engine = playerState.engine,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    session?.title.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(12.dp))
                Text("NOW", color = LocalAccent.current, style = MaterialTheme.typography.labelLarge)
                Text(
                    state.now?.title ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                state.now?.let { nowProgramme ->
                    Text(
                        "${formatClock(nowProgramme.startTimeMs)}–${formatClock(nowProgramme.endTimeMs)}",
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(nowProgramme.progressFraction, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text("NEXT", color = TextMuted, style = MaterialTheme.typography.labelLarge)
                Text(
                    state.next?.title ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "OK on preview for full screen  •  BACK stops",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            } else if (selected == null) {
                Text(
                    "Choose a channel to watch",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "OK on a channel starts it. Nothing plays until you pick one.",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            } else {
                ChannelLogo(
                    url = selected.logoUrl,
                    name = selected.name,
                    streamId = selected.streamId,
                    epgChannelId = selected.epgChannelId,
                    modifier = Modifier.fillMaxWidth().requiredHeight(56.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    selected.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(16.dp))
                Text("NOW", color = LocalAccent.current, style = MaterialTheme.typography.labelLarge)
                Text(
                    state.now?.title ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                state.now?.let { nowProgramme ->
                    Text(
                        "${formatClock(nowProgramme.startTimeMs)}–${formatClock(nowProgramme.endTimeMs)}",
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(nowProgramme.progressFraction, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text("NEXT", color = TextMuted, style = MaterialTheme.typography.labelLarge)
                Text(
                    state.next?.title ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                state.next?.let { nextProgramme ->
                    Text(
                        "${formatClock(nextProgramme.startTimeMs)}–${formatClock(nextProgramme.endTimeMs)}",
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "OK to watch  •  hold OK for options",
                    color = TextMuted,
                    fontSize = 12.sp,
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
            onFavourite = { viewModel.toggleFavourite(ch); menuFor = null },
            onHide = { viewModel.hide(ch); menuFor = null },
            onAddToGroup = { menuFor = null; addToGroupFor = ch },
            groupId = state.selectedGroupId,
            onRemoveFromGroup = { gid -> viewModel.removeFromGroup(gid, ch.streamId); menuFor = null },
            canRemind = IptvCapabilities.canRemind(ch, state.hasIptvLogin),
            canRecord = IptvCapabilities.canRecord(ch, state.hasIptvLogin),
            onRemind = { menuFor = null; remindFor = ch },
            onRecord = { viewModel.record(ch); menuFor = null },
            onFallback = { viewModel.playFallback(ch); menuFor = null },
            onEpg = {
                menuFor = null
                epgText = ch.epgChannelId.orEmpty()
                epgFor = ch
            },
            onEngine = { engine -> viewModel.setEngineOverride(ch, engine); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }

    addToGroupFor?.let { ch ->
        TvModal(onDismiss = { addToGroupFor = null }) {
            Column(
                Modifier.width(420.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add to group", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                state.groups.forEach { group ->
                    Surface(onClick = {
                        viewModel.addToGroup(group.id, ch.streamId)
                        addToGroupFor = null
                    }) {
                        Text(group.name, modifier = Modifier.padding(14.dp), color = TextPrimary)
                    }
                }
                Button(onClick = { addToGroupFor = null }) { Text("Cancel") }
            }
        }
    }

    remindFor?.let { ch ->
        TvModal(onDismiss = { remindFor = null }) {
            Column(
                Modifier.width(420.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Remind", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Button(onClick = { viewModel.remind(ch, 5); remindFor = null }) { Text("5 minutes before") }
                Button(onClick = { viewModel.remind(ch, 10); remindFor = null }) { Text("10 minutes before") }
                Button(onClick = { viewModel.remind(ch, 15); remindFor = null }) { Text("15 minutes before") }
                Button(onClick = { remindFor = null }) { Text("Cancel") }
            }
        }
    }

    epgFor?.let { ch ->
        TvModal(onDismiss = { epgFor = null }) {
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
                    }) { Text("Save") }
                    Button(onClick = { epgFor = null }) { Text("Cancel") }
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
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
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
