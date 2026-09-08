package com.iptv.tv.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleMatchTest {
    @Test
    fun normalizeKeepsLettersOfAnyScript() {
        assertEquals("bbc one", TitleMatch.normalize("  BBC-One! "))
        assertEquals("tom and jerry", TitleMatch.normalize("Tom & Jerry"))
        assertEquals("первый канал", TitleMatch.normalize("Первый канал"))
    }

    @Test
    fun stemsAreLikeSafeAndBounded() {
        val stems = TitleMatch.stems("100% Wolf's Den_")
        assertTrue(stems.none { '%' in it || '_' in it || '\'' in it })
        assertTrue(stems.size <= 6)
    }

    @Test
    fun exactTitleOutranksPrefixOutranksContains() {
        val exact = TitleMatch.score("dune", "Dune")
        val prefix = TitleMatch.score("dune", "Dune Part Two")
        val contains = TitleMatch.score("dune", "Children of Dune")
        assertTrue(exact > prefix && prefix > contains && contains > 0)
    }

    @Test
    fun typoStillMatches() {
        assertTrue(TitleMatch.score("intersteller", "Interstellar") > 0)
    }

    @Test
    fun unrelatedTitleScoresZero() {
        assertEquals(0, TitleMatch.score("dune", "Paddington"))
    }
}
