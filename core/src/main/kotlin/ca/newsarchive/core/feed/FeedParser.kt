package ca.newsarchive.core.feed

import ca.newsarchive.core.model.FeedItem
import ca.newsarchive.core.model.ParsedFeed
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.text.Dates
import ca.newsarchive.core.text.TextNormalizer
import ca.newsarchive.core.text.UrlCanonicalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class FeedParseException(message: String) : Exception(message)

/**
 * Parses RSS 2.0, RSS 1.0 (RDF), Atom and XML sitemaps. Uses jsoup's lenient XML parser
 * because real-world feeds are frequently not well-formed.
 */
object FeedParser {

    fun looksLikeFeed(body: String): Boolean {
        val head = body.take(2048).lowercase()
        return "<rss" in head || "<feed" in head || "<rdf:rdf" in head || "<urlset" in head || "<sitemapindex" in head
    }

    fun parse(xml: String, baseUrl: String? = null): ParsedFeed {
        val doc = Jsoup.parse(xml.trimStart('﻿', ' ', '\n', '\r', '\t'), baseUrl ?: "", Parser.xmlParser())
        val root = doc.children().firstOrNull { it.tagName() != "?xml" }
            ?: throw FeedParseException("Empty document")
        return when (local(root)) {
            "rss" -> parseRss(root, baseUrl, SourceKind.RSS)
            "rdf" -> parseRss(root, baseUrl, SourceKind.RDF)
            "feed" -> parseAtom(root, baseUrl)
            "urlset", "sitemapindex" -> SitemapParser.parse(root, baseUrl)
            else -> throw FeedParseException("Not a feed: root element <${root.tagName()}>")
        }
    }

    internal fun local(e: Element): String = e.tagName().substringAfter(':').lowercase()

    internal fun child(e: Element, vararg names: String): Element? =
        e.children().firstOrNull { c -> names.any { n -> c.tagName().equals(n, true) } }

    internal fun childrenNamed(e: Element, vararg names: String): List<Element> =
        e.children().filter { c -> names.any { n -> c.tagName().equals(n, true) } }

    internal fun text(e: Element?): String? = e?.let { el ->
        // CDATA and entity-escaped HTML both end up in wholeText(); collapse it.
        TextNormalizer.clean(el.wholeText()).ifBlank { null }
    }

    private fun parseRss(root: Element, baseUrl: String?, kind: SourceKind): ParsedFeed {
        val channel = child(root, "channel") ?: root
        // RSS 1.0 puts <item> beside <channel>; RSS 2.0 puts them inside.
        val itemEls = childrenNamed(channel, "item").ifEmpty { childrenNamed(root, "item") }
        val siteLink = text(child(channel, "link"))
        val items = itemEls.map { parseRssItem(it, siteLink ?: baseUrl) }
        return ParsedFeed(
            kind = kind,
            title = text(child(channel, "title")),
            siteLink = siteLink,
            description = text(child(channel, "description"))?.let(::htmlToText),
            language = text(child(channel, "language", "dc:language")),
            items = items,
        )
    }

    private fun parseRssItem(it: Element, base: String?): FeedItem {
        val link = text(child(it, "link"))
            ?: child(it, "guid")?.takeIf { g -> g.attr("isPermaLink") != "false" }?.let(::text)?.takeIf { s -> s.startsWith("http") }
            ?: it.attr("rdf:about").ifBlank { null }
        val contentHtml = text(child(it, "content:encoded"))
        val desc = child(it, "description")?.wholeText()?.trim()?.ifBlank { null }
        return FeedItem(
            title = text(child(it, "title"))?.let(::htmlToText),
            link = UrlCanonicalizer.resolve(base, link),
            guid = text(child(it, "guid")),
            publishedAt = Dates.parse(text(child(it, "pubDate", "dc:date", "published"))),
            updatedAt = Dates.parse(text(child(it, "atom:updated", "dcterms:modified", "updated"))),
            author = (text(child(it, "dc:creator")) ?: text(child(it, "author")))?.let(::cleanAuthor),
            summary = desc?.let(::htmlToText)?.ifBlank { null },
            contentHtml = contentHtml,
            categories = childrenNamed(it, "category", "dc:subject").mapNotNull { c -> text(c) }.distinct(),
            imageUrl = imageOf(it, desc ?: contentHtml),
        )
    }

