package com.iptv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iptv.tv.data.repository.ChannelLogoEntryPoint
import com.iptv.tv.ui.theme.Charcoal
import dagger.hilt.android.EntryPointAccessors

/**
 * Home-only 16:9 filled poster. Live/Guide keep [ChannelLogo].
 * Reuses files/channel-cards/ on the first frame when a PNG already exists.
 */
@Composable
fun HomeChannelArt(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    streamId: Int? = null,
    epgChannelId: String? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ChannelLogoEntryPoint::class.java)
            .channelCardStore()
    }
    val cached = remember(url, name, epgChannelId) {
        store.cachedFile(url, name, epgChannelId)
    }
    val file by produceState(cached, url, name, epgChannelId, streamId) {
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = runCatching {
            store.ensureCard(url, name, epgChannelId, streamId)
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Charcoal),
    ) {
        val shown = file
        if (shown != null) {
            AsyncImage(
                model = ImageRequest.Builder(appContext)
                    .data(shown)
                    .memoryCacheKey(shown.absolutePath)
                    .diskCacheKey(shown.absolutePath)
                    .crossfade(false)
                    .build(),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
