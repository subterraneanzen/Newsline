package ca.newsarchive.core

import ca.newsarchive.core.cluster.StoryClusterer
import ca.newsarchive.core.cluster.StorySignature
import ca.newsarchive.core.diff.ChangeType
import ca.newsarchive.core.diff.VersionDiff
import ca.newsarchive.core.diff.VersionSnapshot
import ca.newsarchive.core.geo.GeoClassifier
import ca.newsarchive.core.geo.GeoLevel
import ca.newsarchive.core.topic.TopicClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisTest {
    private val h = 3_600_000L

    @Test fun diffDetectsAddRemoveModifyAndHeadline() {
        val old = VersionSnapshot("Fire forces evacuation", "A. Writer", null, listOf(
            "A wildfire forced residents of La Ronge to leave their homes on Tuesday.",
            "Officials said about 200 people were affected.",
            "The cause is unknown.",
        ))
        val new = VersionSnapshot("Wildfire forces evacuation of La Ronge", "A. Writer", 5L, listOf(
            "A wildfire forced residents of La Ronge to leave their homes on Tuesday.",
            "Officials said about 2,000 people were affected by the order.",
            "Crews from Manitoba arrived overnight to help.",
        ))
        val c = VersionDiff.compare(old, new)
        assertTrue(c.headlineChanged)
        assertEquals(listOf(ChangeType.UNCHANGED, ChangeType.REMOVED, ChangeType.MODIFIED, ChangeType.ADDED).sorted(), c.blocks.map { it.type }.sorted())
        val mod = c.blocks.first { it.type == ChangeType.MODIFIED }
        assertTrue(mod.spans.any { it.type == ChangeType.ADDED && it.text.contains("2,000") })
        assertTrue(mod.spans.any { it.type == ChangeType.REMOVED && it.text.contains("200") })
        assertTrue(c.summary().startsWith("Headline changed"))
    }

    @Test fun formattingOnlyChangesAreNotMeaningful() {
        val a = VersionSnapshot("It’s “here”", null, null, listOf("Hello  world — again", "Second"))
        val b = VersionSnapshot("It's \"here\"", null, null, listOf("Hello world - again", " Second "))
        assertFalse(VersionDiff.isMeaningfulChange(a, b))
        assertTrue(VersionDiff.isMeaningfulChange(a, b.copy(paragraphs = listOf("Hello world - again", "Second", "Third"))))
    }

    @Test fun clustersSameStoryAcrossPublishers() {
        val t = 1_800_000_000_000L
        val a = StoryClusterer.signature("Saskatchewan Premier Scott Moe announces cabinet shuffle",
            "Premier Scott Moe named five new ministers in Regina on Tuesday, moving Jeremy Harrison to finance.", setOf("CA-SK"), t)
        val b = StoryClusterer.signature("Moe shuffles Saskatchewan cabinet, Harrison takes finance",
            "Scott Moe unveiled a cabinet shuffle Tuesday in Regina. Jeremy Harrison becomes finance minister.", setOf("CA-SK"), t + 3 * h)
        val unrelated = StoryClusterer.signature("Saskatchewan Roughriders win Western Final",
            "The Roughriders beat Winnipeg in Regina on Sunday to advance to the Grey Cup.", setOf("CA-SK"), t + 2 * h)
        val sameKeywordsDifferentStory = StoryClusterer.signature("Manitoba premier announces cabinet shuffle",
            "Premier Wab Kinew named new ministers in Winnipeg on Tuesday.", setOf("CA-MB"), t + h)
        assertTrue(StoryClusterer.similarity(a, b) >= StoryClusterer.THRESHOLD, "same story score ${StoryClusterer.similarity(a, b)}")
        assertTrue(StoryClusterer.similarity(a, unrelated) < StoryClusterer.THRESHOLD, "unrelated ${StoryClusterer.similarity(a, unrelated)}")
        assertTrue(StoryClusterer.similarity(a, sameKeywordsDifferentStory) < StoryClusterer.THRESHOLD, "keyword-only ${StoryClusterer.similarity(a, sameKeywordsDifferentStory)}")
        assertEquals(1L, StoryClusterer.assign(b, mapOf(1L to listOf(a), 2L to listOf(unrelated)))?.clusterId)
        assertNull(StoryClusterer.assign(sameKeywordsDifferentStory, mapOf(1L to listOf(a, b))))
        val far = b.copy(time = t + 10 * 24 * h)
        assertEquals(0.0, StoryClusterer.similarity(a, far))
        assertEquals(a, StorySignature.deserialize(a.serialize()))
    }

    @Test fun geoClassifiesFromTextNotPublisher() {
        val tags = GeoClassifier.classify("Saskatoon police investigate downtown fire",
            listOf("Saskatoon Fire Department crews responded on 20th Street.", "No injuries were reported in the Saskatchewan city."))
        val codes = tags.map { it.code }
        assertTrue("CA-SK:saskatoon" in codes, codes.toString())
        assertTrue("CA-SK" in codes && "CA" in codes && "C-NA" in codes, codes.toString())
        assertFalse(tags.any { it.inferred })

        // A local publisher writing about Ukraine should not be tagged Saskatchewan.
        val world = GeoClassifier.classify("Ukraine says drones hit Kyiv overnight", listOf("Ukrainian officials said Russian drones struck Kyiv."), localSourcePlace = "CA-SK:regina")
        assertTrue(world.any { it.code == "UA" })
        assertFalse(world.any { it.code.startsWith("CA") }, world.toString())

        // No place named: fall back to the local source's home, marked inferred.
        val fallback = GeoClassifier.classify("Council debates snow-clearing budget", listOf("Councillors argued for two hours."), localSourcePlace = "CA-SK:regina")
        assertTrue(fallback.all { it.inferred } && fallback.any { it.code == "CA-SK:regina" })
    }

    @Test fun geoHandlesAmbiguityAndLongestMatch() {
        val ga = GeoClassifier.mentions("Georgia voters in Atlanta lined up, the U.S. secretary said.").map { it.first }
        assertTrue("US-GA" in ga && "GE" !in ga, ga.toString())
        val png = GeoClassifier.mentions("Papua New Guinea and Equatorial Guinea").map { it.first }
        assertEquals(listOf("PG", "GQ"), png)
        val jordan = GeoClassifier.mentions("Michael Jordan scored.").map { it.first }
        assertTrue(jordan.isEmpty(), jordan.toString())
        val dc = GeoClassifier.mentions("Officials in Washington D.C. met.").map { it.first }
        assertEquals(listOf("US-DC"), dc)
    }

    @Test fun spaceDetection() {
        val tags = GeoClassifier.classify("NASA's Artemis astronauts prepare for lunar flyby", listOf("The Orion spacecraft will orbit the Moon."))
        assertNotNull(tags.firstOrNull { it.level == GeoLevel.SPACE })
        assertNull(GeoClassifier.classify("Mars bars price rises", listOf("Chocolate costs more.")).firstOrNull { it.level == GeoLevel.SPACE })
    }

    @Test fun topics() {
        val t = TopicClassifier.classify("Farmers rush to finish harvest before snowstorm",
            listOf("Canola and wheat crops remain in fields across Saskatchewan as Environment Canada issues a snowfall warning."))
        val names = t.map { it.topic }
        assertTrue("Agriculture" in names, names.toString())
        assertTrue("Weather" in names, names.toString())
        assertFalse("Sports" in names)
    }

    @Test fun spaceDefenseIsNotWarNews() {
        fun names(title: String, vararg p: String) = TopicClassifier.classify(title, p.toList()).map { it.topic }
        // War reporting stays world news (Conflict & Security), not Defense.
        val war = names("Russian drones hit Kyiv as Ukraine's army pushes back",
            "Ukraine's military said its air force shot down 40 drones overnight.", "NATO allies condemned the attack, and troops remain on alert.")
        assertFalse("Defense & Military" in war, war.toString())
        assertTrue("Conflict & Security" in war, war.toString())
        // Remembrance Day ceremonies are not defense news.
        val poppy = names("Veterans honoured at Remembrance Day ceremony in Regina", "Hundreds gathered to remember soldiers and veterans; army cadets laid wreaths.")
        assertFalse("Defense & Military" in poppy, poppy.toString())
        // Real defense news still qualifies.
        val ships = names("Canada picks builder for new submarines", "The Department of National Defence says the Royal Canadian Navy fleet will be replaced.")
        assertTrue("Defense & Military" in ships, ships.toString())
        // Satellite photos of a war zone are not space news.
        val sat = GeoClassifier.classify("Satellite images show damage at Iranian base", listOf("New satellite photos reveal craters.", "Satellites captured the site."))
        assertNull(sat.firstOrNull { it.level == GeoLevel.SPACE })
        assertFalse("Space" in names("Satellite images show damage", "The space between buildings was destroyed; satellites recorded it."))
        assertTrue("Space" in names("NASA astronauts return from space station", "The SpaceX capsule splashed down."))
    }
}
