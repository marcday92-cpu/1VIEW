package com.iptv.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iptv.tv.data.repository.ChannelLogoEntryPoint
import com.iptv.tv.ui.theme.TextMuted
import dagger.hilt.android.EntryPointAccessors

/**
 * Fixed-size channel artwork box so neighbours stay aligned. Fit + a tight inset
 * lets typical IPTV wordmarks fill the tile without squares changing row height.
 */
@Composable
fun ChannelLogo(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    streamId: Int? = null,
    epgChannelId: String? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val resolver = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ChannelLogoEntryPoint::class.java)
            .channelLogoResolver()
    }
    val candidates = remember(url, name, epgChannelId) {
        resolver.candidates(
            name = name,
            epgChannelId = epgChannelId,
            existing = url,
        )
    }
    var index by remember(candidates) { mutableIntStateOf(0) }
    var exhausted by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
    val current = candidates.getOrNull(index)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!exhausted && current != null) {
            AsyncImage(
                model = ImageRequest.Builder(appContext)
                    .data(current)
                    .crossfade(false)
                    .build(),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                onError = {
                    if (index < candidates.lastIndex) index++ else exhausted = true
                },
                onSuccess = {
                    resolver.persistIfChanged(streamId, url, current)
                },
            )
        } else {
            ChannelLogoPlaceholder()
        }
    }
}

@Composable
private fun ChannelLogoPlaceholder() {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val glyph = if (maxHeight < 40.dp) 14.dp else if (maxHeight < 64.dp) 18.dp else 22.dp
        TvGlyph(Modifier.size(glyph))
    }
}

@Composable
private fun TvGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (size.minDimension * 0.12f).coerceAtLeast(1.5f)
        val bodyH = size.height * 0.62f
        val bodyW = size.width * 0.86f
        val left = (size.width - bodyW) / 2f
        val top = size.height * 0.08f
        drawRoundRect(
            color = TextMuted,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(stroke * 1.2f, stroke * 1.2f),
            style = Stroke(width = stroke),
        )
        val standY = top + bodyH + stroke
        val standW = bodyW * 0.38f
        drawLine(
            color = TextMuted,
            start = Offset(size.width / 2f, standY),
            end = Offset(size.width / 2f, size.height * 0.88f),
            strokeWidth = stroke,
        )
        drawLine(
            color = TextMuted,
            start = Offset((size.width - standW) / 2f, size.height * 0.88f),
            end = Offset((size.width + standW) / 2f, size.height * 0.88f),
            strokeWidth = stroke,
        )
    }
}
