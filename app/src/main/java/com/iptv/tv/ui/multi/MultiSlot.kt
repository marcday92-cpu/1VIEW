package com.iptv.tv.ui.multi

import com.iptv.tv.domain.model.Channel

sealed class MultiSlot {
    data class Live(val channel: Channel) : MultiSlot()
    data class Web(val url: String, val title: String) : MultiSlot()

    val label: String
        get() = when (this) {
            is Live -> channel.name
            is Web -> title.ifBlank { url }
        }
}
