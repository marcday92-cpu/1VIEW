package com.iptv.tv.data.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Xtream clocks vs the Fire TV clock.
 *
 * Programme instants are stored as UTC epoch millis. The Guide, Catch-up and Recordings
 * then format them in the **device** timezone (CEST, BST, …).
 *
 * Two provider conventions must not be mixed:
 * - XMLTV / unix `start_timestamp` → an instant (offset in the string, else UTC, never the device zone).
 * - Timeshift URL `yyyy-MM-dd:HH-mm-ss` → a **naive** wall clock the panel reads in
 *   `server_info.timezone` (EDTV: Europe/London). Sending UTC here makes CEST playback start 1 h early.
 */
object XtreamTime {
    fun unixMs(value: JsonElement?): Long {
        val primitive = value as? JsonPrimitive ?: return 0L
        val raw = primitive.longOrNull
            ?: primitive.contentOrNull?.toLongOrNull()
            ?: primitive.intOrNull?.toLong()
            ?: return 0L
        return if (raw > 10_000_000_000L) raw else raw * 1000L
    }

    fun listingMs(timestamp: JsonElement?, dateTime: String?): Long {
        unixMs(timestamp).takeIf { it > 0L }?.let { return it }
        return parseDateTimeUtc(dateTime)
    }

    /**
     * XMLTV `start`/`stop`. An explicit `+0000` / `+0100` always wins.
     * Timezone-less 14-digit stamps are UTC (or the panel zone if known) — never the device zone.
     */
    fun parseXmlTv(value: String?, fallbackTimeZoneId: String = "UTC"): Long {
        if (value.isNullOrBlank()) return 0L
        val compact = value.trim().replace(WHITESPACE, "")
        val withZone = XMLTV_ZONED.find(compact)
        if (withZone != null) {
            val stamp = withZone.groupValues[1] + withZone.groupValues[2]
            return parseStrict("yyyyMMddHHmmssZ", stamp, TimeZone.getTimeZone("UTC"))
        }
        val digits = compact.take(14)
        if (digits.length < 14 || digits.any { !it.isDigit() }) return 0L
        return parseStrict("yyyyMMddHHmmss", digits, zone(fallbackTimeZoneId))
    }

    /** Timeshift path segment. Wall clock in the panel timezone, not UTC and not the viewer zone. */
    fun timeshiftStart(startMs: Long, serverTimeZoneId: String?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm-ss", Locale.US)
        fmt.isLenient = false
        fmt.timeZone = zone(serverTimeZoneId)
        return fmt.format(Date(startMs))
    }

    fun zone(id: String?): TimeZone {
        val raw = id?.trim().orEmpty()
        if (raw.isEmpty()) return TimeZone.getTimeZone("UTC")
        val zone = TimeZone.getTimeZone(raw)
        // Unknown IDs become GMT; treat that as UTC unless the panel really said GMT/UTC.
        if (zone.id == "GMT" && raw.uppercase() !in GMT_IDS) return TimeZone.getTimeZone("UTC")
        return zone
    }

    private fun parseDateTimeUtc(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        DATETIME_PATTERNS.forEach { pattern ->
            val ms = parseStrict(pattern, trimmed, TimeZone.getTimeZone("UTC"))
            if (ms > 0L) return ms
        }
        return 0L
    }

    private fun parseStrict(pattern: String, value: String, timeZone: TimeZone): Long = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            this.timeZone = timeZone
        }.parse(value)?.time ?: 0L
    }.getOrDefault(0L)

    private val XMLTV_ZONED = Regex("^(\\d{14})([+-]\\d{4})$")
    private val WHITESPACE = Regex("\\s+")
    private val GMT_IDS = setOf("GMT", "UTC", "ETC/GMT", "ETC/UTC")
    private val DATETIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd:HH-mm-ss",
        "yyyy-MM-dd'T'HH:mm:ss",
    )
}
