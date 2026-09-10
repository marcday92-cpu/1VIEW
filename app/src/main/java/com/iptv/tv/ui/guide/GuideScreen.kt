package com.iptv.tv.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.CatchupAvailability
import com.iptv.tv.domain.model.IptvCapabilities
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.ui.components.ChannelLogo
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock
import com.iptv.tv.util.formatDay
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val PX_PER_HOUR = 320f
private const val HOUR_MS = 3_600_000f
private val CHANNEL_COLUMN = 196.dp
private val ROW_HEIGHT = 68.dp
private val TIMELINE_WIDTH = (PX_PER_HOUR * 24).dp

private val BlockIdle = Color(0xFF22222B)
private val BlockPast = Color(0xFF17171E)

@Composable
fun GuideScreen(
    jumpToNowOnOpen: Boolean = false,
    onJumpToNowConsumed: () -> Unit = {},
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(jumpToNowOnOpen) {
        if (jumpToNowOnOpen) {
            viewModel.jumpToNow()
            onJumpToNowConsumed()
        }
    }

    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val dayEnd = state.dayStartMs + 24 * 60 * 60 * 1000L
    val showNowLine = nowMs in state.dayStartMs until dayEnd
    val nowOffsetDp = ((nowMs - state.dayStartMs) / HOUR_MS) * PX_PER_HOUR

    // Land on the current time rather than at midnight.
    LaunchedEffect(state.dayStartMs, state.channels.size, state.jumpToNowToken) {
        if (showNowLine && state.channels.isNotEmpty()) {
            val target = with(density) { (nowOffsetDp - 120f).coerceAtLeast(0f).dp.roundToPx() }
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
        }
    }

    BackHandler(enabled = state.selectedCategoryId != null) {
        viewModel.clearCategory()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                when {
                    state.selectedCategoryName != null &&
                        state.dayStartMs + 24 * 60 * 60 * 1000L <= nowMs ->
                        "${state.selectedCategoryName}  ·  Catch-up"
                    state.selectedCategoryName != null -> state.selectedCategoryName!!
                    state.dayStartMs + 24 * 60 * 60 * 1000L <= nowMs ->
                        "${formatDay(state.dayStartMs)}  ·  Catch-up"
                    else -> formatDay(state.dayStartMs)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            Button(onClick = { viewModel.jumpToNow() }) { Text("Jump to Now") }
            Button(onClick = { viewModel.shiftDay(-1) }) { Text("Previous day") }
            Button(onClick = { viewModel.shiftDay(1) }) { Text("Next day") }
            Button(onClick = { viewModel.toggleFavouritesOnly() }) {
                Text(if (state.favouritesOnly) "All lists" else "Favourites only")
            }
            Button(onClick = { viewModel.refreshEpg() }) { Text("Refresh EPG") }
            if (state.selectedCategoryId != null) {
                Button(onClick = { viewModel.clearCategory() }) { Text("All lists") }
            }
        }
        if (state.hasIptvLogin && state.selectedCategoryId != null) {
            Text(
                "Past programmes marked Catch-up can be replayed. Use Previous day to browse further back.",
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (state.selectedCategoryId == null) {
            if (state.categories.isEmpty()) {
                Text(
                    if (state.loading) "Loading guide…" else state.emptyMessage ?: "No lists to show.",
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.categories.distinctBy { it.id }, key = { it.id }) { cat ->
                        Surface(
                            onClick = { viewModel.selectCategory(cat.id) },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = TextPrimary,
                                focusedContainerColor = LocalAccent.current,
                                focusedContentColor = Color.White,
                                pressedContainerColor = LocalAccent.current,
                                pressedContentColor = Color.White,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        ) {
                            Text(
                                cat.name,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                                fontSize = 18.sp,
                            )
                        }
                    }
                }
            }
        } else {
            Row(Modifier.padding(bottom = 6.dp)) {
                Spacer(Modifier.width(CHANNEL_COLUMN))
                Row(Modifier.horizontalScroll(scroll)) {
                    (0..23).forEach { hour ->
                        Text(
                            "%02d:00".format(hour),
                            modifier = Modifier.width(PX_PER_HOUR.dp),
                            color = TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                if (state.channels.isEmpty()) {
                    Text(
                        if (state.loading) "Loading guide…" else state.emptyMessage ?: "No channels to show.",
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.channels.distinctBy { it.streamId }, key = { it.streamId }) { channel ->
                        GuideRow(
                            channel = channel,
                            programmes = state.programmes[channel.epgChannelId].orEmpty(),
                            dayStart = state.dayStartMs,
                            nowMs = nowMs,
                            hasIptvLogin = state.hasIptvLogin,
                            scroll = scroll,
                            onSelect = { viewModel.select(it, channel) },
                        )
                    }
                }
                val nowLineX = CHANNEL_COLUMN + nowOffsetDp.dp - with(density) { scroll.value.toDp() }
                if (showNowLine && nowLineX >= CHANNEL_COLUMN) {
                    Box(
                        Modifier
                            .offset(x = nowLineX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(LocalAccent.current),
                    )
                }
            }
        }
    }

    val selected = state.selected
    val ch = state.selectedChannel
    if (selected != null && ch != null) {
        ProgrammeSheet(
            programme = selected,
            channel = ch,
            onWatch = { viewModel.watch(ch) },
            onWatchFromStart = { viewModel.watchFromStart() },
            canRemind = IptvCapabilities.canRemind(ch, state.hasIptvLogin),
            canRecord = IptvCapabilities.canRecord(ch, state.hasIptvLogin),
            hasIptvLogin = state.hasIptvLogin,
            timeshiftWhileLive = state.timeshiftWhileLive,
            onRemind = { viewModel.remind(it) },
            onRecord = { viewModel.record(-1, -1) },
            onDismiss = { viewModel.dismissDetail() },
        )
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    dayStart: Long,
    nowMs: Long,
    hasIptvLogin: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
    onSelect: (Programme) -> Unit,
) {
    Row(Modifier.height(ROW_HEIGHT)) {
        Box(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    url = channel.logoUrl,
                    name = channel.name,
                    streamId = channel.streamId,
                    epgChannelId = channel.epgChannelId,
                    modifier = Modifier.requiredSize(72.dp, 24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    channel.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(scroll).fillMaxHeight()) {
        Box(Modifier.width(TIMELINE_WIDTH).fillMaxHeight()) {
            if (programmes.isEmpty()) {
                Box(
                    Modifier
                        .width(PX_PER_HOUR.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BlockPast),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("No guide data", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
            programmes.forEach { prog ->
                ProgrammeBlock(
                    programme = prog,
                    dayStart = dayStart,
                    nowMs = nowMs,
                    canCatchUp = CatchupAvailability.watchCatchup(channel, prog, hasIptvLogin, nowMs),
                    onSelect = { onSelect(prog) },
                )
            }
        }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: Programme,
    dayStart: Long,
    nowMs: Long,
    canCatchUp: Boolean,
    onSelect: () -> Unit,
) {
    val accent = LocalAccent.current
    val startOffset = ((programme.startTimeMs - dayStart) / HOUR_MS) * PX_PER_HOUR
    val width = max(90f, (programme.durationMs / HOUR_MS) * PX_PER_HOUR)
    val isPast = programme.endTimeMs < nowMs
    val container = when {
        programme.isLiveNow -> accent.copy(alpha = 0.28f)
        canCatchUp -> Color(0xFF1C2433)
        isPast -> BlockPast
        else -> BlockIdle
    }

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .offset(x = startOffset.dp)
            .width(width.dp)
            .fillMaxHeight()
            .padding(end = 3.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = if (isPast) TextSecondary else TextPrimary,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
            pressedContainerColor = accent,
            pressedContentColor = Color.White,
        ),
        border = if (programme.isLiveNow) {
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, accent), shape = RoundedCornerShape(8.dp)),
            )
        } else {
            ClickableSurfaceDefaults.border()
        },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                programme.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (programme.isLiveNow) {
                    Text("● ", color = accent, fontSize = 12.sp)
                }
                if (canCatchUp) {
                    Text("Catch-up  ", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    formatClock(programme.startTimeMs),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            if (programme.isLiveNow && width > 140f) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(programme.progressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent),
                    )
                }
            }
        }
    }
}

