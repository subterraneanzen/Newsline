package ca.newsarchive.core.feed

import ca.newsarchive.core.feed.FeedParser.child
import ca.newsarchive.core.feed.FeedParser.childrenNamed
import ca.newsarchive.core.feed.FeedParser.local
import ca.newsarchive.core.feed.FeedParser.text
import ca.newsarchive.core.model.FeedItem
import ca.newsarchive.core.model.ParsedFeed
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.text.Dates
import org.jsoup.nodes.Element

/**
 * Parses XML sitemaps, including Google News sitemaps (news:news) which carry title and
 * publication date. Plain sitemaps only yield URLs + lastmod; those are treated as discovery
 * hints and article details come from extraction.
 */
object SitemapParser {
    fun parse(root: Element, baseUrl: String?): ParsedFeed {
        if (local(root) == "sitemapindex") {
            val children = childrenNamed(root, "sitemap").mapNotNull { text(child(it, "loc")) }
            return ParsedFeed(SourceKind.SITEMAP, null, baseUrl, null, null, emptyList(), children)
        }
        val items = childrenNamed(root, "url").mapNotNull { u ->
            val loc = text(child(u, "loc")) ?: return@mapNotNull null
            val news = child(u, "news:news")
            val lastmod = Dates.parse(text(child(u, "lastmod")))
            val image = child(u, "image:image")?.let { text(child(it, "image:loc")) }
            FeedItem(
                title = news?.let { text(child(it, "news:title")) },
                link = loc,
                guid = loc,
                publishedAt = news?.let { Dates.parse(text(child(it, "news:publication_date"))) },
                updatedAt = lastmod,
                author = null,
                summary = null,
                contentHtml = null,
                categories = news?.let { n -> text(child(n, "news:keywords"))?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } } ?: emptyList(),
                imageUrl = image,
            )
        }
        // Most recent first so a capped collection pass sees new articles.
        val sorted = items.sortedByDescending { it.publishedAt ?: it.updatedAt ?: 0L }
        return ParsedFeed(SourceKind.SITEMAP, null, baseUrl, null, null, sorted)
    }
}
