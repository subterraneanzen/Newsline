package ca.newsarchive.core

import ca.newsarchive.core.home.HomePlacement
import ca.newsarchive.core.home.HomePlacement.Candidate
import ca.newsarchive.core.home.HomePlacement.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

class HomePlacementTest {
    private val sections = listOf(
        HomePlacement.Place("LOCAL", "CA-SK:regina"), HomePlacement.Place("CA-SK", "CA-SK"),
        HomePlacement.Place("CA", "CA"), HomePlacement.Place("US", "US"),
        HomePlacement.Place("SPACE", "SPACE"), HomePlacement.Updated("UPDATED"), HomePlacement.CatchAll("WORLD"),
    )
    private fun place(vararg t: Tag, versions: Int = 1) = HomePlacement.bestSection(sections, Candidate(1, versions, t.toList()))?.key

    @Test fun mostSpecificPlaceWins() {
        assertEquals("LOCAL", place(Tag("CA-SK:regina", 0.9, false), Tag("CA-SK", 0.9, true), Tag("CA", 0.9, true)))
        assertEquals("CA-SK", place(Tag("CA-SK:saskatoon", 0.9, false), Tag("CA", 0.9, true)))
        assertEquals("CA", place(Tag("CA-ON:ottawa", 0.8, false)))
    }

    @Test fun strongestPlaceWinsAcrossCountries() {
        assertEquals("US", place(Tag("US", 0.9, false), Tag("CA-SK", 0.5, false)))
        assertEquals("CA-SK", place(Tag("US", 0.5, false), Tag("CA-SK", 0.9, false)))
        assertEquals("SPACE", place(Tag("SPACE", 0.8, false), Tag("US", 0.4, false)))
    }

    @Test fun fallbacks() {
        assertEquals("UPDATED", place(Tag("FR", 0.9, false), versions = 3))
        assertEquals("WORLD", place(Tag("FR", 0.9, false)))
        assertEquals("WORLD", place())
    }

    @Test fun eachArticleShownOnce() {
        val a = Candidate(1, 1, listOf(Tag("CA-SK:regina", 0.9, false)))
        val b = Candidate(2, 1, listOf(Tag("CA", 0.9, false)))
        val r = HomePlacement.assign(sections, listOf(a, b, a))
        assertEquals(listOf(1L), r["LOCAL"]); assertEquals(listOf(2L), r["CA"]); assertEquals(emptyList(), r["CA-SK"])
        assertEquals(2, r.values.sumOf { it.size })
    }
}
