package com.iptv.tv.domain.model

/**
 * Sections a user can switch off in Settings → Manage features. Home and Settings are
 * always available, so the app can never be left without a way back in.
 */
enum class AppFeature(val label: String, val description: String) {
    LIVE("Live TV", "Live channels, channel rails on Home and the Guide."),
    CATCHUP("Catch-up", "Finished programmes from IPTV channels that keep a TV archive. Needs Live TV and an IPTV login."),
    MOVIES("Movies", "Movie catalogue and the Recently Added Movies rail."),
    SERIES("Series", "Series catalogue and the Recently Added Series rail."),
    GUIDE("Guide", "The programme guide tab. Needs Live TV."),
    MULTI("Multi view", "Watch several channels at once. Needs Live TV."),
    WEB("Web", "The built-in browser tab."),
    RECORDINGS("Recordings", "Record and schedule recordings from an IPTV account. Needs Live TV."),
    SEARCH("Global search", "The search button at the top of every screen. Each section keeps its own search."),
    ;

    /** Features that only make sense while [LIVE] is on. */
    val needsLive: Boolean get() = this == CATCHUP || this == GUIDE || this == MULTI || this == RECORDINGS

    companion object {
        fun parseDisabled(raw: String?): Set<AppFeature> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(',').mapNotNull { token ->
                entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
            }.toSet()
        }

        fun encodeDisabled(disabled: Set<AppFeature>): String =
            disabled.sortedBy { it.ordinal }.joinToString(",") { it.name }

        /** Turning Live off also hides everything that depends on it. */
        fun effectiveEnabled(disabled: Set<AppFeature>): Set<AppFeature> {
            val liveOff = LIVE in disabled
            return entries.filter { feature ->
                feature !in disabled && !(liveOff && feature.needsLive)
            }.toSet()
        }
    }
}
