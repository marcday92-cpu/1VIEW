package com.iptv.tv.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatClock(ms: Long): String {
    // Device timezone on purpose: stored values are UTC epoch millis.
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}

fun formatDay(ms: Long): String {
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val gb = bytes / (1024.0 * 1024 * 1024)
    return String.format(Locale.US, "%.1f GB free", gb)
}
