package com.iptv.tv.domain.model

/**
 * EDTV/Xtream `tv_archive` means the *channel* has a catch-up window.
 * It does **not** mean a programme that is still on air can be restarted.
 */
object CatchupAvailability {
    fun isArchiveChannel(channel: Channel, hasIptvLogin: Boolean): Boolean =
        IptvCapabilities.canCatchup(channel, hasIptvLogin) && channel.tvArchive > 0

    /**
     * Provider archive length in days. `tv_archive_duration` wins; some panels put the
     * day count in `tv_archive` itself (anything greater than a 0/1 flag). Never invent 7.
     */
    fun archiveWindowDays(channel: Channel): Int =
        channel.tvArchiveDuration.takeIf { it > 0 } ?: channel.tvArchive.takeIf { it > 1 } ?: 0

    fun archiveWindowMs(channel: Channel): Long {
        val days = archiveWindowDays(channel)
        return if (days > 0) days * 24L * 60L * 60L * 1000L else 0L
    }

    fun watchCatchup(
        channel: Channel,
        programme: Programme,
        hasIptvLogin: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isArchiveChannel(channel, hasIptvLogin)) return false
        if (programme.endTimeMs > nowMs) return false
        return withinArchiveWindow(channel, programme, nowMs)
    }

    /**
     * Finished programmes still inside this channel's archive window.
     * Currently airing items are never included — restart-while-live belongs on the Guide.
     * If the provider marked rows with `has_archive`, those win over a raw EPG dump.
     */
    fun catchupProgrammes(
        channel: Channel,
        programmes: List<Programme>,
        hasIptvLogin: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Programme> {
        if (!isArchiveChannel(channel, hasIptvLogin)) return emptyList()
        val finished = programmes.filter { prog ->
            prog.startTimeMs > 0L &&
                prog.endTimeMs > prog.startTimeMs &&
                prog.endTimeMs <= nowMs &&
                withinArchiveWindow(channel, prog, nowMs)
        }
        return if (finished.any { it.hasArchive }) finished.filter { it.hasArchive } else finished
    }

    /** Restart-while-live is not assumed. Only offer it when the provider has proven it. */
    fun restartProgramme(
        channel: Channel,
        programme: Programme,
        timeshiftWhileLive: Boolean,
        hasIptvLogin: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!timeshiftWhileLive) return false
        if (!isArchiveChannel(channel, hasIptvLogin)) return false
        if (nowMs !in programme.startTimeMs..programme.endTimeMs) return false
        return withinArchiveWindow(channel, programme, nowMs)
    }

    private fun withinArchiveWindow(channel: Channel, programme: Programme, nowMs: Long): Boolean {
        val windowMs = archiveWindowMs(channel)
        if (windowMs <= 0) return true
        // Both ends of the programme must still sit inside the provider's archive window.
        return nowMs - programme.startTimeMs <= windowMs && nowMs - programme.endTimeMs <= windowMs
    }
}
