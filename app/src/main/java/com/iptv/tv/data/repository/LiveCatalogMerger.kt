package com.iptv.tv.data.repository

import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.ChannelSource
import com.iptv.tv.domain.model.FeedType

data class MergedLiveCatalog(
    val categories: List<Category>,
    val channels: List<Channel>,
)

object LiveCatalogMerger {
    fun merge(
        xcCategories: List<Category>,
        xcChannels: List<Channel>,
        orgChannels: List<Channel>,
        orgCategories: List<Category>,
        logoIndex: ChannelLogoIndex = ChannelLogoIndex.EMPTY,
    ): MergedLiveCatalog {
        val xcNormNames = xcChannels.map { ChannelSource.normalizeName(it.name) }
        val xcByNormChannel = HashMap<String, Channel>(xcChannels.size)
        xcChannels.forEachIndexed { index, channel ->
            xcByNormChannel.putIfAbsent(xcNormNames[index], channel)
        }

        val remappedOrg = mutableListOf<Channel>()
        val usedOrgIds = mutableSetOf<Int>()
        val orgNormNames = orgChannels.map { ChannelSource.normalizeName(it.name) }
        val orgByNormName = HashMap<String, Channel>(orgChannels.size)
        orgChannels.forEachIndexed { index, channel ->
            orgByNormName.putIfAbsent(orgNormNames[index], channel)
        }
        val orgByExternal = HashMap<String, Channel>()
        for (channel in orgChannels) {
            val external = channel.externalId?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            orgByExternal.putIfAbsent(external, channel)
        }
        val xcOut = xcChannels.mapIndexed { index, xc ->
            val nameKey = xcNormNames[index]
            val orgMatch = xc.epgChannelId?.lowercase()?.let { orgByExternal[it] }
                ?: nameKey.takeIf { it.length >= 4 }?.let { orgByNormName[it] }
            val merged = if (orgMatch == null) xc
            else {
                usedOrgIds += orgMatch.streamId
                xc.copy(
                    logoUrl = xc.logoUrl?.takeIf { ChannelSource.isUsableLogoUrl(it) }
                        ?: orgMatch.logoUrl?.takeIf { ChannelSource.isUsableLogoUrl(it) },
                    epgChannelId = xc.epgChannelId?.takeIf { it.isNotBlank() } ?: orgMatch.epgChannelId,
                    fallbackUrl = orgMatch.streamUrl,
                    externalId = orgMatch.externalId,
                )
            }
            applyLogo(merged, logoIndex)
        }

        orgChannels.forEachIndexed { index, org ->
            if (org.streamId in usedOrgIds) return@forEachIndexed
            val nameKey = orgNormNames[index]
            if (nameKey.length >= 4 && xcByNormChannel.containsKey(nameKey)) return@forEachIndexed
            remappedOrg += applyLogo(org, logoIndex)
        }

        val combined = shareFamilyLogos(
            channels = xcOut + remappedOrg,
            providerByFamily = providerLogos(xcChannels + orgChannels),
        )
        val usedCatIds = combined.map { it.categoryId }.toSet()
        val xcCats = xcCategories.mapIndexed { index, cat ->
            cat.copy(source = ChannelSource.XC, sortOrder = index, feedType = FeedType.LIVE)
        }
        val extraOrgCats = orgCategories
            .filter { it.id in usedCatIds && xcCats.none { xc -> xc.id == it.id } }
            .mapIndexed { index, cat ->
                cat.copy(
                    source = ChannelSource.IPTV_ORG,
                    sortOrder = 10_000 + index,
                    feedType = FeedType.LIVE,
                )
            }
        return MergedLiveCatalog(
            categories = (xcCats + extraOrgCats).distinctBy { it.id },
            channels = combined.distinctBy { it.streamId },
        )
    }

    private fun applyLogo(channel: Channel, index: ChannelLogoIndex): Channel {
        val existing = channel.logoUrl.takeIf { ChannelSource.isUsableLogoUrl(it) }
        val url = index.resolve(channel.epgChannelId, channel.name, channel.externalId, existing)
        return when {
            !url.isNullOrBlank() -> channel.copy(logoUrl = url)
            existing != null -> channel.copy(logoUrl = existing)
            else -> channel.copy(logoUrl = null)
        }
    }

    private fun providerLogos(channels: List<Channel>): Map<String, String> {
        val map = HashMap<String, String>()
        for (channel in channels) {
            val url = channel.logoUrl.takeIf { ChannelSource.isUsableLogoUrl(it) } ?: continue
            val key = ChannelSource.familyKey(channel.name)
            if (key.length >= FAMILY_MIN) map.putIfAbsent(key, url)
        }
        return map
    }

    private fun shareFamilyLogos(
        channels: List<Channel>,
        providerByFamily: Map<String, String>,
    ): List<Channel> {
        val anyByFamily = HashMap<String, String>()
        for (channel in channels) {
            val url = channel.logoUrl.takeIf { ChannelSource.isUsableLogoUrl(it) } ?: continue
            val key = ChannelSource.familyKey(channel.name)
            if (key.length >= FAMILY_MIN) anyByFamily.putIfAbsent(key, url)
        }
        return channels.map { channel ->
            if (ChannelSource.isUsableLogoUrl(channel.logoUrl)) channel
            else {
                val key = ChannelSource.familyKey(channel.name)
                val url = if (key.length >= FAMILY_MIN) {
                    providerByFamily[key] ?: anyByFamily[key]
                } else {
                    null
                }
                if (url.isNullOrBlank()) channel.copy(logoUrl = null) else channel.copy(logoUrl = url)
            }
        }
    }

    private const val FAMILY_MIN = 2
}
