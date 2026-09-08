package com.iptv.tv.data.repository

import com.iptv.tv.data.api.ArchiveMetadataFile
import com.iptv.tv.data.api.asText
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ArchivePlaybackPick(
    val url: String,
    val fallbackUrl: String?,
    val containerExtension: String,
    val subtitleUrl: String? = null,
)

object ArchiveFilePicker {
    fun pick(identifier: String, files: List<ArchiveMetadataFile>): ArchivePlaybackPick? {
        val videos = files.mapNotNull { file ->
            val name = file.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isVideo(name, file.format)) return@mapNotNull null
            ScoredFile(name, file.format.orEmpty(), file.source.orEmpty(), file.size.asText()?.toLongOrNull() ?: 0L)
        }.sortedByDescending { score(it) }
        val best = videos.firstOrNull() ?: return null
        val fallback = videos.drop(1).firstOrNull { it.name != best.name }
        val subtitle = files.mapNotNull { file ->
            val name = file.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name.takeIf { isSubtitle(it) }
        }.maxByOrNull { subtitleScore(it) }
        return ArchivePlaybackPick(
            url = downloadUrl(identifier, best.name),
            fallbackUrl = fallback?.let { downloadUrl(identifier, it.name) },
            containerExtension = extensionOf(best.name),
            subtitleUrl = subtitle?.let { downloadUrl(identifier, it) },
        )
    }

    fun downloadUrl(identifier: String, fileName: String): String {
        val encoded = fileName.split('/').joinToString("/") { part ->
            URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        return "https://archive.org/download/$identifier/$encoded"
    }

    fun posterUrl(identifier: String): String = "https://archive.org/services/img/$identifier"

    private fun isVideo(name: String, format: String?): Boolean {
        val lower = name.lowercase()
        if (SKIP_NAME.any { it in lower }) return false
        val fmt = format.orEmpty().lowercase()
        if (SKIP_FORMAT.any { it in fmt }) return false
        return VIDEO_EXT.any { lower.endsWith(it) } ||
            fmt.contains("mpeg4") || fmt.contains("h.264") || fmt.contains("h264") ||
            fmt.contains("matroska") || fmt.contains("quicktime")
    }

    private fun isSubtitle(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass")
    }

    private fun score(file: ScoredFile): Int {
        val name = file.name.lowercase()
        val format = file.format.lowercase()
        var score = 0
        if (name.endsWith(".mp4") || "mpeg4" in format || "h.264" in format || "h264" in format) score += 120
        if ("512kb" in name || "512kb" in format) score += 45
        if ("720" in name || "720p" in format) score += 35
        if ("480" in name) score += 20
        if (file.source.equals("derivative", ignoreCase = true)) score += 20
        if (name.endsWith(".m4v")) score += 80
        if (name.endsWith(".mkv")) score += 50
        if (name.endsWith(".avi") || name.endsWith(".mpg") || name.endsWith(".mpeg")) score += 25
        if (name.endsWith(".ogv") || name.endsWith(".ogg")) score += 5
        if (file.source.equals("original", ignoreCase = true) && file.size > 1_500_000_000L) score -= 40
        if ("4k" in name || "2160" in name) score -= 15
        return score
    }

    private fun subtitleScore(name: String): Int {
        val lower = name.lowercase()
        var score = 0
        if ("eng" in lower || "en." in lower || "english" in lower) score += 10
        if (lower.endsWith(".srt")) score += 5
        return score
    }

    private fun extensionOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("mp4", "mkv", "avi", "mpg", "mpeg", "m4v", "mov", "ogv")) ext else "mp4"
    }

    private data class ScoredFile(
        val name: String,
        val format: String,
        val source: String,
        val size: Long,
    )

    private val VIDEO_EXT = listOf(".mp4", ".m4v", ".mkv", ".avi", ".mpg", ".mpeg", ".mov", ".ogv", ".wmv")
    private val SKIP_NAME = listOf(
        "__ia_thumb", ".thumbs", "sprite", ".gif", ".jpg", ".jpeg", ".png", ".xml",
        ".json", ".sqlite", ".torrent", ".iso", ".zip", ".7z",
    )
    private val SKIP_FORMAT = listOf("thumbnail", "animated gif", "item tile", "archive bitstream")
}
