package ca.newsarchive.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import ca.newsarchive.core.model.Block
import ca.newsarchive.core.model.BlockType

object SourceStatus {
    const val UNKNOWN = "UNKNOWN"          // never fetched
    const val OK = "OK"
    const val PARTIAL = "PARTIAL"          // works, but with limitations (e.g. page scanning, feed-only text)
    const val ERROR = "ERROR"              // last attempt failed
    const val UNAVAILABLE = "UNAVAILABLE"  // repeatedly failing / gone
}

object Availability {
    const val AVAILABLE = "AVAILABLE"
    const val GONE = "GONE"                // publisher returns 404/410
    const val UNREACHABLE = "UNREACHABLE"  // recent revisits failed for other reasons
}

@Entity(tableName = "sources", indices = [Index(value = ["url"], unique = true), Index("category")])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val kind: String,
    val homepage: String? = null,
    val category: String = "Uncategorized",
    val placeCode: String? = null,
    val language: String? = null,
    val isLocal: Boolean = false,
    val enabled: Boolean = true,
    val notify: Boolean = false,
    val intervalMinutes: Int = 60,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastNewArticleAt: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val etag: String? = null,
    val lastModifiedHeader: String? = null,
    val status: String = SourceStatus.UNKNOWN,
    val statusNote: String? = null,
    val fromDirectory: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["canonicalUrl"], unique = true), Index("sourceId"), Index("storyId"),
        Index("sortTime"), Index("nextCheckAt"), Index("isBookmarked"), Index("isSaved"), Index("readAt"),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val url: String,
    val canonicalUrl: String,
    val guid: String? = null,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    /** Original publication date as reported by the publisher, if any. */
    val publishedAt: Long? = null,
    /** Publisher-reported last modification date, if any. */
    val publisherModifiedAt: Long? = null,
    /** When this app first discovered the article. */
    val discoveredAt: Long,
    /** publishedAt ?: discoveredAt – used for ordering. */
    val sortTime: Long,
    val extractionStatus: String,
    val extractionNote: String? = null,
    val articleType: String = "UNKNOWN",
    val imageUrl: String? = null,
    val language: String? = null,
    val section: String? = null,
    val keywords: String? = null,
    val wordCount: Int = 0,
    val versionCount: Int = 1,
    val lastCheckedAt: Long? = null,
    val nextCheckAt: Long? = null,
    val checkCount: Int = 0,
    val availability: String = Availability.AVAILABLE,
    val unavailableSince: Long? = null,
    val isRead: Boolean = false,
    val readAt: Long? = null,
    val isBookmarked: Boolean = false,
    val bookmarkedAt: Long? = null,
    val isSaved: Boolean = false,
    val savedAt: Long? = null,
    val storyId: Long? = null,
    val signature: String? = null,
    val geoManual: Boolean = false,
    val topicsManual: Boolean = false,
)

@Entity(
    tableName = "article_versions",
    indices = [Index(value = ["articleId", "versionNo"], unique = true)],
)
data class ArticleVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long,
    val versionNo: Int,
    /** When the app captured this snapshot (NOT when the publisher changed it). */
    val capturedAt: Long,
    val title: String,
    val author: String? = null,
    val publisherModifiedAt: Long? = null,
    /** Blocks encoded one per line as "T|text" (T = P/H/Q/L). */
    val body: String,
    val contentHash: String,
    val changeSummary: String? = null,
    val images: String? = null,
    val extractionStatus: String,
) {
    fun blocks(): List<Block> = decodeBlocks(body)
    fun paragraphs(): List<String> = blocks().map { it.text }

    companion object {
        fun encodeBlocks(blocks: List<Block>): String = blocks.joinToString("\n") {
            val t = when (it.type) { BlockType.HEADING -> 'H'; BlockType.QUOTE -> 'Q'; BlockType.LIST_ITEM -> 'L'; else -> 'P' }
            "$t|" + it.text.replace('\n', ' ')
        }

        fun decodeBlocks(s: String): List<Block> = s.lines().filter { it.length > 2 && it[1] == '|' }.map {
            val type = when (it[0]) { 'H' -> BlockType.HEADING; 'Q' -> BlockType.QUOTE; 'L' -> BlockType.LIST_ITEM; else -> BlockType.PARAGRAPH }
            Block(type, it.substring(2))
        }
    }
}

@Fts4
@Entity(tableName = "article_fts")
data class ArticleFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val body: String,
    val publisher: String,
    val author: String,
    val places: String,
    val topics: String,
)

@Entity(tableName = "geo_tags", primaryKeys = ["articleId", "code"], indices = [Index("code")])
data class GeoTagEntity(
    val articleId: Long,
    val code: String,
    val level: String,
    val confidence: Double,
    val inferred: Boolean = false,
    val manual: Boolean = false,
)

@Entity(tableName = "topic_tags", primaryKeys = ["articleId", "topic"], indices = [Index("topic")])
data class TopicTagEntity(
    val articleId: Long,
    val topic: String,
    val confidence: Double,
    val manual: Boolean = false,
)

@Entity(tableName = "stories", indices = [Index("lastPublishedAt"), Index("publisherCount")])
data class StoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val firstPublishedAt: Long,
    val lastPublishedAt: Long,
    val articleCount: Int,
    val publisherCount: Int,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "articleId"], indices = [Index("articleId")])
data class CollectionItemEntity(val collectionId: Long, val articleId: Long, val addedAt: Long = System.currentTimeMillis())

object FollowKind {
    const val TOPIC = "TOPIC"      // one of the built-in topic categories
    const val KEYWORD = "KEYWORD"  // free-text query matched with full-text search
    const val PLACE = "PLACE"      // geographic code (for notifications)
}

@Entity(tableName = "follows", indices = [Index(value = ["kind", "value"], unique = true)])
data class FollowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val value: String,
    val label: String,
    val notify: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "telegram_channels")
data class TelegramChannelEntity(
    @PrimaryKey val username: String,
    val title: String? = null,
    val description: String? = null,
    val category: String = "General",
    val followed: Boolean = true,
    val status: String = SourceStatus.UNKNOWN,
    val statusNote: String? = null,
    val lastFetchAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val newestPostId: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "telegram_posts", primaryKeys = ["channel", "postId"], indices = [Index("postedAt")])
data class TelegramPostEntity(
    val channel: String,
    val postId: Long,
    val text: String,
    val postedAt: Long?,
    val url: String,
    val views: String?,
    val hasMedia: Boolean,
    val imageUrl: String?,
    val links: String,
    val fetchedAt: Long,
)

@Entity(tableName = "notification_log")
data class NotificationLogEntity(@PrimaryKey val id: String, val notifiedAt: Long)

@Entity(tableName = "sync_runs")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val trigger: String,
    val sourcesChecked: Int = 0,
    val sourcesFailed: Int = 0,
    val newArticles: Int = 0,
    val articlesRevisited: Int = 0,
    val newVersions: Int = 0,
    val message: String? = null,
)
