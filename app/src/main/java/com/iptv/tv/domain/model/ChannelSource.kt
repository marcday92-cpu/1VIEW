package com.iptv.tv.domain.model

enum class LiveSourceFilter {
    ALL, IPTV, FREE;

    val label: String
        get() = when (this) {
            ALL -> "ALL"
            IPTV -> "IPTV"
            FREE -> "FREE"
        }

    companion object {
        fun fromStored(raw: String?): LiveSourceFilter =
            entries.find { it.name == raw } ?: ALL
    }
}

enum class VodSourceFilter {
    ALL, IPTV, FREE;

    val label: String
        get() = when (this) {
            ALL -> "ALL"
            IPTV -> "IPTV"
            FREE -> "FREE"
        }

    companion object {
        fun fromStored(raw: String?): VodSourceFilter =
            entries.find { it.name == raw } ?: ALL
    }
}

object ChannelSource {
    const val XC = "xc"
    const val IPTV_ORG = "iptv_org"
    const val ARCHIVE = "archive_org"
    const val ORG_ID_BASE = 1_000_000_000
    const val ARCHIVE_MOVIE_ID_BASE = 80_000_000
    const val ARCHIVE_SERIES_ID_BASE = 120_000_000
    const val ARCHIVE_EPISODE_ID_BASE = 160_000_000
    const val ARCHIVE_ID_MASK = 0x00FFFFFF
    const val COUNTRY_PREFIX = "io_cc_"
    const val GENRE_PREFIX = "io_cat_"
    const val ARCHIVE_VOD_PREFIX = "ao_vod_"
    const val ARCHIVE_SERIES_PREFIX = "ao_ser_"
    const val FAST_MOVIE_ID_BASE = 200_000_000
    const val FAST_SERIES_ID_BASE = 220_000_000
    const val FAST_EPISODE_ID_BASE = 240_000_000
    const val FAST_VOD_MOVIES = "io_vod_movie_channels"
    const val FAST_VOD_CLASSIC = "io_vod_classic_channels"
    const val FAST_SER_CHANNELS = "io_ser_series_channels"

    /** Free country rails: these stay at the top, in this order. iptv-org uses GB for the UK. */
    val FREE_COUNTRY_PIN = listOf("GB", "IE", "US", "AU", "CA", "NZ", "ZA", "MT")

    fun orgStreamId(externalId: String): Int = hashedId(externalId, ORG_ID_BASE)

    fun archiveMovieId(identifier: String): Int = hashedId(identifier, ARCHIVE_MOVIE_ID_BASE, ARCHIVE_ID_MASK)

    fun archiveSeriesId(showKey: String): Int = hashedId(showKey, ARCHIVE_SERIES_ID_BASE, ARCHIVE_ID_MASK)

    fun archiveEpisodeId(identifier: String): Int = hashedId(identifier, ARCHIVE_EPISODE_ID_BASE, ARCHIVE_ID_MASK)

    fun hashedId(externalId: String, base: Int, mask: Int = 0x3FFFFFFF): Int {
        var hash = 0
        for (char in externalId) {
            hash = hash * 31 + char.code
        }
        return base + (hash and mask)
    }

    fun uniqueHashedId(externalId: String, base: Int, used: MutableSet<Int>, mask: Int = 0x3FFFFFFF): Int {
        var id = hashedId(externalId, base, mask)
        while (!used.add(id)) id++
        return id
    }

    fun vodCategoryId(genre: String): String =
        ARCHIVE_VOD_PREFIX + genre.lowercase().replace(NON_ALNUM, "_")

    fun seriesCategoryId(genre: String): String =
        ARCHIVE_SERIES_PREFIX + genre.lowercase().replace(NON_ALNUM, "_")

    fun isFreeSource(source: String): Boolean =
        source == IPTV_ORG || source == ARCHIVE

