package ca.newsarchive.app.ingest

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.SettingsStore

data class StorageStats(
    val databaseBytes: Long,
    val articleCount: Int,
    val versionCount: Int,
    val textBytes: Long,
    val telegramPosts: Int,
    val imageCacheBytes: Long,
)

/** Storage reporting, duplicate cleanup and optional retention. Nothing is deleted silently. */
class Maintenance(private val context: Context, private val db: AppDatabase, private val settings: SettingsStore, private val stories: StoryService) {

    suspend fun stats(): StorageStats {
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        val dbBytes = listOf(dbFile, java.io.File(dbFile.path + "-wal"), java.io.File(dbFile.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
        val cache = java.io.File(context.cacheDir, "images")
        return StorageStats(
            databaseBytes = dbBytes,
            articleCount = db.articles().count(),
            versionCount = db.versions().count(),
            textBytes = db.versions().totalBodyChars(),
            telegramPosts = db.telegram().countPosts(),
            imageCacheBytes = cache.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }

    /** Number of articles the configured retention would remove (for the confirmation dialog). */
    suspend fun retentionPreview(days: Int): Int =
        if (days <= 0) 0 else db.articles().deletableBefore(System.currentTimeMillis() - days * 86_400_000L).size

    /**
     * Applies the retention setting. Only runs when the user explicitly enabled it (default: keep
     * forever). Bookmarked, saved and collected articles are always kept.
     */
    suspend fun applyRetention(): Int {
        val days = settings.current.retentionDays
        if (days <= 0) return 0
        return deleteArticles(db.articles().deletableBefore(System.currentTimeMillis() - days * 86_400_000L))
    }

    suspend fun deleteArticles(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        ids.chunked(500).forEach { chunk ->
            db.withTransaction {
                db.versions().deleteFor(chunk)
                db.tags().deleteGeoFor(chunk)
                db.tags().deleteTopicsFor(chunk)
                db.fts().deleteFor(chunk)
                db.library().removeArticles(chunk)
                db.articles().deleteByIds(chunk)
            }
        }
        db.stories().deleteEmpty()
        stories.invalidate()
        return ids.size
    }

    suspend fun duplicatePreview(): List<Long> = db.articles().probableDuplicateIds()

    suspend fun removeDuplicates(): Int = deleteArticles(duplicatePreview())

    suspend fun deleteSourceArticles(sourceId: Long): Int = deleteArticles(db.articles().idsForSource(sourceId))

    /**
     * Re-checks recent articles against the current Space and Defense & Military rules and removes those
     * tags where they no longer apply (tags the user added by hand are kept). Nothing else changes.
     */
    suspend fun recheckSpaceDefenseTags(days: Int = 60) {
        val subjects = setOf("Space", "Defense & Military")
        for (id in db.articles().idsSince(System.currentTimeMillis() - days * 86_400_000L)) {
            val topics = db.tags().topicsFor(id).filter { it.topic in subjects && !it.manual }
            val space = db.tags().geoFor(id).firstOrNull { it.code == ca.newsarchive.core.geo.Gazetteer.SPACE_CODE && !it.manual }
            if (topics.isEmpty() && space == null) continue
            val v = db.versions().latest(id) ?: continue
            val paragraphs = ca.newsarchive.app.data.ArticleVersionEntity.decodeBlocks(v.body).map { it.text }
            val now = ca.newsarchive.core.topic.TopicClassifier.classify(v.title, paragraphs).associateBy { it.topic }
            for (t in topics) {
                val fresh = now[t.topic]
                if (fresh == null) db.tags().deleteTopic(id, t.topic)
                else db.tags().insertTopics(listOf(t.copy(confidence = fresh.confidence)))
            }
            if (space != null && ca.newsarchive.core.geo.GeoClassifier.spaceScore(v.title, paragraphs) < 3.0) db.tags().deleteGeo(id, space.code)
        }
    }

    fun clearImageCache() { java.io.File(context.cacheDir, "images").deleteRecursively() }

    /** Flushes the write-ahead log so the main database file is complete (before backup). */
    suspend fun checkpoint() {
        runCatching { db.maintenance().checkpoint(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")) }
    }
}
