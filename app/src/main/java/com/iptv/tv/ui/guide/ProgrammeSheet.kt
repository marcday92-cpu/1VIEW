package com.iptv.tv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.CatchupAvailability
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatClock
import kotlinx.coroutines.delay

@Composable
fun ProgrammeSheet(
    programme: Programme,
    channel: Channel,
    onWatch: () -> Unit,
    onWatchFromStart: () -> Unit,
    canRemind: Boolean = false,
    canRecord: Boolean = false,
    hasIptvLogin: Boolean,
    timeshiftWhileLive: Boolean,
    onRemind: (Int) -> Unit,
    onRecord: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(programme.startTimeMs, channel.streamId) {
        delay(120)
        runCatching { firstAction.requestFocus() }
    }
    val nowMs = System.currentTimeMillis()
    val isPast = programme.endTimeMs <= nowMs
    val canCatchUp = CatchupAvailability.watchCatchup(channel, programme, hasIptvLogin, nowMs)
    val canRestart = CatchupAvailability.restartProgramme(
        channel,
        programme,
        timeshiftWhileLive,
        hasIptvLogin,
        nowMs,
    )

    TvModal(onDismiss = onDismiss) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C22))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                channel.name.uppercase(),
                color = LocalAccent.current,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                programme.title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${formatClock(programme.startTimeMs)} – ${formatClock(programme.endTimeMs)}" +
                    when {
                        programme.isLiveNow -> "   •   ON NOW"
                        canCatchUp -> "   •   CATCH-UP"
                        else -> ""
                    },
                color = TextSecondary,
                fontSize = 15.sp,
            )
            programme.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 15.sp,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (programme.isLiveNow) {
                    Button(onClick = onWatch, modifier = Modifier.focusRequester(firstAction)) {
                        Text("Watch Now")
                    }
                    if (canRestart) {
                        Button(onClick = onWatchFromStart) { Text("Restart programme") }
                    }
                } else if (canCatchUp) {
                    Button(onClick = onWatchFromStart, modifier = Modifier.focusRequester(firstAction)) {
                        Text("Watch catch-up")
                    }
                } else if (!isPast && canRemind) {
                    Button(onClick = { onRemind(5) }, modifier = Modifier.focusRequester(firstAction)) {
                        Text("Remind 5 min")
                    }
                    Button(onClick = { onRemind(10) }) { Text("10 min") }
                    Button(onClick = { onRemind(15) }) { Text("15 min") }
                    Button(onClick = { onRemind(0) }) { Text("At start") }
                } else if (!isPast) {
                    Button(onClick = onWatch, modifier = Modifier.focusRequester(firstAction)) {
                        Text("Watch")
                    }
                }
                if (!isPast && canRecord) Button(onClick = onRecord) { Text("Record") }
                Button(
                    onClick = onDismiss,
                    modifier = if (programme.isLiveNow || canCatchUp || !isPast) {
                        Modifier
                    } else {
                        Modifier.focusRequester(firstAction)
                    },
                ) { Text("Close") }
            }
            Text("Press BACK to close", color = TextMuted, fontSize = 12.sp)
        }
    }
}
