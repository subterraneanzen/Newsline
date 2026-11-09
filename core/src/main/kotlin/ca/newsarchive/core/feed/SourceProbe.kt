package ca.newsarchive.core.feed

import ca.newsarchive.core.model.FeedItem
import ca.newsarchive.core.model.ParsedFeed
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.net.HttpFetcher
import ca.newsarchive.core.text.UrlCanonicalizer
import org.jsoup.Jsoup

data class ProbeResult(
    val ok: Boolean,
    val kind: SourceKind?,
    val feedUrl: String?,
    val title: String?,
    val siteUrl: String?,
    val itemCount: Int,
    val message: String,
    /** Other feeds advertised on the page, for the user to choose from. */
    val alternatives: List<String> = emptyList(),
)

/** Validates a user-supplied URL and works out how the app can collect from it. */
class SourceProbe(private val http: HttpFetcher) {

    fun probe(input: String): ProbeResult {
        val url = normalizeInput(input) ?: return ProbeResult(false, null, null, null, null, 0, "That doesn't look like a web address.")
        val res = runCatching { http.get(url) }.getOrElse {
            return ProbeResult(false, null, url, null, null, 0, "Couldn't reach the site: ${it.message ?: it.javaClass.simpleName}")
        }
        if (!res.ok) return ProbeResult(false, null, url, null, null, 0, "The site answered with HTTP ${res.status}.")
        val body = res.text()
        if (FeedParser.looksLikeFeed(body)) {
            return runCatching { fromFeed(res.finalUrl, FeedParser.parse(body, res.finalUrl)) }
                .getOrElse { ProbeResult(false, null, res.finalUrl, null, null, 0, "This looks like a feed but couldn't be read: ${it.message}") }
        }
        // HTML page: look for advertised feeds, then conventional locations.
        val advertised = FeedParser.discoverFeeds(body, res.finalUrl)
        val guesses = listOf("feed", "rss", "rss.xml", "feed.xml", "atom.xml", "index.xml", "feeds/all.rss.xml", "news-sitemap.xml", "sitemap_news.xml")
            .mapNotNull { UrlCanonicalizer.resolve(rootOf(res.finalUrl), it) }
        for (candidate in (advertised + guesses).distinct()) {
            val r = runCatching { http.get(candidate) }.getOrNull() ?: continue
            if (!r.ok) continue
            val t = r.text()
            if (!FeedParser.looksLikeFeed(t)) continue
            val parsed = runCatching { FeedParser.parse(t, r.finalUrl) }.getOrNull() ?: continue
            if (parsed.items.isEmpty() && parsed.childSitemaps.isEmpty()) continue
            return fromFeed(r.finalUrl, parsed).copy(alternatives = advertised.filter { it != candidate })
        }
        // No feed: offer page scanning, which is less reliable.
        val links = ArticleLinkHarvester.harvest(body, res.finalUrl)
        val title = Jsoup.parse(body).title().ifBlank { null }
        return if (links.size >= 3) ProbeResult(
            true, SourceKind.WEBSITE, res.finalUrl, title, res.finalUrl, links.size,
            "No RSS/Atom feed found. The app can scan this page for article links (partial support: less reliable than a feed).",
        ) else ProbeResult(false, null, res.finalUrl, title, res.finalUrl, 0, "No feed or recognisable article links were found on this page.")
    }

    private fun fromFeed(url: String, f: ParsedFeed): ProbeResult {
        val count = f.items.size + f.childSitemaps.size
        val msg = when {
            f.kind == SourceKind.SITEMAP && f.childSitemaps.isNotEmpty() -> "Sitemap index with ${f.childSitemaps.size} sitemaps."
            f.kind == SourceKind.SITEMAP -> "Sitemap with ${f.items.size} article links."
            count == 0 -> "Valid ${f.kind.name} feed, but it currently has no items."
            else -> "Valid ${f.kind.name} feed with ${f.items.size} items."
        }
        return ProbeResult(true, f.kind, url, f.title, f.siteLink, count, msg)
    }

    companion object {
        fun normalizeInput(input: String): String? {
            var s = input.trim()
            if (s.isEmpty() || ' ' in s) return null
            if (s.startsWith("feed://")) s = "https://" + s.removePrefix("feed://")
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.takeIf { UrlCanonicalizer.host(it)?.contains('.') == true }
        }

        fun rootOf(url: String): String = UrlCanonicalizer.host(url)?.let { h ->
            val scheme = url.substringBefore("://")
            "$scheme://${java.net.URI(url).host}/"
        } ?: url
    }
}

/** Finds likely article links on a publisher's front/section page (used when no feed exists). */
object ArticleLinkHarvester {
    private val datePath = Regex("/(19|20)\\d{2}/\\d{1,2}/")
    private val skip = Regex("/(tag|tags|category|categories|author|authors|topic|topics|section|search|login|subscribe|account|about|contact|privacy|terms|video|videos|podcast|podcasts|live)(/|$)", RegexOption.IGNORE_CASE)

    fun harvest(html: String, pageUrl: String): List<FeedItem> {
        val doc = Jsoup.parse(html, pageUrl)
        val host = UrlCanonicalizer.host(pageUrl) ?: return emptyList()
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").substringBefore('#')
            val h = UrlCanonicalizer.host(href) ?: return@mapNotNull null
            if (h != host && !h.endsWith(".$host")) return@mapNotNull null
            val path = runCatching { java.net.URI(href).path }.getOrNull() ?: return@mapNotNull null
            if (skip.containsMatchIn(path)) return@mapNotNull null
            val slug = path.trimEnd('/').substringAfterLast('/')
            val looksLikeArticle = datePath.containsMatchIn(path) || slug.count { it == '-' } >= 3 || Regex("\\d{6,}").containsMatchIn(slug)
            val text = a.text().trim()
            if (!looksLikeArticle || text.length < 20) return@mapNotNull null
            FeedItem(text, href, href, null, null, null, null, null)
        }.distinctBy { UrlCanonicalizer.canonicalize(it.link!!) }.take(60)
    }
}
