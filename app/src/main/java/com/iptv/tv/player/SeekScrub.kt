package com.iptv.tv.player

import kotlin.math.max

/** D-pad / drag math for the VOD timeline. Keep steps sofa-friendly. */
object SeekScrub {
    fun stepMs(durationMs: Long, held: Boolean): Long {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val tap = max(10_000L, safeDuration / 50L)
        val hold = max(30_000L, safeDuration / 20L)
        return if (held) hold else tap
    }

    fun positionFromFraction(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (durationMs * fraction.coerceIn(0f, 1f)).toLong().coerceIn(0L, durationMs)
    }

    fun applyStep(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (positionMs + deltaMs).coerceIn(0L, durationMs)
    }
}
