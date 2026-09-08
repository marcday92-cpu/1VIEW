package com.iptv.tv.data.repository

/**
 * Turns Archive classic-TV titles into Show → Season → Episode.
 * Patterns match Movies Deluxe / Archive Movie Browser / IA Theater listings.
 */
object ArchiveTitleParser {
    data class ParsedEpisode(
        val showName: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String,
    )

    fun parse(title: String, creator: String? = null): ParsedEpisode? {
        val cleaned = tidy(title) ?: return null
        matchStructured(cleaned)?.let { return it }
        val fromCreator = creator?.let { tidy(it) }
        if (!fromCreator.isNullOrBlank() && fromCreator.lowercase() != cleaned.lowercase()) {
            val remainder = cleaned.removePrefix(fromCreator, ignoreCase = true)
                .trim(' ', '-', '–', ':', '|', '.')
            matchStructured("$fromCreator $remainder")?.let { return it }
            val nums = trailingEpisode(remainder.ifBlank { cleaned })
            return ParsedEpisode(
                showName = displayShowName(fromCreator),
                season = nums?.first ?: 1,
                episode = nums?.second ?: 1,
                episodeTitle = remainder.ifBlank { cleaned }.ifBlank { "Episode ${nums?.second ?: 1}" },
            )
        }
        splitShowAndTitle(cleaned)?.let { (show, rest) ->
            val nums = trailingEpisode(rest) ?: (1 to 1)
            if (show.length >= 3) {
                return ParsedEpisode(
                    showName = displayShowName(show),
                    season = nums.first,
                    episode = nums.second,
                    episodeTitle = rest.ifBlank { "Episode ${nums.second}" },
                )
            }
        }
        return null
    }

    fun showKey(name: String): String =
        displayShowName(name).lowercase().replace(NON_ALNUM, "")

    fun displayShowName(name: String): String {
        var value = tidy(name) ?: return name.trim()
        value = YEAR_PAREN.replace(value, "").trim()
        value = COMPLETE.replace(value, "").trim()
        value = value.trim(' ', '-', '–', ':')
        if (value.length >= 4 && value.all { !it.isLetter() || it.isUpperCase() }) {
            value = value.lowercase().replaceFirstChar { it.titlecase() }
        }
        return value.ifBlank { name.trim() }
    }

    private fun matchStructured(text: String): ParsedEpisode? {
        SXXEXX.find(text)?.let { match ->
            return ParsedEpisode(
                showName = displayShowName(match.groupValues[1]),
                season = match.groupValues[2].toInt(),
                episode = match.groupValues[3].toInt(),
                episodeTitle = match.groupValues[4].trim().ifBlank {
                    "Episode ${match.groupValues[3].toInt()}"
                },
            )
        }
        SEASON_EPISODE.find(text)?.let { match ->
            return ParsedEpisode(
                showName = displayShowName(match.groupValues[1]),
                season = match.groupValues[2].toInt(),
                episode = match.groupValues[3].toInt(),
                episodeTitle = match.groupValues[4].trim().ifBlank {
                    "Episode ${match.groupValues[3].toInt()}"
                },
            )
        }
        NXNN.find(text)?.let { match ->
            return ParsedEpisode(
                showName = displayShowName(match.groupValues[1]),
                season = match.groupValues[2].toInt(),
                episode = match.groupValues[3].toInt(),
                episodeTitle = match.groupValues[4].trim().ifBlank {
                    "Episode ${match.groupValues[3].toInt()}"
                },
            )
        }
        EP_AFTER_SHOW.find(text)?.let { match ->
            return ParsedEpisode(
                showName = displayShowName(match.groupValues[1]),
                season = 1,
                episode = match.groupValues[2].toInt(),
                episodeTitle = match.groupValues[3].trim().ifBlank {
                    "Episode ${match.groupValues[2].toInt()}"
                },
            )
        }
        return null
    }

    private fun splitShowAndTitle(text: String): Pair<String, String>? {
        val parts = text.split(SHOW_SPLIT, limit = 2)
        if (parts.size != 2) return null
        val show = parts[0].trim()
        val rest = parts[1].trim()
        if (show.length < 3 || rest.length < 2) return null
        return show to rest
    }

    private fun trailingEpisode(text: String): Pair<Int, Int>? {
        SXXEXX.find(text)?.let { return it.groupValues[2].toInt() to it.groupValues[3].toInt() }
        EP_ONLY.find(text)?.let { return 1 to it.groupValues[1].toInt() }
        return null
    }

    private fun tidy(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        value = BRACKETS.replace(value, " ")
        value = UNDERSCORE.replace(value, " ")
        value = WHITESPACE.replace(value, " ").trim()
        PREFIXES.forEach { prefix ->
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.substring(prefix.length).trim(' ', '-', '–', ':')
            }
        }
        return value.takeIf { it.isNotBlank() }
    }

    private fun String.removePrefix(prefix: String, ignoreCase: Boolean): String {
        if (!startsWith(prefix, ignoreCase)) return this
        return substring(prefix.length)
    }

    private val SXXEXX = Regex(
        """^(.+?)[\s._-]+[Ss](\d{1,2})[Ee](\d{1,3})(?:[\s._-]+(.+))?$""",
    )
    private val SEASON_EPISODE = Regex(
        """^(.+?)\s+Season\s+(\d{1,2})\s+(?:Episode|Ep\.?)\s+(\d{1,3})(?:\s*[-–:]\s*(.+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val NXNN = Regex(
        """^(.+?)\s+(\d{1,2})x(\d{1,3})(?:[\s._-]+(.+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val EP_AFTER_SHOW = Regex(
        """^(.+?)\s+Ep(?:isode)?\.?\s*(\d{1,3})(?:[\s._-]+(.+))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val EP_ONLY = Regex(
        """(?:Episode|Ep\.?)\s+(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHOW_SPLIT = Regex("""\s+[-–:]\s+""")
    private val YEAR_PAREN = Regex("""\s*\((?:18|19|20)\d{2}\)\s*$""")
    private val COMPLETE = Regex(
        """\s*[-–:]?\s*(complete series|the series|tv series|tv)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETS = Regex("""\s*[\(\[][^)\]]*[)\]]""")
    private val UNDERSCORE = Regex("""[._]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val PREFIXES = listOf(
        "Classic TV - ",
        "Classic Television - ",
        "Public Domain TV - ",
        "PD TV - ",
    )
}
