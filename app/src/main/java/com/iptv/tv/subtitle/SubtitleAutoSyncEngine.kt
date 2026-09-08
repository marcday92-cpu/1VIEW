package com.iptv.tv.subtitle

import com.iptv.tv.data.db.SubtitleSyncDao
import com.iptv.tv.data.db.SubtitleSyncOffsetEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Singleton
class SubtitleAutoSyncEngine @Inject constructor(
    private val syncDao: SubtitleSyncDao,
) {
    suspend fun getSavedOffset(contentKey: String, subtitleId: String): Long? {
        return syncDao.get(contentKey, subtitleId)?.offsetMs
    }

    suspend fun saveOffset(contentKey: String, subtitleId: String, offsetMs: Long) {
        syncDao.upsert(
            SubtitleSyncOffsetEntity(
                contentKey = contentKey,
                subtitleId = subtitleId,
                offsetMs = offsetMs,
            ),
        )
    }

    fun parseSrt(file: File): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val content = runCatching { file.readText() }.getOrElse { return cues }
        val blocks = content.split("\n\n", "\r\n\r\n")
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            val timeLine = lines.find { it.contains("-->") } ?: continue
            val times = timeLine.split("-->").map { it.trim() }
            if (times.size != 2) continue
            val startMs = parseSrtTime(times[0])
            val endMs = parseSrtTime(times[1])
            val textStartIndex = lines.indexOf(timeLine) + 1
            val text = lines.drop(textStartIndex).joinToString(" ").trim()
            if (text.isNotBlank()) {
                cues.add(SubtitleCue(startMs, endMs, text))
            }
        }
        return cues
    }

    private fun parseSrtTime(time: String): Long {
        val parts = time.replace(",", ".").split(":")
        if (parts.size != 3) return 0
        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toLongOrNull() ?: 0
        val millis = secondsParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
    }

}
