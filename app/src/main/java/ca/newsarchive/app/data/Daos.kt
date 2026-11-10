package ca.newsarchive.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Row shown in article lists. */
data class ArticleListItem(
    val id: Long,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    val url: String,
    val publishedAt: Long?,
    val discoveredAt: Long,
    val sortTime: Long,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val isSaved: Boolean,
    val storyId: Long?,
    val articleType: String,
    val versionCount: Int,
    val availability: String,
    val extractionStatus: String,
    val sourceId: Long,
    val sourceName: String,
    val publisherCount: Int,
)

data class SourceWithStats(
    val id: Long,
    val name: String,
    val url: String,
    val kind: String,
    val category: String,
    val enabled: Boolean,
    val status: String,
    val statusNote: String?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val articleCount: Int,
)

data class StoryListItem(
    val id: Long,
    val title: String,
    val firstPublishedAt: Long,
    val lastPublishedAt: Long,
    val articleCount: Int,
    val publisherCount: Int,
    val publishers: String?,
)

data class CountByKey(val name: String, val count: Int)
data class CollectionWithCount(val id: Long, val name: String, val createdAt: Long, val count: Int)

const val ARTICLE_LIST_SELECT = """
    SELECT a.id, a.title, a.summary, a.imageUrl, a.url, a.publishedAt, a.discoveredAt, a.sortTime, a.isRead,
           a.isBookmarked, a.isSaved, a.storyId, a.articleType, a.versionCount, a.availability, a.extractionStatus,
           s.id AS sourceId, s.name AS sourceName, COALESCE(st.publisherCount, 1) AS publisherCount
    FROM articles a JOIN sources s ON s.id = a.sourceId LEFT JOIN stories st ON st.id = a.storyId
"""

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY category, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("""SELECT s.id, s.name, s.url, s.kind, s.category, s.enabled, s.status, s.statusNote, s.lastSuccessAt, s.lastError,
              (SELECT COUNT(*) FROM articles a WHERE a.sourceId = s.id) AS articleCount
              FROM sources s ORDER BY s.category, s.name""")
    fun observeWithStats(): Flow<List<SourceWithStats>>

    @Query("SELECT * FROM sources WHERE id = :id") fun observe(id: Long): Flow<SourceEntity?>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun get(id: Long): SourceEntity?
    @Query("SELECT * FROM sources WHERE url = :url LIMIT 1") suspend fun byUrl(url: String): SourceEntity?
    @Query("SELECT * FROM sources") suspend fun all(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE enabled = 1") suspend fun enabled(): List<SourceEntity>
    @Query("SELECT COUNT(*) FROM sources") suspend fun count(): Int
    @Query("SELECT DISTINCT category FROM sources ORDER BY category") fun observeCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(s: SourceEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(s: List<SourceEntity>)
    @Update suspend fun update(s: SourceEntity)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: Long, enabled: Boolean)
    @Query("UPDATE sources SET notify = :notify WHERE id = :id") suspend fun setNotify(id: Long, notify: Boolean)
}

@Dao
interface ArticleDao {
    @RawQuery(observedEntities = [ArticleEntity::class, SourceEntity::class, StoryEntity::class, GeoTagEntity::class, TopicTagEntity::class, ArticleFts::class, CollectionItemEntity::class])
    fun observeList(q: SupportSQLiteQuery): Flow<List<ArticleListItem>>

    @RawQuery suspend fun queryList(q: SupportSQLiteQuery): List<ArticleListItem>

