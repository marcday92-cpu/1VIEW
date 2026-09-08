package com.iptv.tv.data.repository

import com.iptv.tv.data.api.XtreamApi
import com.iptv.tv.data.api.XtreamUrlBuilder
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.db.ChannelDao
import com.iptv.tv.data.db.ChannelEpgOverrideDao
import com.iptv.tv.data.db.EpgDao
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.domain.model.Programme
import com.iptv.tv.util.TitleMatch
import com.iptv.tv.domain.model.ChannelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepository @Inject constructor(
    private val api: XtreamApi,
    private val credentialsStore: CredentialsStore,
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val channelEpgOverrideDao: ChannelEpgOverrideDao,
    private val preferences: AppPreferences,
) {
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /** One XMLTV download at a time: the worker and the Settings/Guide buttons often coincide. */
    suspend fun refreshEpg(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            refreshMutex.withLock { refreshEpgLocked() }
        }
    }

    private suspend fun refreshEpgLocked(): Int {
        run {
            val creds = credentialsStore.getCredentials()
                ?: throw IllegalStateException("Not logged in")
            val days = preferences.epgDaysToKeep.first()
            val now = System.currentTimeMillis()
            val cutoff = now - days * 24L * 60 * 60 * 1000
            val futureLimit = now + 14L * 24 * 60 * 60 * 1000
            val parsed = api.downloadXmlTv(XtreamUrlBuilder.xmlTvUrl(creds)).use { response ->
                parseXmlTv(response.byteStream(), cutoff, futureLimit, credentialsStore.getServerTimeZoneId() ?: "UTC")
            }
            val overrides = channelEpgOverrideDao.getAll().associate { it.streamId to it.epgChannelId }
            val epgToStream = channelDao.getAll().associate { entity ->
                val epgId = overrides[entity.streamId] ?: entity.epgChannelId
                epgId to entity.streamId
            }.filterKeys { it != null }.mapKeys { it.key!! }

            val enriched = parsed.programmes.distinctBy { it.id }.map { prog ->
                prog.copy(channelStreamId = epgToStream[prog.channelId])
            }
            epgDao.replaceAll(enriched.map { it.toEntity() }, days)
            applyXmlTvIcons(parsed.icons, overrides)
            return enriched.size
        }
    }

    suspend fun clearCache() {
        epgDao.clearAll()
    }

    suspend fun getProgrammesForDay(
        channelEpgIds: List<String>,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<Programme> = runCatching {
        if (channelEpgIds.isEmpty()) {
            epgDao.getInRange(dayStartMs, dayEndMs).map { it.toDomain() }
        } else {
            channelEpgIds.distinct()
                .chunked(SQLITE_VARIABLE_LIMIT)
                .flatMap { batch -> epgDao.getForChannels(batch, dayStartMs, dayEndMs) }
                .map { it.toDomain() }
        }
    }.getOrDefault(emptyList())

    suspend fun getProgrammesForChannel(
        channelEpgId: String,
        fromMs: Long,
        toMs: Long,
    ): List<Programme> = runCatching {
        if (channelEpgId.isBlank()) return@runCatching emptyList()
        epgDao.getForChannel(channelEpgId, fromMs, toMs).map { it.toDomain() }
    }.getOrDefault(emptyList())

    suspend fun getCurrentForStreams(streamIds: List<Int>): List<Programme> = runCatching {
        if (streamIds.isEmpty()) return@runCatching emptyList()
        val now = System.currentTimeMillis()
        streamIds.distinct().chunked(SQLITE_VARIABLE_LIMIT).flatMap { batch ->
            epgDao.getCurrentForStreams(batch, now)
        }.map { it.toDomain() }
    }.getOrDefault(emptyList())

    /**
     * What is on now and next on every channel (titles by EPG channel id), so the Live search
     * can match "us open" to the channel showing it. One range query; a few thousand rows.
     */
    suspend fun nowAndNextTitles(
        horizonMs: Long = NOW_NEXT_HORIZON_MS,
        perChannel: Int = 3,
    ): Map<String, List<String>> = runCatching {
        val now = System.currentTimeMillis()
        epgDao.getInRange(now, now + horizonMs)
            .groupBy { it.channelEpgId }
            .mapValues { (_, programmes) ->
                programmes.asSequence()
                    .sortedBy { it.startTimeMs }
                    .map { it.title.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(perChannel)
                    .toList()
            }
    }.getOrDefault(emptyMap())

    suspend fun getCurrentProgramme(channelEpgId: String): Programme? = runCatching {
        epgDao.getCurrentProgramme(channelEpgId, System.currentTimeMillis())?.toDomain()
    }.getOrNull()

    suspend fun getNextProgramme(channelEpgId: String): Programme? = runCatching {
        val now = System.currentTimeMillis()
        epgDao.getNextProgramme(channelEpgId, now)?.toDomain()
    }.getOrNull()

    suspend fun epgChannelIdsOnDay(dayStartMs: Long, dayEndMs: Long): Set<String> = runCatching {
        epgDao.distinctChannelIdsInRange(dayStartMs, dayEndMs).toSet()
    }.getOrDefault(emptySet())

    suspend fun searchProgrammes(query: String): List<Programme> = runCatching {
        val stems = TitleMatch.stems(query)
        if (stems.isEmpty()) return@runCatching emptyList()
        val now = System.currentTimeMillis()
        stems.flatMap { epgDao.searchProgrammes(it, now) }
            .distinctBy { it.id }
            .map { it.toDomain() }
    }.getOrDefault(emptyList())

    private data class XmlTvParse(
        val programmes: List<Programme>,
        val icons: Map<String, String>,
    )

    private fun parseXmlTv(
        input: InputStream,
        fromMs: Long,
        toMs: Long,
        fallbackTimeZoneId: String,
    ): XmlTvParse {
        val programmes = mutableListOf<Programme>()
        val icons = HashMap<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        var eventType = parser.eventType
        var currentChannelId: String? = null
        var title: String? = null
        var description: String? = null
        var start: Long = 0
        var stop: Long = 0
        var inChannel = false
        var iconChannelId: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            inChannel = true
                            iconChannelId = parser.getAttributeValue(null, "id")
                        }
                        "icon" -> if (inChannel) {
                            val src = parser.getAttributeValue(null, "src")
                            val chId = iconChannelId
                            if (!src.isNullOrBlank() && chId != null && ChannelSource.isUsableLogoUrl(src)) {
                                icons.putIfAbsent(chId, src.trim())
                            }
                        }
                        "programme" -> {
                            currentChannelId = parser.getAttributeValue(null, "channel")
                            start = parseXmlTvTime(parser.getAttributeValue(null, "start"), fallbackTimeZoneId)
                            stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"), fallbackTimeZoneId)
                            title = null
                            description = null
                        }
                        "title" -> if (parser.next() == XmlPullParser.TEXT) title = parser.text
                        "desc" -> if (parser.next() == XmlPullParser.TEXT) description = parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            inChannel = false
                            iconChannelId = null
                        }
                        "programme" -> {
                            val chId = currentChannelId
                            val progTitle = title
                            if (
                                chId != null && progTitle != null && start > 0 && stop > start &&
                                stop >= fromMs && start <= toMs
                            ) {
                                programmes.add(
                                    Programme(
                                        id = "${chId}_${start}",
                                        channelId = chId,
                                        channelStreamId = null,
                                        title = progTitle,
                                        description = description,
                                        startTimeMs = start,
                                        endTimeMs = stop,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return XmlTvParse(programmes.distinctBy { it.id }, icons)
    }

    private suspend fun applyXmlTvIcons(
        icons: Map<String, String>,
        overrides: Map<Int, String>,
    ) {
        if (icons.isEmpty()) return
        val byLower = HashMap<String, String>(icons.size * 2)
        for ((id, url) in icons) {
            byLower.putIfAbsent(id, url)
            byLower.putIfAbsent(id.lowercase(), url)
        }
        for (entity in channelDao.getAll()) {
            if (ChannelSource.isUsableLogoUrl(entity.logoUrl)) continue
            val epgId = overrides[entity.streamId] ?: entity.epgChannelId ?: continue
            val icon = byLower[epgId] ?: byLower[epgId.lowercase()] ?: continue
            if (ChannelSource.isUsableLogoUrl(icon)) {
                channelDao.updateLogoUrl(entity.streamId, icon)
            }
        }
    }

    private companion object {
        private const val NOW_NEXT_HORIZON_MS = 4 * 60 * 60 * 1000L
        // SQLite allows 999 bound parameters; leave headroom for the range args.
        const val SQLITE_VARIABLE_LIMIT = 900
    }

    internal fun parseXmlTvTime(value: String?, fallbackTimeZoneId: String = "UTC"): Long =
        com.iptv.tv.data.api.XtreamTime.parseXmlTv(value, fallbackTimeZoneId)
}
