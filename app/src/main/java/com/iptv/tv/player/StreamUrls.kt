package com.iptv.tv.player

/** Small URL rewrites shared by the main player and the Multi tiles. */
object StreamUrls {
    /**
     * The HLS twin of an Xtream `.ts` URL, or null when the URL is not a `.ts` stream.
     * Only the final path segment is touched so hosts such as `iptv.tsnet.io` stay intact.
     */
    fun hlsVariant(url: String): String? {
        val queryStart = url.indexOf('?')
        val path = if (queryStart >= 0) url.substring(0, queryStart) else url
        val query = if (queryStart >= 0) url.substring(queryStart) else ""
        if (!path.endsWith(".ts", ignoreCase = true)) return null
        return path.dropLast(3) + ".m3u8" + query
    }
}
