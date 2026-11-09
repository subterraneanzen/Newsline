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

    private fun DirectorySource.toEntity(enabled: Boolean) = SourceEntity(
        name = name, url = feedUrl, kind = SourceKind.RSS.name, homepage = homepage, category = category,
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
        if (!r.ok || r.kind == null) return AddResult.Invalid(r.message)
        findDuplicate(url)?.let { return AddResult.Duplicate(it) }
        val id = db.sources().insert(
            SourceEntity(
                name = name.ifBlank { r.title ?: UrlCanonicalizer.host(url) ?: url }, url = url, kind = r.kind.name,
                homepage = r.siteUrl, category = category.ifBlank { "Uncategorized" }, placeCode = placeCode, isLocal = isLocal,
                enabled = true, status = if (r.kind == SourceKind.WEBSITE) SourceStatus.PARTIAL else SourceStatus.UNKNOWN, statusNote = r.message,
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