    @Query("SELECT * FROM articles WHERE id = :id") fun observe(id: Long): Flow<ArticleEntity?>
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun get(id: Long): ArticleEntity?
    @Query("SELECT * FROM articles WHERE canonicalUrl = :c LIMIT 1") suspend fun byCanonical(c: String): ArticleEntity?
    @Query("SELECT url FROM articles WHERE title = :title AND sortTime > :since") suspend fun urlsWithTitle(title: String, since: Long): List<String>
    @Query("SELECT id FROM articles WHERE sourceId = :sourceId AND guid = :guid LIMIT 1") suspend fun idByGuid(sourceId: Long, guid: String): Long?
    @Query("SELECT * FROM articles WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<ArticleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: ArticleEntity): Long
    @Update suspend fun update(a: ArticleEntity)

    @Query("SELECT * FROM articles WHERE availability != 'GONE' AND nextCheckAt IS NOT NULL AND nextCheckAt <= :now ORDER BY nextCheckAt LIMIT :limit")
    suspend fun dueForRevisit(now: Long, limit: Int): List<ArticleEntity>

    @Query("UPDATE articles SET isRead = :read, readAt = CASE WHEN :read THEN :now ELSE readAt END WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean, now: Long)
    @Query("UPDATE articles SET isBookmarked = :v, bookmarkedAt = :now WHERE id = :id") suspend fun setBookmarked(id: Long, v: Boolean, now: Long)
    @Query("UPDATE articles SET isSaved = :v, savedAt = :now WHERE id = :id") suspend fun setSaved(id: Long, v: Boolean, now: Long)
    @Query("UPDATE articles SET storyId = :storyId WHERE id = :id") suspend fun setStory(id: Long, storyId: Long?)
    @Query("UPDATE articles SET readAt = NULL") suspend fun clearHistory()

    @Query("SELECT * FROM articles WHERE storyId IS NULL AND signature IS NOT NULL AND sortTime >= :since AND id != :exclude ORDER BY sortTime DESC LIMIT 400")
    suspend fun unclusteredSince(since: Long, exclude: Long): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE storyId = :storyId ORDER BY sortTime") suspend fun inStory(storyId: Long): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE storyId IN (:storyIds) ORDER BY sortTime DESC") suspend fun inStories(storyIds: List<Long>): List<ArticleEntity>

    @Query("SELECT COUNT(*) FROM articles") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM articles") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM articles WHERE sourceId = :sourceId") suspend fun countForSource(sourceId: Long): Int
    @Query("SELECT id FROM articles WHERE sourceId = :sourceId") suspend fun idsForSource(sourceId: Long): List<Long>
    @Query("SELECT * FROM articles ORDER BY id LIMIT :limit OFFSET :offset") suspend fun page(limit: Int, offset: Int): List<ArticleEntity>

    /** Articles older than [before] that the user has not kept (bookmark / saved / collection). */
    @Query("""SELECT id FROM articles WHERE sortTime < :before AND isBookmarked = 0 AND isSaved = 0
              AND id NOT IN (SELECT articleId FROM collection_items)""")
    suspend fun deletableBefore(before: Long): List<Long>

    @Query("DELETE FROM articles WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT canonicalUrl AS name, COUNT(*) AS count FROM articles GROUP BY canonicalUrl HAVING COUNT(*) > 1")
    suspend fun duplicateCanonicals(): List<CountByKey>