    private fun parseAtom(root: Element, baseUrl: String?): ParsedFeed {
        val siteLink = atomLink(root)
        val entries = childrenNamed(root, "entry").map { e ->
            val content = child(e, "content")
            val summary = child(e, "summary")
            FeedItem(
                title = text(child(e, "title"))?.let(::htmlToText),
                link = UrlCanonicalizer.resolve(siteLink ?: baseUrl, atomLink(e)),
                guid = text(child(e, "id")),
                publishedAt = Dates.parse(text(child(e, "published", "issued"))) ?: Dates.parse(text(child(e, "updated"))),
                updatedAt = Dates.parse(text(child(e, "updated", "modified"))),
                author = child(e, "author")?.let { a -> text(child(a, "name")) ?: text(a) }?.let(::cleanAuthor),
                summary = (text(summary) ?: text(content))?.let(::htmlToText)?.take(1200),
                contentHtml = content?.wholeText()?.trim()?.ifBlank { null },
                categories = childrenNamed(e, "category").mapNotNull { c -> c.attr("term").ifBlank { c.attr("label") }.ifBlank { null } },
                imageUrl = imageOf(e, content?.wholeText() ?: summary?.wholeText()),
            )
        }
        return ParsedFeed(
            kind = SourceKind.ATOM,
            title = text(child(root, "title")),
            siteLink = siteLink,
            description = text(child(root, "subtitle")),
            language = root.attr("xml:lang").ifBlank { null },
            items = entries,
        )
    }

    private fun atomLink(e: Element): String? {
        val links = childrenNamed(e, "link")
        val alt = links.firstOrNull { it.attr("rel").isBlank() || it.attr("rel") == "alternate" }
        return (alt ?: links.firstOrNull())?.attr("href")?.ifBlank { null }
    }

    private fun imageOf(item: Element, html: String?): String? {
        childrenNamed(item, "media:content", "media:thumbnail").firstOrNull { m ->
            val type = m.attr("type"); val medium = m.attr("medium")
            m.attr("url").isNotBlank() && (type.startsWith("image") || medium == "image" || m.tagName().endsWith("thumbnail") || (type.isBlank() && medium.isBlank()))
        }?.let { return it.attr("url") }
        child(item, "media:group")?.let { g -> childrenNamed(g, "media:content", "media:thumbnail").firstOrNull()?.attr("url")?.ifBlank { null }?.let { return it } }
        child(item, "enclosure")?.takeIf { it.attr("type").startsWith("image") }?.attr("url")?.ifBlank { null }?.let { return it }
        if (html != null && "<img" in html) {
            return Jsoup.parse(html).selectFirst("img[src]")?.attr("src")?.takeIf { it.startsWith("http") }
        }
        return null
    }

    fun htmlToText(s: String): String =
        if ('<' in s || '&' in s) TextNormalizer.clean(Jsoup.parse(s).text()) else TextNormalizer.clean(s)

    private fun cleanAuthor(a: String): String? {
        // RSS <author> is often "email@x.com (Name)".
        val m = Regex("^[^@\\s]+@[^\\s]+\\s*\\((.+)\\)$").find(a)
        val v = (m?.groupValues?.get(1) ?: a).trim()
        return v.takeUnless { it.contains('@') && !it.contains(' ') }?.ifBlank { null }
    }

    /** Finds feeds advertised by an HTML page via <link rel="alternate">. */
    fun discoverFeeds(html: String, pageUrl: String): List<String> {
        val doc: Document = Jsoup.parse(html, pageUrl)
        return doc.select("link[rel~=(?i)alternate][type~=(?i)(rss|atom)\\+xml], link[rel~=(?i)alternate][type~=(?i)application/feed\\+json]")
            .mapNotNull { it.absUrl("href").ifBlank { null } }
            .filterNot { it.contains("comments/feed") || it.contains("/comments") }
            .distinct()
    }
}
