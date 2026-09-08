package com.iptv.tv.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * The search chip that sits in a section's filter row. Shows the live query so the user
 * can see a search is applied, and OK re-opens the sheet.
 */
@Composable
fun SectionSearchChip(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val accent = LocalAccent.current
    val active = query.isNotBlank()
    Surface(
        onClick = onClick,
        modifier = modifier.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (active) TextPrimary else TextSecondary,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
            pressedContainerColor = accent,
            pressedContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchIcon(Modifier.width(16.dp).height(16.dp))
            Text(
                if (active) "Search: $query" else "Search",
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * Full-screen search sheet used by Live, Movies and Series. The text field sits at the top
 * and results fill the rest; D-pad DOWN from the field lands on the first result. BACK
 * closes the sheet and leaves the section exactly where it was.
 */
@Composable
fun SectionSearchSheet(
    title: String,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    status: String?,
    results: @Composable (firstResultFocus: FocusRequester) -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    // The field owns its text while the sheet is open. The view model's query is debounced, so
    // binding the field to it would recompose with the stale value and wipe each keystroke.
    var text by remember { mutableStateOf(query) }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
    TvModal(onDismiss = onDismiss, alignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.92f)
                .padding(top = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C22))
                .padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            TvTextField(
                value = text,
                onValueChange = {
                    text = it
                    onQueryChange(it)
                },
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
                focusRequester = fieldFocus,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status ?: "Type with the remote keyboard. Results update as you type.",
                color = TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                results(firstResultFocus)
            }
        }
    }
}

@Composable
private fun SearchIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val strokeWidth = 2.dp.toPx()
        val radius = size.minDimension * 0.32f
        val center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(
            color = Color.White,
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
