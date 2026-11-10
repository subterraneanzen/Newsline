package ca.newsarchive.app.data

import ca.newsarchive.app.ingest.ArticleProcessor
import ca.newsarchive.app.ingest.Maintenance
import ca.newsarchive.app.ingest.StoryService
import ca.newsarchive.core.feed.OpmlEntry
import ca.newsarchive.core.feed.ProbeResult
import ca.newsarchive.core.feed.SourceProbe
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.geo.GeoLevel
import ca.newsarchive.core.model.SourceKind
import ca.newsarchive.core.sources.DirectorySource
import ca.newsarchive.core.sources.SourceDirectory
import ca.newsarchive.core.telegram.TelegramDirectory
import ca.newsarchive.core.telegram.TelegramPreview
import ca.newsarchive.core.text.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AddResult {
    data class Added(val id: Long) : AddResult
    data class Duplicate(val existing: SourceEntity) : AddResult
    data class Invalid(val message: String) : AddResult
}

/** Write operations used by the UI. Reads are done directly through DAO flows. */
class Repository(
    private val db: AppDatabase,
    private val probe: SourceProbe,
    private val stories: StoryService,
    private val maintenance: Maintenance,
) {
    private val now get() = System.currentTimeMillis()

    // ---- Sources ---------------------------------------------------------------------------

    suspend fun seedStarterSources() {
        if (db.sources().count() > 0) return
        db.sources().insertAll(SourceDirectory.all.filter { it.starter }.map { it.toEntity(enabled = true) })
    }

    /**
     * Brings an existing install up to date with directory changes shipped in an app update:
     * adds and turns on sources the user asked to have built in, and moves feeds whose address changed.
     * Runs once per [DIRECTORY_VERSION]; never removes a source or any archived article.
     */
    suspend fun applyDirectoryUpdates(fromVersion: Int) {
        if (fromVersion < 2) {
            // Moved feed addresses (old -> new).
            val moved = mapOf("https://feeds.abcnews.com/abcnews/topstories" to "https://abcnews.go.com/abcnews/topstories")
            for ((old, new) in moved) {
                val s = db.sources().byUrl(old) ?: continue
                if (db.sources().byUrl(new) == null) db.sources().update(s.copy(url = new, etag = null, lastModifiedHeader = null))
            }
            val wanted = setOf(
                "https://panow.com/feed/", "https://thestarphoenix.com/feed", "https://leaderpost.com/feed",
                "https://www.aptnnews.ca/feed/", "https://www.thecanadianpressnews.ca/", "https://www.cbc.ca/webfeed/rss/rss-topstories",
                "https://www.ctvnews.ca/arc/outboundfeeds/sitemap-news/latest/", "https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/",
                "https://globalnews.ca/feed/", "https://www.cnbc.com/id/100003114/device/rss/rss.html", "https://www.cbsnews.com/latest/rss/main",
                "https://abcnews.go.com/abcnews/topstories", "https://feeds.nbcnews.com/nbcnews/public/news", "https://www.ms.now/feed",
                "https://www.cgtn.com/subscribe/rss/section/world.xml", "https://www.aljazeera.com/xml/rss/all.xml",
                "https://www.france24.com/en/rss", "https://rss.dw.com/rdf/rss-en-all",
            )
            for (d in SourceDirectory.all.filter { it.feedUrl in wanted }) {
                val existing = findDuplicate(d.feedUrl)
                if (existing == null) db.sources().insert(d.toEntity(enabled = true))
                else if (!existing.enabled) db.sources().setEnabled(existing.id, true)
            }
        }
        if (fromVersion < 3) {
            // Postmedia dropped its RSS feeds (they now redirect to the home page); CTV publishes RSS again.
            val moved = mapOf(
                "https://leaderpost.com/feed" to ("https://leaderpost.com/news-sitemap.xml" to SourceKind.SITEMAP.name),
                "https://thestarphoenix.com/feed" to ("https://thestarphoenix.com/news-sitemap.xml" to SourceKind.SITEMAP.name),
                "https://www.ctvnews.ca/arc/outboundfeeds/sitemap-news/latest/" to ("https://www.ctvnews.ca/arc/outboundfeeds/rss/?outputType=xml" to SourceKind.RSS.name),
            )
            for ((old, target) in moved) {
                val s = findDuplicate(old) ?: continue
                if (findDuplicate(target.first) == null) db.sources().update(s.copy(url = target.first, kind = target.second, etag = null, lastModifiedHeader = null))
            }
            // Sources the user asked to have working: make sure each is present and on, and clear old
            // errors so the next collection retries them straight away.
            val wanted = setOf(
                "https://www.cgtn.com/subscribe/rss/section/world.xml", "https://www.ctvnews.ca/arc/outboundfeeds/rss/?outputType=xml",
                "https://www.propublica.org/feeds/propublica/main", "https://www.cbc.ca/webfeed/rss/rss-canada-saskatchewan",
                "https://leaderpost.com/news-sitemap.xml", "https://thestarphoenix.com/news-sitemap.xml", "https://panow.com/feed/",
                "https://www.sciencedaily.com/rss/all.xml", "https://feeds.arstechnica.com/arstechnica/index",
                "https://abcnews.go.com/abcnews/topstories", "https://www.cbsnews.com/latest/rss/main",
                "https://www.cnbc.com/id/100003114/device/rss/rss.html", "https://www.ms.now/feed",
                "https://feeds.nbcnews.com/nbcnews/public/news", "https://feeds.npr.org/1001/rss.xml",
            )
            for (d in SourceDirectory.all.filter { it.feedUrl in wanted }) {
                val existing = findDuplicate(d.feedUrl)
                if (existing == null) db.sources().insert(d.toEntity(enabled = true))
                else db.sources().update(existing.copy(
                    enabled = true, kind = d.kind, lastError = null, consecutiveFailures = 0,
                    status = SourceStatus.UNKNOWN, statusNote = null, etag = null, lastModifiedHeader = null,
                ))
            }
        }
    }

    private fun DirectorySource.toEntity(enabled: Boolean) = SourceEntity(
        name = name, url = feedUrl, kind = kind, homepage = homepage, category = category,
        placeCode = placeCode, language = language, isLocal = local, enabled = enabled, fromDirectory = true,
    )

    suspend fun findDuplicate(url: String): SourceEntity? {
        val c = UrlCanonicalizer.canonicalize(url)
        return db.sources().all().firstOrNull { UrlCanonicalizer.canonicalize(it.url) == c }
    }

    suspend fun sameHostCount(url: String): Int {
        val h = UrlCanonicalizer.host(url) ?: return 0
        return db.sources().all().count { UrlCanonicalizer.host(it.url) == h }
    }

    suspend fun addFromDirectory(d: DirectorySource): AddResult {
        findDuplicate(d.feedUrl)?.let { return AddResult.Duplicate(it) }
        val id = db.sources().insert(d.toEntity(enabled = true))
        return if (id > 0) AddResult.Added(id) else AddResult.Invalid("Couldn't add source")
    }

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) { probe.probe(url) }

    suspend fun addProbed(r: ProbeResult, name: String, category: String, placeCode: String?, isLocal: Boolean): AddResult {
        val url = r.feedUrl ?: return AddResult.Invalid("No usable address")
        val kind = r.kind
        if (!r.ok || kind == null) return AddResult.Invalid(r.message)
        findDuplicate(url)?.let { return AddResult.Duplicate(it) }
        val id = db.sources().insert(
            SourceEntity(
                name = name.ifBlank { r.title ?: UrlCanonicalizer.host(url) ?: url }, url = url, kind = kind.name,
                homepage = r.siteUrl, category = category.ifBlank { "Uncategorized" }, placeCode = placeCode, isLocal = isLocal,
                enabled = true, status = if (kind == SourceKind.WEBSITE) SourceStatus.PARTIAL else SourceStatus.UNKNOWN, statusNote = r.message,
            ),
        )
        return if (id > 0) AddResult.Added(id) else AddResult.Invalid("Couldn't add source")
    }

    /** Imports OPML entries, skipping duplicates. Returns (added, skipped). */
    suspend fun importOpml(entries: List<OpmlEntry>): Pair<Int, Int> {
        var added = 0; var skipped = 0
        for (e in entries) {
            if (findDuplicate(e.xmlUrl) != null) { skipped++; continue }
            val id = db.sources().insert(SourceEntity(name = e.title, url = e.xmlUrl, kind = SourceKind.RSS.name, homepage = e.htmlUrl, category = e.category ?: "Imported"))
            if (id > 0) added++ else skipped++
        }
        return added to skipped
    }

    suspend fun updateSource(s: SourceEntity) = db.sources().update(s)
    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = db.sources().setEnabled(id, enabled)
    suspend fun setSourceNotify(id: Long, notify: Boolean) = db.sources().setNotify(id, notify)

    /** Deletes a source together with its archived articles (the UI confirms first). */
    suspend fun deleteSourceAndArticles(id: Long): Int {
        val n = maintenance.deleteSourceArticles(id)
        db.sources().delete(id)
        return n
    }

    // ---- Articles ---------------------------------------------------------------------------

    suspend fun markRead(id: Long, read: Boolean) = db.articles().setRead(id, read, now)
    suspend fun setBookmarked(id: Long, v: Boolean) = db.articles().setBookmarked(id, v, now)
    suspend fun setSaved(id: Long, v: Boolean) = db.articles().setSaved(id, v, now)
    suspend fun clearHistory() = db.articles().clearHistory()

    suspend fun addGeoTag(articleId: Long, code: String) {
        val level = when (code) {
            Gazetteer.SPACE_CODE -> GeoLevel.SPACE
            else -> Gazetteer.get(code)?.level ?: return
        }
        db.tags().insertGeo(listOf(GeoTagEntity(articleId, code, level.name, 1.0, inferred = false, manual = true)))
        // Make the place reachable from its parents too.
        db.tags().insertGeo(Gazetteer.ancestors(code).map { GeoTagEntity(articleId, it.code, it.level.name, 1.0, inferred = false, manual = true) })
        db.articles().get(articleId)?.let { db.articles().update(it.copy(geoManual = true)) }
    }

    suspend fun removeGeoTag(articleId: Long, code: String) {
        db.tags().deleteGeo(articleId, code)
        db.articles().get(articleId)?.let { db.articles().update(it.copy(geoManual = true)) }
    }

    suspend fun addTopic(articleId: Long, topic: String) {
        db.tags().insertTopics(listOf(TopicTagEntity(articleId, topic, 1.0, manual = true)))
        db.articles().get(articleId)?.let { db.articles().update(it.copy(topicsManual = true)) }
    }

    suspend fun removeTopic(articleId: Long, topic: String) {
        db.tags().deleteTopic(articleId, topic)
        db.articles().get(articleId)?.let { db.articles().update(it.copy(topicsManual = true)) }
    }

    suspend fun detachFromStory(articleId: Long) = stories.detach(articleId)

    // ---- Library ----------------------------------------------------------------------------

    suspend fun createCollection(name: String): Long = db.library().insertCollection(CollectionEntity(name = name.trim()))
    suspend fun renameCollection(id: Long, name: String) = db.library().renameCollection(id, name.trim())
    suspend fun deleteCollection(id: Long) { db.library().clearCollection(id); db.library().deleteCollection(id) }
    suspend fun addToCollection(collectionId: Long, articleId: Long) = db.library().addItem(CollectionItemEntity(collectionId, articleId))
    suspend fun removeFromCollection(collectionId: Long, articleId: Long) = db.library().removeItem(collectionId, articleId)

    suspend fun follow(kind: String, value: String, label: String, notify: Boolean = false) =
        db.library().follow(FollowEntity(kind = kind, value = value, label = label, notify = notify))
    suspend fun unfollow(id: Long) = db.library().unfollow(id)
    suspend fun unfollow(kind: String, value: String) = db.library().unfollow(kind, value)
    suspend fun setFollowNotify(id: Long, notify: Boolean) = db.library().setFollowNotify(id, notify)

    // ---- Telegram ---------------------------------------------------------------------------

    suspend fun addTelegramChannel(input: String, category: String): AddResult {
        val u = TelegramPreview.normalizeUsername(input)
            ?: return AddResult.Invalid("Enter a public channel username like @channelname or a t.me link. Private invite links can't be read.")
        db.telegram().get(u)?.let { existing ->
            if (!existing.followed) db.telegram().updateChannel(existing.copy(followed = true))
            return AddResult.Added(0)
        }
        val dir = TelegramDirectory.all.firstOrNull { it.username.equals(u, true) }
        db.telegram().insertChannel(TelegramChannelEntity(username = u, title = dir?.title, category = category.ifBlank { dir?.category ?: "General" }))
        return AddResult.Added(0)
    }

    suspend fun setTelegramFollowed(username: String, followed: Boolean) {
        db.telegram().get(username)?.let { db.telegram().updateChannel(it.copy(followed = followed)) }
    }

    suspend fun setTelegramCategory(username: String, category: String) {
        db.telegram().get(username)?.let { db.telegram().updateChannel(it.copy(category = category)) }
    }

    suspend fun removeTelegramChannel(username: String) {
        db.telegram().deletePosts(username)
        db.telegram().deleteChannel(username)
    }
}