    fun isFastFreeCategory(categoryId: String): Boolean =
        categoryId == FAST_VOD_MOVIES || categoryId == FAST_VOD_CLASSIC || categoryId == FAST_SER_CHANNELS

    fun countryCategoryId(countryCode: String): String =
        COUNTRY_PREFIX + countryCode.uppercase()

    fun genreCategoryId(genre: String): String =
        GENRE_PREFIX + genre.lowercase().replace(NON_ALNUM, "_")

    fun countryCodeFromCategory(categoryId: String): String? =
        categoryId.removePrefix(COUNTRY_PREFIX).takeIf { categoryId.startsWith(COUNTRY_PREFIX) }

    fun freeCountryPinIndex(categoryId: String): Int {
        val code = countryCodeFromCategory(categoryId) ?: return Int.MAX_VALUE
        val index = FREE_COUNTRY_PIN.indexOf(code.uppercase())
        return if (index >= 0) index else Int.MAX_VALUE
    }

    fun displayCountryName(countryCode: String, fallback: String): String =
        if (countryCode.equals("GB", ignoreCase = true)) "UK" else fallback

    fun normalizeName(name: String): String =
        expandAbbreviations(stripLeadingCountry(name))
            .lowercase()
            .replace(BRACKETED, " ")
            .replace(QUALITY_TAGS, " ")
            .replace(NON_ALNUM, "")
            .trim()

    /**
     * Exact remaining name after stripping quality/region tokens. Used to share
     * artwork across HD/UHD/SD variants of the same channel, not across a brand
     * prefix (Sky Sports F1 stays distinct from Sky Sports Main).
     */
    fun familyKey(name: String): String {
        val words = expandAbbreviations(stripLeadingCountry(name))
            .lowercase()
            .replace(BRACKETED, " ")
            .replace(TIMESHIFT, " ")
            .replace(QUALITY_TAGS, " ")
            .replace(NON_ALNUM_SPACE, " ")
            .trim()
            .split(WHITESPACE)
            .mapNotNull { word ->
                val trimmed = word.replace(GLUED_QUALITY, "")
                trimmed.takeIf { it.isNotEmpty() && it !in DROP_WORDS }
            }
        if (words.isEmpty()) return ""
        val kept = words.toMutableList()
        while (kept.size > 1 && kept.last() in REGION_WORDS) {
            kept.removeAt(kept.lastIndex)
        }
        return kept.joinToString("")
    }

    fun hyphenSlug(name: String): String? {
        val words = expandAbbreviations(stripLeadingCountry(name))
            .lowercase()
            .replace(BRACKETED, " ")
            .replace(TIMESHIFT, " ")
            .replace(QUALITY_TAGS, " ")
            .replace(NON_ALNUM_SPACE, " ")
            .trim()
            .split(WHITESPACE)
            .mapNotNull { word ->
                val trimmed = word.replace(GLUED_QUALITY, "")
                trimmed.takeIf { it.isNotEmpty() && it !in DROP_WORDS }
            }
            .toMutableList()
        if (words.isEmpty()) return null
        while (words.size > 1 && words.last() in REGION_WORDS) {
            words.removeAt(words.lastIndex)
        }
        val slug = words.joinToString("-")
        return slug.takeIf { it.length >= 3 }
    }

    /**
     * Keys to try when looking up artwork. Includes abbreviation expansion
     * (SkySp → Sky Sports) and a few UK aliases (Main → Main Event).
     */
    fun lookupKeys(name: String): List<String> {
        val keys = LinkedHashSet<String>()
        fun add(raw: String) {
            val family = familyKey(raw)
            if (family.length >= 2) keys += family
            val norm = normalizeName(raw)
            if (norm.length >= 2) keys += norm
        }
        add(name)
        val cleaned = expandAbbreviations(stripLeadingCountry(name))
        if (cleaned != name) add(cleaned)
        if ("skysportsmain" in keys || "skyspmain" in keys) {
            keys += "skysportsmainevent"
        }
        return keys.toList()
    }

