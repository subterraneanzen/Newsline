package ca.newsarchive.app.data

import androidx.sqlite.db.SimpleSQLiteQuery
import ca.newsarchive.core.geo.Gazetteer

enum class SortOrder { NEWEST, OLDEST, RELEVANCE }

/** All the ways the archive can be browsed or searched, combined into one query. */
data class ArticleFilter(
    val text: String? = null,
    val placeCode: String? = null,
    val topic: String? = null,
    val sourceId: Long? = null,
    val articleType: String? = null,
    val from: Long? = null,
    val to: Long? = null,
    val bookmarked: Boolean = false,
    val saved: Boolean = false,
    val history: Boolean = false,
    val unreadOnly: Boolean = false,
    val collectionId: Long? = null,
    val storyId: Long? = null,
    val minVersions: Int? = null,
    val sort: SortOrder = SortOrder.NEWEST,
    val limit: Int = 60,
)

object ArticleQuery {
    /** Turns user input into a safe FTS4 MATCH expression (prefix match on every word, AND-ed). */
    fun ftsExpression(text: String): String? {
        val phrases = Regex("\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        val rest = text.replace(Regex("\"[^\"]*\""), " ")
        val words = rest.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            .filterNot { it.equals("AND", true) || it.equals("OR", true) || it.equals("NOT", true) || it.equals("NEAR", true) }
        val parts = phrases.map { p -> "\"" + p.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.joinToString(" ") + "\"" } +
            words.map { "$it*" }
        return parts.filter { it != "\"\"" }.joinToString(" ").ifBlank { null }
    }

    fun build(f: ArticleFilter): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val joins = StringBuilder()

        f.text?.let(::ftsExpression)?.let {
            joins.append(" JOIN article_fts fts ON fts.rowid = a.id")
            where += "article_fts MATCH ?"; args += it
        }
        when (val p = f.placeCode) {
            null, Gazetteer.WORLD_CODE -> {}
            else -> { where += "EXISTS (SELECT 1 FROM geo_tags g WHERE g.articleId = a.id AND g.code = ?)"; args += p }
        }
        f.topic?.let { where += "EXISTS (SELECT 1 FROM topic_tags t WHERE t.articleId = a.id AND t.topic = ?)"; args += it }
        f.sourceId?.let { where += "a.sourceId = ?"; args += it }
        f.articleType?.let { where += "a.articleType = ?"; args += it }
        f.from?.let { where += "a.sortTime >= ?"; args += it }
        f.to?.let { where += "a.sortTime < ?"; args += it }
        if (f.bookmarked) where += "a.isBookmarked = 1"
        if (f.saved) where += "a.isSaved = 1"
        if (f.history) where += "a.readAt IS NOT NULL"
        if (f.unreadOnly) where += "a.isRead = 0"
        f.collectionId?.let { where += "a.id IN (SELECT articleId FROM collection_items WHERE collectionId = ?)"; args += it }
        f.storyId?.let { where += "a.storyId = ?"; args += it }
        f.minVersions?.let { where += "a.versionCount >= ?"; args += it }

        val order = when {
            f.history -> "a.readAt DESC"
            f.bookmarked -> "a.bookmarkedAt DESC"
            f.saved -> "a.savedAt DESC"
            f.sort == SortOrder.OLDEST -> "a.sortTime ASC"
            else -> "a.sortTime DESC"
        }
        val sql = ARTICLE_LIST_SELECT + joins +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY $order LIMIT ${f.limit.coerceIn(1, 2000)}"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
