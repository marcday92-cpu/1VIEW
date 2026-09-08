package com.iptv.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

object SidecarText {
    fun isSidecarFormat(format: Format, sidecarFileName: String? = null): Boolean =
        isSidecarFormat(format.sampleMimeType, format.label, sidecarFileName)

    fun isSidecarFormat(mime: String?, label: String?, sidecarFileName: String? = null): Boolean {
        if (isSidecarMime(mime)) return true
        val name = sidecarFileName.orEmpty()
        val trackLabel = label.orEmpty()
        if (name.isNotBlank() && (trackLabel == name || trackLabel.endsWith(name))) return true
        return trackLabel.endsWith(".srt", ignoreCase = true) ||
            trackLabel.endsWith(".vtt", ignoreCase = true) ||
            trackLabel.endsWith(".ttml", ignoreCase = true)
    }

    fun isSidecarMime(mime: String?): Boolean {
        val value = mime?.lowercase().orEmpty()
        if (value.isBlank()) return false
        return value == MimeTypes.APPLICATION_SUBRIP.lowercase() ||
            value == MimeTypes.TEXT_VTT.lowercase() ||
            value == MimeTypes.APPLICATION_TTML.lowercase() ||
            value.contains("subrip") ||
            value.contains("vtt") ||
            value.contains("ttml") ||
            value.contains("ssa") ||
            value.contains("ass")
    }

    fun isTextGroup(type: Int): Boolean = type == C.TRACK_TYPE_TEXT
}
