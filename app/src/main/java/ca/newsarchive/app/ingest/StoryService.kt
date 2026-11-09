package ca.newsarchive.app.ingest

import androidx.room.withTransaction
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.StoryEntity
import ca.newsarchive.core.cluster.StoryClusterer
import ca.newsarchive.core.cluster.StorySignature
import ca.newsarchive.core.text.UrlCanonicalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Groups articles from different publishers that report the same story. Keeps an in-memory window
 * of recent signatures during a collection run so each new article is compared cheaply.
 */
class StoryService(private val db: AppDatabase) {
    private data class Member(val articleId: Long, var storyId: Long?, val sig: StorySignature)

    private val mutex = Mutex()
    private var window: MutableList<Member>? = null
    private var windowLoadedAt = 0L

    private suspend fun ensureWindow(now: Long) {
        if (window != null && now - windowLoadedAt < 30 * 60_000) return
        val since = now - StoryClusterer.TIME_WINDOW_MS
        val list = mutableListOf<Member>()
        val active = db.stories().activeSince(since).map { it.id }
        if (active.isNotEmpty()) {
            db.articles().inStories(active).groupBy { it.storyId }.forEach { (sid, arts) ->
                arts.take(6).forEach { a -> a.signature?.let(StorySignature::deserialize)?.let { list += Member(a.id, sid, it) } }
            }
        }
        db.articles().unclusteredSince(since, -1).forEach { a ->
            a.signature?.let(StorySignature::deserialize)?.let { list += Member(a.id, null, it) }
        }
        window = list
        windowLoadedAt = now
    }

    fun invalidate() { window = null }

    suspend fun assign(articleId: Long, sig: StorySignature, url: String) = mutex.withLock {
        ensureWindow(System.currentTimeMillis())
        val w = window ?: return@withLock
        // Candidates: existing stories (positive ids) and unclustered single articles (negative ids).
        val candidates = HashMap<Long, MutableList<StorySignature>>()
        for (m in w) {
            if (m.articleId == articleId) continue
            val key = m.storyId ?: -m.articleId
            val l = candidates.getOrPut(key) { mutableListOf() }
            if (l.size < 6) l += m.sig
        }
        val match = StoryClusterer.assign(sig, candidates)
        val storyId: Long? = when {
            match == null -> null
            match.clusterId > 0 -> match.clusterId
            else -> {
                val otherId = -match.clusterId
                val other = db.articles().get(otherId) ?: return@withLock
                val newId = db.stories().insert(
                    StoryEntity(title = other.title, firstPublishedAt = other.sortTime, lastPublishedAt = other.sortTime, articleCount = 1, publisherCount = 1),
                )
                db.articles().setStory(otherId, newId)
                w.filter { it.articleId == otherId }.forEach { it.storyId = newId }
                newId
            }
        }
        w += Member(articleId, storyId, sig)
        if (storyId != null) {
            db.articles().setStory(articleId, storyId)
            refreshStats(storyId)
        }
    }

    suspend fun refreshStats(storyId: Long) {
        db.withTransaction {
            val members = db.articles().inStory(storyId)
            val story = db.stories().get(storyId) ?: return@withTransaction
            if (members.isEmpty()) return@withTransaction
            val publishers = members.map { publisherKey(it.canonicalUrl) }.toSet().size
            db.stories().update(
                story.copy(
                    title = members.first().title,
                    firstPublishedAt = members.minOf { it.sortTime },
                    lastPublishedAt = members.maxOf { it.sortTime },
                    articleCount = members.size,
                    publisherCount = publishers,
                ),
            )
        }
    }

    /** Removes an article from its story (user correction). */
    suspend fun detach(articleId: Long) {
        val a = db.articles().get(articleId) ?: return
        val sid = a.storyId ?: return
        db.articles().setStory(articleId, null)
        refreshStats(sid)
        db.stories().deleteEmpty()
        invalidate()
    }

    companion object {
        private val twoPartTlds = setOf("co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.nz", "co.za", "com.br", "com.ar", "co.jp", "co.kr", "com.sg", "com.pk", "co.in")

        /** Publisher identity = registrable domain, so CBC's several feeds count as one publisher. */
        fun publisherKey(url: String): String {
            val host = UrlCanonicalizer.host(url) ?: return url
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val last2 = parts.takeLast(2).joinToString(".")
            return if (last2 in twoPartTlds) parts.takeLast(3).joinToString(".") else last2
        }
    }
}