    /** Articles with the same normalized title from the same source – likely duplicates under different URLs. */
    @Query("""SELECT a.id FROM articles a JOIN articles b ON a.sourceId = b.sourceId AND LOWER(a.title) = LOWER(b.title)
              AND a.id > b.id AND ABS(a.sortTime - b.sortTime) < 172800000
              WHERE a.isBookmarked = 0 AND a.isSaved = 0 AND a.versionCount = 1 AND a.id NOT IN (SELECT articleId FROM collection_items)""")
    suspend fun probableDuplicateIds(): List<Long>
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM article_versions WHERE articleId = :articleId ORDER BY versionNo") fun observeFor(articleId: Long): Flow<List<ArticleVersionEntity>>
    @Query("SELECT * FROM article_versions WHERE articleId = :articleId ORDER BY versionNo") suspend fun allFor(articleId: Long): List<ArticleVersionEntity>
    @Query("SELECT * FROM article_versions WHERE articleId = :articleId ORDER BY versionNo DESC LIMIT 1") suspend fun latest(articleId: Long): ArticleVersionEntity?
    @Query("SELECT * FROM article_versions WHERE articleId = :articleId ORDER BY versionNo DESC LIMIT 1") fun observeLatest(articleId: Long): Flow<ArticleVersionEntity?>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(v: ArticleVersionEntity): Long
    @Query("SELECT COUNT(*) FROM article_versions") suspend fun count(): Int
    @Query("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM article_versions") suspend fun totalBodyChars(): Long
    @Query("DELETE FROM article_versions WHERE articleId IN (:ids)") suspend fun deleteFor(ids: List<Long>)
    @Query("SELECT articleId FROM article_versions WHERE capturedAt >= :since AND versionNo > 1 ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecentlyChanged(since: Long, limit: Int): Flow<List<Long>>
}

@Dao
interface FtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(f: ArticleFts)
    @Query("DELETE FROM article_fts WHERE rowid IN (:ids)") suspend fun deleteFor(ids: List<Long>)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM geo_tags WHERE articleId = :articleId ORDER BY confidence DESC") fun observeGeo(articleId: Long): Flow<List<GeoTagEntity>>
    @Query("SELECT * FROM geo_tags WHERE articleId = :articleId") suspend fun geoFor(articleId: Long): List<GeoTagEntity>
    @Query("SELECT * FROM geo_tags WHERE articleId IN (:ids)") suspend fun geoForMany(ids: List<Long>): List<GeoTagEntity>
    @Query("SELECT * FROM topic_tags WHERE articleId = :articleId ORDER BY confidence DESC") fun observeTopics(articleId: Long): Flow<List<TopicTagEntity>>
    @Query("SELECT * FROM topic_tags WHERE articleId = :articleId") suspend fun topicsFor(articleId: Long): List<TopicTagEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGeo(t: List<GeoTagEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTopics(t: List<TopicTagEntity>)
    @Query("DELETE FROM geo_tags WHERE articleId = :articleId AND manual = 0") suspend fun clearAutoGeo(articleId: Long)
    @Query("DELETE FROM topic_tags WHERE articleId = :articleId AND manual = 0") suspend fun clearAutoTopics(articleId: Long)
    @Query("DELETE FROM geo_tags WHERE articleId = :articleId AND code = :code") suspend fun deleteGeo(articleId: Long, code: String)
    @Query("DELETE FROM topic_tags WHERE articleId = :articleId AND topic = :topic") suspend fun deleteTopic(articleId: Long, topic: String)
    @Query("DELETE FROM geo_tags WHERE articleId IN (:ids)") suspend fun deleteGeoFor(ids: List<Long>)
    @Query("DELETE FROM topic_tags WHERE articleId IN (:ids)") suspend fun deleteTopicsFor(ids: List<Long>)

    @Query("SELECT code AS name, COUNT(*) AS count FROM geo_tags GROUP BY code") fun observeGeoCounts(): Flow<List<CountByKey>>
    @Query("SELECT topic AS name, COUNT(*) AS count FROM topic_tags GROUP BY topic ORDER BY count DESC") fun observeTopicCounts(): Flow<List<CountByKey>>
}

@Dao
interface StoryDao {
    @Insert suspend fun insert(s: StoryEntity): Long
    @Update suspend fun update(s: StoryEntity)
    @Query("SELECT * FROM stories WHERE id = :id") suspend fun get(id: Long): StoryEntity?
    @Query("SELECT * FROM stories WHERE id = :id") fun observe(id: Long): Flow<StoryEntity?>
    @Query("SELECT * FROM stories WHERE lastPublishedAt >= :since ORDER BY lastPublishedAt DESC LIMIT 300") suspend fun activeSince(since: Long): List<StoryEntity>
    @Query("DELETE FROM stories WHERE id NOT IN (SELECT DISTINCT storyId FROM articles WHERE storyId IS NOT NULL)") suspend fun deleteEmpty()

    @Query("""SELECT st.id, st.title, st.firstPublishedAt, st.lastPublishedAt, st.articleCount, st.publisherCount,
              (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT DISTINCT s.name FROM articles a JOIN sources s ON s.id = a.sourceId WHERE a.storyId = st.id LIMIT 6)) AS publishers
              FROM stories st WHERE st.publisherCount >= :minPublishers AND st.lastPublishedAt >= :since
              ORDER BY st.lastPublishedAt DESC LIMIT :limit""")
    fun observeMultiSource(minPublishers: Int, since: Long, limit: Int): Flow<List<StoryListItem>>

    @Query("""SELECT st.id, st.title, st.firstPublishedAt, st.lastPublishedAt, st.articleCount, st.publisherCount,
              (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT DISTINCT s.name FROM articles a JOIN sources s ON s.id = a.sourceId WHERE a.storyId = st.id LIMIT 6)) AS publishers
              FROM stories st WHERE st.articleCount >= 2 AND st.lastPublishedAt >= :since
              ORDER BY st.lastPublishedAt DESC LIMIT :limit""")
    fun observeDeveloping(since: Long, limit: Int): Flow<List<StoryListItem>>

