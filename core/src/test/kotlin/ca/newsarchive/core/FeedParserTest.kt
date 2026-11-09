package ca.newsarchive.core

import ca.newsarchive.core.feed.FeedParser
import ca.newsarchive.core.feed.Opml
import ca.newsarchive.core.feed.OpmlEntry
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.text.Dates
import ca.newsarchive.core.text.UrlCanonicalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParserTest {
    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
<channel><title>Example News</title><link>https://example.com/</link><description>Latest</description><language>en-ca</language>
<item><title><![CDATA[Regina council approves budget &amp; tax plan]]></title>
<link>https://www.example.com/news/regina-budget?utm_source=rss&amp;utm_medium=feed&amp;id=42</link>
<guid isPermaLink="false">abc-123</guid>
<pubDate>Mon, 09 Nov 2026 14:30:00 CST</pubDate>
<dc:creator>Jane Doe</dc:creator>
<description>&lt;p&gt;The council voted &lt;b&gt;7-4&lt;/b&gt;.&lt;/p&gt;</description>
<content:encoded><![CDATA[<p>First paragraph of the article.</p><p>Second paragraph.</p>]]></content:encoded>
<category>Politics</category><category>Regina</category>
<media:content url="https://example.com/img.jpg" medium="image"/>
</item>
<item><title>No date item</title><link>https://example.com/b</link></item>
</channel></rss>"""

    @Test fun parsesRss2() {
        val f = FeedParser.parse(rss)
        assertEquals(SourceKind.RSS, f.kind)
        assertEquals("Example News", f.title)
        assertEquals(2, f.items.size)
        val i = f.items[0]
        assertEquals("Regina council approves budget & tax plan", i.title)
        assertEquals("Jane Doe", i.author)
        assertEquals("The council voted 7-4.", i.summary)
        assertEquals(listOf("Politics", "Regina"), i.categories)
        assertEquals("https://example.com/img.jpg", i.imageUrl)
        assertEquals(Dates.parse("2026-11-09T20:30:00Z"), i.publishedAt)
        assertTrue(i.contentHtml!!.contains("Second paragraph"))
        assertNull(f.items[1].publishedAt)
    }

    @Test fun parsesAtom() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom Site</title><link href="https://a.org/"/>
            <entry><title type="html">Hello &amp;lt;World&amp;gt;</title><link rel="alternate" href="/post/1"/><id>tag:a.org,1</id>
            <published>2026-11-01T10:00:00+01:00</published><updated>2026-11-02T10:00:00Z</updated>
            <author><name>Sam</name></author><summary>Short</summary><category term="Science"/></entry></feed>"""
        val f = FeedParser.parse(atom, "https://a.org/feed")
        assertEquals(SourceKind.ATOM, f.kind)
        val e = f.items.single()
        assertEquals("https://a.org/post/1", e.link)
        assertEquals("Sam", e.author)
        assertEquals(Dates.parse("2026-11-01T09:00:00Z"), e.publishedAt)
        assertEquals(listOf("Science"), e.categories)
    }

    @Test fun parsesRdf() {
        val rdf = """<?xml version="1.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <channel rdf:about="https://dw.com"><title>DW</title><link>https://dw.com</link></channel>
            <item rdf:about="https://dw.com/a"><title>Item A</title><link>https://dw.com/a</link><dc:date>2026-11-05T08:00:00Z</dc:date></item>
            </rdf:RDF>"""
        val f = FeedParser.parse(rdf)
        assertEquals(SourceKind.RDF, f.kind)
        assertEquals("Item A", f.items.single().title)
        assertNotNull(f.items.single().publishedAt)
    }

    @Test fun parsesNewsSitemap() {
        val sm = """<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
            <url><loc>https://x.com/old</loc><news:news><news:publication_date>2026-11-01T00:00:00Z</news:publication_date><news:title>Old</news:title></news:news></url>
            <url><loc>https://x.com/new</loc><news:news><news:publication_date>2026-11-08T00:00:00Z</news:publication_date><news:title>New</news:title></news:news></url>
            </urlset>"""
        val f = FeedParser.parse(sm)
        assertEquals(SourceKind.SITEMAP, f.kind)
        assertEquals("New", f.items.first().title)
    }

    @Test fun discoversFeedLinks() {
        val html = """<html><head><link rel="alternate" type="application/rss+xml" href="/feed"/><link rel="alternate" type="application/rss+xml" href="/comments/feed"/></head></html>"""
        assertEquals(listOf("https://site.ca/feed"), FeedParser.discoverFeeds(html, "https://site.ca/news"))
    }

    @Test fun opmlRoundTrip() {
        val entries = listOf(OpmlEntry("A & B", "https://a.com/rss", "https://a.com", "Canada"), OpmlEntry("C", "https://c.com/feed", null, null))
        val parsed = Opml.parse(Opml.write("Test", entries))
        assertEquals(2, parsed.size)
        assertEquals("A & B", parsed.first { it.xmlUrl == "https://a.com/rss" }.title)
        assertEquals("Canada", parsed.first { it.xmlUrl == "https://a.com/rss" }.category)
    }

    @Test fun datesInVariousFormats() {
        val expected = Dates.parse("2026-11-09T14:30:00Z")
        assertEquals(expected, Dates.parse("Mon, 09 Nov 2026 14:30:00 GMT"))
        assertEquals(expected, Dates.parse("Mon, 9 Nov 2026 14:30:00 +0000"))
        assertEquals(expected, Dates.parse("2026-11-09T09:30:00-05:00"))
        assertEquals(expected, Dates.parse("2026-11-09T14:30:00.000Z"))
        assertEquals(expected, Dates.parse("Mon, 09 Nov 2026 08:30:00 CST"))
        assertNull(Dates.parse("yesterday"))
    }

    @Test fun canonicalUrlsStripTracking() {
        assertEquals(
            "https://example.com/news/regina-budget?id=42",
            UrlCanonicalizer.canonicalize("http://www.example.com/news/regina-budget/?utm_source=rss&id=42&fbclid=x#top"),
        )
        assertEquals(UrlCanonicalizer.canonicalize("https://m.cbc.ca/a/b/amp"), UrlCanonicalizer.canonicalize("https://www.cbc.ca/a/b"))
    }
}
