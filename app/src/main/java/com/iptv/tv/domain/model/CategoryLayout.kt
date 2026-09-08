package com.iptv.tv.domain.model

/**
 * TEST lists start hidden. Adult stays visible at the bottom of Live ranking.
 * Channel/category order on Live follows most-watched, then the source list.
 */
object CategoryLayout {
    const val VERSION = 3
    const val adultLiveId = "19"
    const val adultVodId = "162"

    val liveOrder: List<String> = listOf(
        // UK — watch
        "78", "3", "2", "5", "6", "30", "4", "7", "8", "81", "41", "38", "224", "168",
        // UK — football
        "9", "268", "10", "17",
        "13", "309", "205",
        "12", "267", "265", "266", "257",
        "11", "261", "167", "308", "252",
        // UK — other sport
        "16", "109", "112", "259",
        // US — watch
        "23", "231", "56", "83", "79", "49", "170",
        // US — sport
        "47", "43", "48", "46", "54", "195", "57", "26", "24",
        // Live PPV and extra sports
        "14", "110", "294", "295", "296", "255", "159",
        "18", "122",
        "15", "273", "35", "153", "34", "80", "32", "45", "76", "42", "53", "120", "293", "305",
        "299", "304", "301", "300", "302", "297", "39", "33",
        "113", "156", "260",
        // 24/7
        "269", "157", "271", "270", "201", "272",
        // International (Irish first)
        "50", "82", "244", "194", "119", "84", "127", "116", "115", "243", "52", "36",
        "124", "75", "111", "215", "77", "236", "117", "108", "121", "125",
        // Adult — last, still visible (Hide from Settings → Parental)
        adultLiveId,
    )

    val liveHidden: Set<String> = setOf(
        "277", "291", "283", "282", "280", "281", "287", "279",
        "289", "290", "286", "284", "278", "288", "285", "292",
    )

    val vodOrder: List<String> = listOf(
        "89", "94",
        "173", "176", "175", "181", "183", "185", "184",
        "90", "179",
        "177", "180",
        "186", "188", "171", "174", "207", "92", "197", "163", "190",
        "99", "172", "191", "253", "187",
        adultVodId,
    )

    val vodHidden: Set<String> = emptySet()

    val seriesOrder: List<String> = listOf(
        "158",
        "139", "142", "141", "209", "138", "151", "145", "144", "264",
        "146", "247", "192", "193",
        "149", "143", "148",
        "306",
    )

    val seriesHidden: Set<String> = emptySet()
}
