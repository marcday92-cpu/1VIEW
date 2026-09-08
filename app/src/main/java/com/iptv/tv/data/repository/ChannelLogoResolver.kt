package com.iptv.tv.data.repository

import com.iptv.tv.data.db.ChannelDao
import com.iptv.tv.domain.model.ChannelSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChannelLogoEntryPoint {
    fun channelLogoResolver(): ChannelLogoResolver
    fun channelCardStore(): ChannelCardStore
}

/**
 * In-memory logo candidate lists. Lookups are map reads (safe on the UI thread);
 * downloads and Room writes stay on IO.
 */
@Singleton
class ChannelLogoResolver @Inject constructor(
    private val iptvOrgCatalog: IptvOrgCatalog,
    private val tvLogosCatalog: TvLogosCatalog,
    private val channelDao: ChannelDao,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var combined: ChannelLogoIndex = ChannelLogoIndex.EMPTY

    init {
        ioScope.launch {
            val built = iptvOrgCatalog.logoIndex().plus(tvLogosCatalog.logoIndex())
            if (!built.isEmpty) update(built)
        }
    }

    fun update(index: ChannelLogoIndex) {
        synchronized(lock) { combined = index }
    }

    fun current(): ChannelLogoIndex = combined

    fun candidates(
        name: String,
        epgChannelId: String? = null,
        externalId: String? = null,
        existing: String? = null,
    ): List<String> {
        val index = current()
        val fromIndex = index.candidates(epgChannelId, name, externalId, existing)
        if (fromIndex.size >= 2) return fromIndex.take(MAX_CANDIDATES)
        val extras = TvLogosCatalog.constructedUrls(name)
            .filter { ChannelSource.isUsableLogoUrl(it) && it !in fromIndex }
        return (fromIndex + extras).distinct().take(MAX_CANDIDATES)
    }

    fun persistIfChanged(streamId: Int?, original: String?, loaded: String) {
        if (streamId == null) return
        if (!ChannelSource.isUsableLogoUrl(loaded)) return
        if (loaded == original?.trim()) return
        ioScope.launch {
            runCatching { channelDao.updateLogoUrl(streamId, loaded) }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 6
    }
}