    @Query("DELETE FROM stories") suspend fun deleteAll()
}

@Dao
interface LibraryDao {
    @Query("""SELECT c.id, c.name, c.createdAt, (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id) AS count
              FROM collections c ORDER BY c.name""")
    fun observeCollections(): Flow<List<CollectionWithCount>>
    @Query("""SELECT c.id, c.name, c.createdAt, (SELECT COUNT(*) FROM collection_items i WHERE i.collectionId = c.id) AS count
              FROM collections c ORDER BY c.name""")
    suspend fun collections(): List<CollectionWithCount>
    @Query("SELECT * FROM collections WHERE id = :id") fun observeCollection(id: Long): Flow<CollectionEntity?>
    @Insert suspend fun insertCollection(c: CollectionEntity): Long
    @Query("UPDATE collections SET name = :name WHERE id = :id") suspend fun renameCollection(id: Long, name: String)
    @Query("DELETE FROM collections WHERE id = :id") suspend fun deleteCollection(id: Long)
    @Query("DELETE FROM collection_items WHERE collectionId = :id") suspend fun clearCollection(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addItem(i: CollectionItemEntity)
    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND articleId = :articleId") suspend fun removeItem(collectionId: Long, articleId: Long)
    @Query("DELETE FROM collection_items WHERE articleId IN (:ids)") suspend fun removeArticles(ids: List<Long>)
    @Query("SELECT collectionId FROM collection_items WHERE articleId = :articleId") fun observeCollectionsFor(articleId: Long): Flow<List<Long>>

    @Query("SELECT * FROM follows ORDER BY kind, label") fun observeFollows(): Flow<List<FollowEntity>>
    @Query("SELECT * FROM follows") suspend fun follows(): List<FollowEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun follow(f: FollowEntity): Long
    @Query("DELETE FROM follows WHERE id = :id") suspend fun unfollow(id: Long)
    @Query("DELETE FROM follows WHERE kind = :kind AND value = :value") suspend fun unfollow(kind: String, value: String)
    @Query("UPDATE follows SET notify = :notify WHERE id = :id") suspend fun setFollowNotify(id: Long, notify: Boolean)
}

@Dao
interface TelegramDao {
    @Query("SELECT * FROM telegram_channels ORDER BY category, COALESCE(title, username)") fun observeChannels(): Flow<List<TelegramChannelEntity>>
    @Query("SELECT * FROM telegram_channels WHERE followed = 1") suspend fun followed(): List<TelegramChannelEntity>
    @Query("SELECT * FROM telegram_channels WHERE username = :u") suspend fun get(u: String): TelegramChannelEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChannel(c: TelegramChannelEntity): Long
    @Update suspend fun updateChannel(c: TelegramChannelEntity)
    @Query("DELETE FROM telegram_channels WHERE username = :u") suspend fun deleteChannel(u: String)
    @Query("DELETE FROM telegram_posts WHERE channel = :u") suspend fun deletePosts(u: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPosts(p: List<TelegramPostEntity>): List<Long>

    @Query("""SELECT p.* FROM telegram_posts p JOIN telegram_channels c ON c.username = p.channel
              WHERE c.followed = 1 AND (:channel IS NULL OR p.channel = :channel) AND (:category IS NULL OR c.category = :category)
              AND (:q IS NULL OR p.text LIKE '%' || :q || '%')
              ORDER BY COALESCE(p.postedAt, p.fetchedAt) DESC LIMIT :limit""")
    fun observePosts(channel: String?, category: String?, q: String?, limit: Int): Flow<List<TelegramPostEntity>>

    @Query("SELECT COUNT(*) FROM telegram_posts") suspend fun countPosts(): Int
}

@Dao
interface MiscDao {
    @Query("SELECT * FROM notification_log WHERE id = :id") suspend fun notified(id: String): NotificationLogEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun logNotification(n: NotificationLogEntity)
    @Query("DELETE FROM notification_log WHERE notifiedAt < :before") suspend fun pruneNotifications(before: Long)
    @Query("SELECT COUNT(*) FROM notification_log WHERE notifiedAt >= :since") suspend fun notificationsSince(since: Long): Int

    @Insert suspend fun insertRun(r: SyncRunEntity): Long
    @Update suspend fun updateRun(r: SyncRunEntity)
    @Query("SELECT * FROM sync_runs ORDER BY startedAt DESC LIMIT :limit") fun observeRuns(limit: Int): Flow<List<SyncRunEntity>>
    @Query("SELECT MAX(finishedAt) FROM sync_runs WHERE finishedAt IS NOT NULL") fun observeLastSync(): Flow<Long?>
    @Query("DELETE FROM sync_runs WHERE startedAt < :before") suspend fun pruneRuns(before: Long)
}

@Dao
interface MaintenanceDao {
    @RawQuery suspend fun checkpoint(q: SupportSQLiteQuery): Int
}
