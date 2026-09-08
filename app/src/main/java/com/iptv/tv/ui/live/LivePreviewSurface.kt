package com.iptv.tv.ui.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.iptv.tv.R
import com.iptv.tv.player.PlayerEngine
import com.iptv.tv.player.TvPlayerManager
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun LivePreviewSurface(
    playerManager: TvPlayerManager,
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
) {
    if (engine == PlayerEngine.VLC) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { playerManager.attachVlcLayout(it) }
            },
            onRelease = { layout -> playerManager.detachVlcLayout(layout) },
            modifier = modifier,
        )
    } else {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = playerManager.player
                }
            },
            update = { view ->
                if (view.player !== playerManager.player) {
                    view.player = playerManager.player
                }
            },
            onRelease = { view -> view.player = null },
            modifier = modifier,
        )
    }
}
