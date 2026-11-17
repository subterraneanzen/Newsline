package ca.newsarchive.core

import ca.newsarchive.core.geo.GeoLevel
import ca.newsarchive.core.geo.GeoTag
import ca.newsarchive.core.geo.UrlGeo
import ca.newsarchive.core.home.HomePlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlGeoTest {
    @Test fun readsCityAndProvinceFromBroadcasterPaths() {
        assertEquals("CA-SK:saskatoon", UrlGeo.placeFor("https://www.cbc.ca/news/canada/saskatoon/city-council-vote-1.7412345"))
        assertEquals("CA-SK", UrlGeo.placeFor("https://www.cbc.ca/news/canada/saskatchewan/legislature-1.7412346"))
        assertEquals("CA-SK:saskatoon", UrlGeo.placeFor("https://www.ctvnews.ca/saskatoon/article/bridge-closure/"))
        assertEquals("CA-SK:regina", UrlGeo.placeFor("https://globalnews.ca/regina/some-story/"))
        assertEquals("CA-BC", UrlGeo.placeFor("https://www.cbc.ca/news/canada/british-columbia/storm-1.1"))
    }

    @Test fun ignoresNationalPathsAndOtherPublishers() {
        assertNull(UrlGeo.placeFor("https://www.cbc.ca/news/politics/budget-1.7412347"))
        assertNull(UrlGeo.placeFor("https://www.ctvnews.ca/politics/article/ottawa-budget/"))
        assertNull(UrlGeo.placeFor("https://www.ckom.com/2025/02/24/neilburg-man-dead-after-rollover/"))
        assertNull(UrlGeo.placeFor("https://www.nytimes.com/saskatoon/story"))
    }

    @Test fun addressPlaceSendsStoryToLocalSection() {
        // Text mentions Canada strongly but never names the city; the address says Saskatoon.
        val text = listOf(GeoTag("CA", "Canada", GeoLevel.COUNTRY, 1.0))
        val tags = UrlGeo.apply(text, "https://www.cbc.ca/news/canada/saskatoon/story-1.2")
        assertEquals(setOf("CA-SK:saskatoon", "CA-SK", "CA", "C-NA"), tags.map { it.code }.toSet())
        val sections = listOf(
            HomePlacement.Place("LOCAL", "CA-SK:saskatoon"), HomePlacement.Place("CA-SK", "CA-SK"), HomePlacement.Place("CA", "CA"),
        )
        val c = HomePlacement.Candidate(1, 1, tags.map { HomePlacement.Tag(it.code, it.confidence, it.inferred) })
        assertEquals("LOCAL", HomePlacement.bestSection(sections, c)?.key)
    }
}
