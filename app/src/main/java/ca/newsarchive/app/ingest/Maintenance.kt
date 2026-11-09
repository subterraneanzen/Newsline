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

    fun clearImageCache() { java.io.File(context.cacheDir, "images").deleteRecursively() }

    /** Flushes the write-ahead log so the main database file is complete (before backup). */
    suspend fun checkpoint() {
        runCatching { db.maintenance().checkpoint(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")) }
    }
}
