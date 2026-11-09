package ca.newsarchive.app.ingest

import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.SourceEntity
import ca.newsarchive.app.data.SourceStatus
import ca.newsarchive.app.data.TelegramPostEntity
import ca.newsarchive.core.feed.ArticleLinkHarvester
import ca.newsarchive.core.feed.FeedParser
import ca.newsarchive.core.model.FeedItem
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.net.HttpFetcher
import ca.newsarchive.core.telegram.TelegramPreview
import ca.newsarchive.core.text.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceResult(val sourceId: Long, val newArticleIds: List<Long>, val error: String?)

/** Collects new articles from one source (feed, sitemap or scanned page). */
class Collector(
    private val db: AppDatabase,
    private val http: HttpFetcher,
    private val processor: ArticleProcessor,
) {
    /** Max new articles fully processed per source per run; the rest are picked up next run. */
    private val perSourceCap = 30

    suspend fun collect(source: SourceEntity, force: Boolean, onArticle: (Long) -> Unit = {}): SourceResult {
        val now = System.currentTimeMillis()
        val attempt = source.copy(lastAttemptAt = now)
        val res = try {
            http.get(source.url, etag = source.etag.takeUnless { force }, lastModified = source.lastModifiedHeader.takeUnless { force })
        } catch (e: Exception) {
            return fail(attempt, e.message ?: e.javaClass.simpleName)
        }
        if (res.notModified) {
            db.sources().update(attempt.copy(lastSuccessAt = now, lastError = null, consecutiveFailures = 0, status = okStatus(source)))
            return SourceResult(source.id, emptyList(), null)
        }
        if (!res.ok) return fail(attempt, "HTTP ${res.status}")

        val items: List<FeedItem> = try {
            when (SourceKind.valueOf(source.kind)) {
                SourceKind.WEBSITE -> ArticleLinkHarvester.harvest(res.text(), res.finalUrl)
                else -> {
                    val parsed = FeedParser.parse(res.text(), res.finalUrl)
                    if (parsed.childSitemaps.isNotEmpty()) {
                        // Sitemap index: newest child sitemaps are usually listed last or named "news".
                        val children = parsed.childSitemaps.sortedByDescending { if ("news" in it) 1 else 0 }.take(2)
                        children.flatMap { c -> runCatching { FeedParser.parse(http.get(c).text(), c).items }.getOrDefault(emptyList()) }
                    } else parsed.items
                }
            }
        } catch (e: Exception) {
            return fail(attempt, "Couldn't read the feed: ${e.message ?: e.javaClass.simpleName}")
        }

        val fresh = items.filter { it.link?.startsWith("http") == true }
            .distinctBy { UrlCanonicalizer.canonicalize(it.link!!) }
            .filter { db.articles().byCanonical(UrlCanonicalizer.canonicalize(it.link!!)) == null }
            .sortedByDescending { it.publishedAt ?: 0L }
            .take(perSourceCap)

        val newIds = mutableListOf<Long>()
        for (item in fresh) {
            val id = runCatching { processor.ingest(source, item) }.getOrNull()
            if (id != null) { newIds += id; onArticle(id) }
        }
        db.sources().update(
            attempt.copy(
                lastSuccessAt = now, lastError = null, consecutiveFailures = 0,
                lastNewArticleAt = if (newIds.isNotEmpty()) now else source.lastNewArticleAt,
                etag = res.etag, lastModifiedHeader = res.lastModified,
                status = okStatus(source),
                statusNote = when {
                    source.kind == SourceKind.WEBSITE.name -> "No feed: collected by scanning the page for article links (partial support)."
                    items.isEmpty() -> "The feed was reachable but contained no items."
                    else -> null
                },
            ),
        )
        return SourceResult(source.id, newIds, null)
    }

    private fun okStatus(s: SourceEntity) = if (s.kind == SourceKind.WEBSITE.name) SourceStatus.PARTIAL else SourceStatus.OK

    private suspend fun fail(s: SourceEntity, msg: String): SourceResult {
        val failures = s.consecutiveFailures + 1
        db.sources().update(
            s.copy(
                lastError = msg, consecutiveFailures = failures,
                status = if (failures >= 5) SourceStatus.UNAVAILABLE else SourceStatus.ERROR,
                statusNote = if (failures >= 5) "Failed $failures times in a row. The feed may have moved or been discontinued." else null,
            ),
        )
        return SourceResult(s.id, emptyList(), msg)
    }
}

/** Collects posts from followed public Telegram channels via their web preview. */
class TelegramCollector(private val db: AppDatabase, private val http: HttpFetcher) {
    suspend fun collectAll(): Int {
        var total = 0
        for (c in db.telegram().followed()) total += collect(c.username)
        return total
    }

    suspend fun collect(username: String): Int = withContext(Dispatchers.IO) { collectInternal(username) }

    private suspend fun collectInternal(username: String): Int {
        val ch = db.telegram().get(username) ?: return 0
        val now = System.currentTimeMillis()
        val res = try { http.get(TelegramPreview.previewUrl(username), accept = "text/html") } catch (e: Exception) {
            db.telegram().updateChannel(ch.copy(lastFetchAt = now, status = SourceStatus.ERROR, statusNote = e.message ?: "Network error"))
            return 0
        }
        if (!res.ok) {
            db.telegram().updateChannel(ch.copy(lastFetchAt = now, status = SourceStatus.ERROR, statusNote = "HTTP ${res.status}"))
            return 0
        }
        val page = TelegramPreview.parse(res.text(), username)
        if (!page.previewAvailable || (page.posts.isEmpty() && page.title == null)) {
            db.telegram().updateChannel(
                ch.copy(lastFetchAt = now, status = SourceStatus.UNAVAILABLE,
                    statusNote = "Telegram does not provide a public web preview for this channel (it may be private, a group, or have previews disabled)."),
            )
            return 0
        }
        val inserted = db.telegram().insertPosts(page.posts.map {
            TelegramPostEntity(it.channel.lowercase().let { c -> if (c == username.lowercase()) username else it.channel }, it.postId, it.text,
                it.postedAt, it.url, it.views, it.hasMedia, it.imageUrl, it.linkUrls.joinToString("\n"), now)
        }).count { it != -1L }
        db.telegram().updateChannel(
            ch.copy(
                title = page.title ?: ch.title, description = page.description ?: ch.description,
                lastFetchAt = now, lastSuccessAt = now, status = SourceStatus.OK,
                statusNote = page.subscribers?.let { "$it subscribers" },
                newestPostId = page.posts.maxOfOrNull { it.postId } ?: ch.newestPostId,
            ),
        )
        return inserted
    }
}
