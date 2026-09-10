package com.iptv.tv.ui.live

import com.iptv.tv.domain.model.Programme

/**
 * Picks the Live preview schedule: now plus the rest of today and into tomorrow,
 * never a fabricated week. Unions local XMLTV with Xtream short EPG when local is thin.
 */
internal object LiveSchedule {
    const val UPCOMING_LIMIT = 50
    const val SHORT_EPG_LIMIT = 50
    /** Fetch short EPG only when local XMLTV is thinner than this many upcoming rows. */
    const val PREFER_LOCAL_MIN = 20
    /** Rest of today plus tomorrow — whatever XMLTV already stored, not a fake week. */
    const val HORIZON_MS = 36L * 60 * 60 * 1000

    fun merge(local: List<Programme>, shortEpg: List<Programme>, nowMs: Long): List<Programme> {
        val fromLocal = normalize(local, nowMs)
        val fromShort = normalize(shortEpg, nowMs)
        if (fromShort.isEmpty()) return fromLocal
        if (fromLocal.isEmpty()) return fromShort
        val byStart = LinkedHashMap<Long, Programme>()
        for (programme in fromShort) byStart[programme.startTimeMs] = programme
        // XMLTV wins on the same slot: titles and descriptions are usually fuller.
        for (programme in fromLocal) byStart[programme.startTimeMs] = programme
        return byStart.values.sortedBy { it.startTimeMs }
    }

    fun splitNowAndUpcoming(
        programmes: List<Programme>,
        nowMs: Long,
    ): Pair<Programme?, List<Programme>> {
        val sorted = normalize(programmes, nowMs)
        val now = sorted.firstOrNull { nowMs in it.startTimeMs..it.endTimeMs }
        val upcoming = sorted
            .filter { it.startTimeMs >= (now?.endTimeMs ?: nowMs) }
            .take(UPCOMING_LIMIT)
        return now to upcoming
    }

    fun needsShortEpg(localUpcomingCount: Int): Boolean = localUpcomingCount < PREFER_LOCAL_MIN

    /** Now (if any) then the rest of the evening — what the Live pane lists. */
    fun listings(now: Programme?, upcoming: List<Programme>): List<Programme> =
        listOfNotNull(now) + upcoming

    /**
     * LazyColumn index of a listing: now, then upcoming, plus a day label after today.
     * No section headers. Never guess firstVisibleItem or index-2.
     */
    fun lazyIndexOf(
        now: Programme?,
        upcoming: List<Programme>,
        programmeId: String,
        todayLabel: String,
        dayOf: (Long) -> String,
    ): Int? {
        var index = 0
        val rows = listings(now, upcoming)
        if (rows.isEmpty()) return null
        rows.groupBy { dayOf(it.startTimeMs) }.forEach { (day, programmes) ->
            if (day != todayLabel) index++
            val at = programmes.indexOfFirst { it.id == programmeId }
            if (at >= 0) return index + at
            index += programmes.size
        }
        return null
    }

    private fun normalize(programmes: List<Programme>, nowMs: Long): List<Programme> =
        programmes.asSequence()
            .filter { it.endTimeMs > nowMs && it.startTimeMs > 0L && it.endTimeMs > it.startTimeMs }
            .filter { it.title.isNotBlank() }
            .sortedBy { it.startTimeMs }
            .distinctBy { it.startTimeMs }
            .toList()
}
