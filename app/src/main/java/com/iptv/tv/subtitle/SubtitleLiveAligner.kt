package com.iptv.tv.subtitle

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/**
 * Estimates a constant sidecar delay by matching subtitle cue times to
 * dialogue onsets in the decoded soundtrack. Does not seek and does not
 * use the microphone.
 */
class SubtitleLiveAligner {

    suspend fun estimateOffset(
        cues: List<SubtitleCue>,
        appliedDelayMs: Long,
        positionMs: () -> Long,
        isPlaying: () -> Boolean,
        rms: () -> Float,
        timeoutMs: Long = 45_000L,
    ): Long? {
        val dialogue = cues.filter { isDialogue(it.text) }
        if (dialogue.isEmpty()) return null

        val history = ArrayDeque<Pair<Long, Float>>()
        val onsets = ArrayDeque<Long>()
        val offsets = mutableListOf<Long>()
        val scored = HashSet<Int>()
        var lastPos = positionMs()
        var quietTicks = 0
        var peakRms = 0f
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < timeoutMs && offsets.size < MAX_SAMPLES) {
            if (!isPlaying()) {
                delay(80)
                continue
            }
            val pos = positionMs()
            val level = rms()
            peakRms = max(peakRms, level)
            history.addLast(pos to level)
            while (history.size > HISTORY_SIZE) history.removeFirst()

            val floor = percentile(history.map { it.second }, 0.25f)
            val thresh = max(SPEECH_FLOOR, floor * 3.2f)
            if (level < thresh * 0.55f) {
                quietTicks++
            } else {
                if (quietTicks >= 4 && level >= thresh) {
                    onsets.addLast(pos)
                    while (onsets.size > 48) onsets.removeFirst()
                }
                quietTicks = 0
            }

            if (System.currentTimeMillis() - startedAt > 8_000L && peakRms < MIN_PEAK_RMS) {
                return null
            }

            for ((index, cue) in dialogue.withIndex()) {
                if (index in scored) continue
                val displayAt = cue.startMs + appliedDelayMs
                val matchAt = displayAt + POST_CUE_MS
                if (lastPos < matchAt && pos >= matchAt) {
                    scored += index
                    val onset = onsets.firstOrNull {
                        it in (displayAt - PRE_CUE_MS)..(displayAt + POST_CUE_MS)
                    } ?: continue
                    val total = onset - cue.startMs
                    if (abs(total) <= MAX_ABS_OFFSET_MS) {
                        offsets += total
                        if (offsets.size >= MIN_SAMPLES && isConsistent(offsets)) {
                            return quantize(median(offsets))
                        }
                    }
                }
            }
            lastPos = pos
            delay(50)
        }

        return if (offsets.size >= MIN_SAMPLES && isConsistent(offsets)) {
            quantize(median(offsets))
        } else {
            null
        }
    }

    private fun isDialogue(text: String): Boolean {
        val cleaned = text.replace(Regex("<[^>]+>"), " ").trim()
        if (cleaned.length < 8) return false
        if (cleaned.all { it == '♪' || it == '♫' || it.isWhitespace() }) return false
        if (cleaned.contains("www.", ignoreCase = true)) return false
        if (cleaned.contains("opensubtitles", ignoreCase = true)) return false
        if (cleaned.contains("subdl", ignoreCase = true)) return false
        return true
    }

    private fun isConsistent(offsets: List<Long>): Boolean {
        val med = median(offsets)
        val mad = offsets.map { abs(it - med) }.sorted()[offsets.size / 2]
        return mad <= 450
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0.02f
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun quantize(offsetMs: Long): Long {
        if (abs(offsetMs) < 80) return 0
        return (offsetMs / 50L) * 50L
    }

    companion object {
        private const val HISTORY_SIZE = 120
        private const val PRE_CUE_MS = 2_200L
        private const val POST_CUE_MS = 1_600L
        private const val MIN_SAMPLES = 3
        private const val MAX_SAMPLES = 8
        private const val MAX_ABS_OFFSET_MS = 8_000L
        private const val SPEECH_FLOOR = 0.04f
        private const val MIN_PEAK_RMS = 0.012f
    }
}
