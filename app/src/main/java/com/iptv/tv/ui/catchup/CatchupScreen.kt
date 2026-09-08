package com.iptv.tv.ui.catchup

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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.ui.LocalPageHasFocus
import com.iptv.tv.ui.components.ChannelLogo
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock

@Composable
fun CatchupScreen(
    viewModel: CatchupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pageHasFocus by LocalPageHasFocus.current
    val listFocus = remember { FocusRequester() }
    val channelListState = rememberLazyListState()
    val programmeListState = rememberLazyListState()

    // Same as Home: take the remote only when the page is empty (tab just opened, or the
    // window bounced to the avatar). Never request focus again while the lists refresh.
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isEmpty()) return@LaunchedEffect
        repeat(60) {
            withFrameNanos { }
            if (pageHasFocus) return@LaunchedEffect
            runCatching { listFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 8.dp),
    ) {
        Text("Catch-up", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            state.archiveHint ?: "Finished programmes from your IPTV archive",
            color = TextMuted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.loading && state.channels.isEmpty() -> Text("Loading catch-up…", color = TextMuted)
            state.emptyMessage != null && state.channels.isEmpty() -> {
                Text(state.emptyMessage!!, color = TextMuted, fontSize = 16.sp)
            }
            else -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyColumn(
                    state = channelListState,
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .focusRequester(listFocus)
                        .focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.channels, key = { it.streamId }) { channel ->
                        ChannelRow(
                            channel = channel,
                            selected = state.selected?.streamId == channel.streamId,
                            onFocus = { viewModel.selectChannel(channel.streamId) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    // Keep this LazyColumn in composition while programmes load. Swapping it for
                    // a Text disposes every focused row and Android then highlights the avatar.
                    LazyColumn(
                        state = programmeListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.loadingProgrammes && state.days.isEmpty()) {
                            item(key = "loading") {
                                Text("Loading programmes…", color = TextMuted)
                            }
                        }
                        if (state.programmesEmptyMessage != null && state.days.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    state.programmesEmptyMessage!!,
                                    color = TextMuted,
                                    fontSize = 16.sp,
                                )
                            }
                        }
                        state.days.forEach { group ->
                            item(key = "day_${group.label}") {
                                Text(
                                    group.label,
                                    color = LocalAccent.current,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(group.programmes, key = { it.id }) { programme ->
                                ProgrammeRow(
                                    programme = programme,
                                    enabled = state.listingsStreamId == state.selected?.streamId,
                                    onPlay = { viewModel.play(programme) },
                                )
                            }
                        }
                    }
                    if (state.loadingProgrammes && state.days.isNotEmpty()) {
                        Text(
                            "Loading programmes…",
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
) {
    Surface(
        onClick = onFocus,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
            contentColor = if (selected) TextPrimary else TextSecondary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
            pressedContainerColor = LocalAccent.current,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = channel.logoUrl,
                name = channel.name,
                streamId = channel.streamId,
                epgChannelId = channel.epgChannelId,
                modifier = Modifier.requiredSize(80.dp, 28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                channel.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProgrammeRow(
    programme: Programme,
    enabled: Boolean,
    onPlay: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onPlay() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary.copy(alpha = if (enabled) 1f else 0.45f),
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
            pressedContainerColor = LocalAccent.current,
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatClock(programme.startTimeMs)}–${formatClock(programme.endTimeMs)}",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(110.dp),
            )
            Text(
                programme.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
