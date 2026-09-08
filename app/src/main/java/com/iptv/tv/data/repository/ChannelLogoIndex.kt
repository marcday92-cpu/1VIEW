package com.iptv.tv.data.repository

import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import kotlinx.serialization.Serializable

@Serializable
data class ChannelLogoIndexDto(
    val byId: Map<String, String> = emptyMap(),
    val byName: Map<String, String> = emptyMap(),
)

/**
 * Logo lookup keyed by channel id (tvg-id / externalId) and normalised name.
 * Built from iptv-org logos.json + channels.json, tv-logos filenames, and
 * any channel that already has artwork so XC rows can inherit it.
 */
data class ChannelLogoIndex(
    val byId: Map<String, String> = emptyMap(),
    val byName: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = byId.isEmpty() && byName.isEmpty()

    fun resolve(
        epgChannelId: String?,
        name: String,
        externalId: String? = null,
        existing: String? = null,
    ): String? = candidates(epgChannelId, name, externalId, existing).firstOrNull()

    fun candidates(
        epgChannelId: String?,
        name: String,
        externalId: String? = null,
        existing: String? = null,
    ): List<String> {
        val out = LinkedHashSet<String>()
        existing?.takeIf { ChannelSource.isUsableLogoUrl(it) }?.let { out += it.trim() }
        lookupKey(epgChannelId)?.let { out += it }
        lookupKey(externalId)?.let { out += it }
        for (key in ChannelSource.lookupKeys(name)) {
            if (key.length < MIN_KEY) continue
            byName[key]?.let { out += it }
            byId[key]?.let { out += it }
        }
        return out.toList()
    }

    fun plus(other: ChannelLogoIndex): ChannelLogoIndex {
        if (other.isEmpty) return this
        if (isEmpty) return other
        return ChannelLogoIndex(
            byId = other.byId + byId,
            byName = other.byName + byName,
        )
    }

    fun toDto() = ChannelLogoIndexDto(byId, byName)

    private fun lookupKey(raw: String?): String? {
        val id = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        byId[id.lowercase()]?.let { return it }
        val family = ChannelSource.familyKey(id)
        if (family.length >= MIN_KEY) {
            byId[family]?.let { return it }
            byName[family]?.let { return it }
        }
        val norm = ChannelSource.normalizeName(id)
        if (norm.length >= MIN_KEY) {
            byId[norm]?.let { return it }
            byName[norm]?.let { return it }
        }
        return null
    }

    companion object {
        const val MIN_KEY = 4
        val EMPTY = ChannelLogoIndex()

        fun fromDto(dto: ChannelLogoIndexDto) = ChannelLogoIndex(dto.byId, dto.byName)

        fun fromChannels(channels: List<Channel>): ChannelLogoIndex {
            if (channels.isEmpty()) return EMPTY
            val byId = HashMap<String, String>(channels.size * 2)
            val byName = HashMap<String, String>(channels.size)
            for (channel in channels) {
                val url = channel.logoUrl?.takeIf { ChannelSource.isUsableLogoUrl(it) } ?: continue
                putId(byId, channel.epgChannelId, url)
                putId(byId, channel.externalId, url)
                putName(byName, channel.name, url)
            }
            return ChannelLogoIndex(byId, byName)
        }

        fun putId(into: MutableMap<String, String>, raw: String?, url: String) {
            val id = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            into.putIfAbsent(id.lowercase(), url)
            val family = ChannelSource.familyKey(id)
            if (family.length >= MIN_KEY) into.putIfAbsent(family, url)
            val norm = ChannelSource.normalizeName(id)
            if (norm.length >= MIN_KEY) into.putIfAbsent(norm, url)
        }

        fun putName(into: MutableMap<String, String>, name: String, url: String) {
            for (key in ChannelSource.lookupKeys(name)) {
                if (key.length >= MIN_KEY) into.putIfAbsent(key, url)
            }
        }
    }
}