    fun isUsableLogoUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.length < 12) return false
        val lower = value.lowercase()
        if (lower in UNUSABLE_LOGO_TOKENS) return false
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.startsWith("data:")) return false
        return UNUSABLE_LOGO_HOST.find(lower) == null
    }

    private fun stripLeadingCountry(name: String): String {
        var value = name.trim()
        LEADING_PAREN_COUNTRY.find(value)?.let { match ->
            if (match.groupValues[1].lowercase() in COUNTRY_PREFIXES) {
                value = value.substring(match.range.last + 1).trim()
            }
        }
        LEADING_COUNTRY_SEP.find(value)?.let { match ->
            if (match.groupValues[1].lowercase() in COUNTRY_PREFIXES) {
                value = value.substring(match.range.last + 1).trim()
            }
        }
        return value
    }

    private fun expandAbbreviations(name: String): String {
        var value = name
        for ((pattern, replacement) in ABBREVIATIONS) {
            value = pattern.replace(value, replacement)
        }
        return value
    }

    private val BRACKETED = Regex("""\s*[\(\[][^)\]]*[)\]]""")
    private val TIMESHIFT = Regex("""\+\d+""")
    private val QUALITY_TAGS = Regex(
        """\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|hdr|hlg|dolby|vision|extra|hq|lq|raw|\+|plus|backup|\d{2,3}\s*fps|\d{3,4}p|50p|60p)\b""",
    )
    private val GLUED_QUALITY = Regex("(uhd|fhd|hdr|hevc|h265|h264|4k|8k|hq|lq|sd|hd)$")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val NON_ALNUM_SPACE = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val DROP_WORDS = setOf(
        "uhd", "fhd", "hd", "sd", "4k", "8k", "hevc", "h265", "h264", "hdr", "hlg",
        "dolby", "vision", "extra", "hq", "lq", "raw",
        "plus", "plus1", "plus2", "backup",
        "50fps", "60fps", "25fps", "30fps", "24fps",
        "50p", "60p", "1080p", "720p", "2160p", "1080", "720", "2160", "4320",
    )
    private val REGION_WORDS = setOf("uk", "gb", "us", "usa", "ie", "au", "ca", "nz")
    private val COUNTRY_PREFIXES = setOf(
        "uk", "gb", "us", "usa", "ie", "au", "ca", "nz", "za", "mt",
        "de", "it", "fr", "es", "nl", "pt", "pl", "se", "no", "dk", "fi",
        "be", "at", "ch", "ar", "br", "mx", "in", "pk", "ae", "sa",
    )
    private val LEADING_PAREN_COUNTRY = Regex("""^\(([a-z]{2,3})\)\s*""", RegexOption.IGNORE_CASE)
    private val LEADING_COUNTRY_SEP = Regex("""^([a-z]{2,3})\s*[:|/\-–]+\s*""", RegexOption.IGNORE_CASE)
    private val ABBREVIATIONS = listOf(
        Regex("""\bskysp\b""", RegexOption.IGNORE_CASE) to "sky sports",
        Regex("""\bbbc1\b""", RegexOption.IGNORE_CASE) to "bbc one",
        Regex("""\bbbc2\b""", RegexOption.IGNORE_CASE) to "bbc two",
        Regex("""\bbbc3\b""", RegexOption.IGNORE_CASE) to "bbc three",
        Regex("""\bbbc4\b""", RegexOption.IGNORE_CASE) to "bbc four",
        Regex("""\bitv1\b""", RegexOption.IGNORE_CASE) to "itv",
    )
    private val UNUSABLE_LOGO_TOKENS = setOf("none", "null", "n/a", "na", "-", "undefined")
    private val UNUSABLE_LOGO_HOST = Regex(
        """https?://[^/]*(discordapp\.(?:com|net)|discord\.com)""",
        RegexOption.IGNORE_CASE,
    )
}
