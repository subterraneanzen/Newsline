package ca.newsarchive.app.io

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonWriter
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.ArticleEntity
import ca.newsarchive.app.data.ArticleFilter
import ca.newsarchive.app.data.ArticleQuery
import ca.newsarchive.app.data.ArticleVersionEntity
import ca.newsarchive.app.data.SourceEntity
import ca.newsarchive.app.ingest.Maintenance
import ca.newsarchive.app.ui.Fmt
import ca.newsarchive.core.feed.Opml
import ca.newsarchive.core.feed.OpmlEntry
import java.io.File
import java.io.OutputStreamWriter

/** Export, import, backup and restore through the Storage Access Framework. All local. */
class ArchiveIO(private val context: Context, private val db: AppDatabase, private val maintenance: Maintenance) {
    private val resolver get() = context.contentResolver

    /** Full archive as JSON: sources, articles with every stored version, tags and collections. */
    suspend fun exportJson(uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val total = db.articles().count()
        var written = 0
        resolver.openOutputStream(uri, "wt")?.use { os ->
            JsonWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                w.setIndent(" ")
                w.beginObject()
                w.name("format").value("personal-news-archive")
                w.name("formatVersion").value(1)
                w.name("exportedAt").value(Fmt.iso(System.currentTimeMillis()))
                w.name("sources").beginArray()
                for (s in db.sources().all()) writeSource(w, s)
                w.endArray()
                w.name("articles").beginArray()
                var offset = 0
                while (true) {
                    val page = db.articles().page(200, offset)
                    if (page.isEmpty()) break
                    for (a in page) {
                        writeArticle(w, a, db.versions().allFor(a.id))
                        written++
                    }
                    offset += page.size
                    progress(written, total)
                }
                w.endArray()
                w.name("collections").beginArray()
                val cols = db.library().collections()
                for (c in cols) {
                    w.beginObject().name("name").value(c.name)
                    w.name("articleUrls").beginArray()
                    db.articles().queryList(ArticleQuery.build(ArticleFilter(collectionId = c.id, limit = 2000))).forEach { w.value(it.url) }
                    w.endArray().endObject()
                }
                w.endArray()
                w.endObject()
            }
        } ?: error("Couldn't open the chosen file for writing")
        return written
    }

    private fun writeSource(w: JsonWriter, s: SourceEntity) {
        w.beginObject()
        w.name("id").value(s.id); w.name("name").value(s.name); w.name("url").value(s.url); w.name("kind").value(s.kind)
        w.name("category").value(s.category); w.name("homepage").value(s.homepage); w.name("enabled").value(s.enabled)
        w.endObject()
    }

    private suspend fun writeArticle(w: JsonWriter, a: ArticleEntity, versions: List<ArticleVersionEntity>) {
        w.beginObject()
        w.name("id").value(a.id); w.name("sourceId").value(a.sourceId)
        w.name("url").value(a.url); w.name("title").value(a.title); w.name("author").value(a.author)
        w.name("publishedAt").value(a.publishedAt?.let(Fmt::iso))
        w.name("publisherModifiedAt").value(a.publisherModifiedAt?.let(Fmt::iso))
        w.name("discoveredAt").value(Fmt.iso(a.discoveredAt))
        w.name("articleType").value(a.articleType); w.name("extractionStatus").value(a.extractionStatus)
        w.name("availability").value(a.availability)
        w.name("bookmarked").value(a.isBookmarked); w.name("saved").value(a.isSaved); w.name("read").value(a.isRead)
        w.name("places").beginArray(); db.tags().geoFor(a.id).forEach { w.value(it.code) }; w.endArray()
        w.name("topics").beginArray(); db.tags().topicsFor(a.id).forEach { w.value(it.topic) }; w.endArray()
        w.name("versions").beginArray()
        for (v in versions) {
            w.beginObject()
            w.name("versionNo").value(v.versionNo); w.name("capturedAt").value(Fmt.iso(v.capturedAt))
            w.name("title").value(v.title); w.name("author").value(v.author)
            w.name("publisherModifiedAt").value(v.publisherModifiedAt?.let(Fmt::iso))
            w.name("contentHash").value(v.contentHash); w.name("changeSummary").value(v.changeSummary)
            w.name("paragraphs").beginArray(); v.paragraphs().forEach { w.value(it) }; w.endArray()
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    suspend fun articleAsText(articleId: Long): Pair<String, String>? {
        val a = db.articles().get(articleId) ?: return null
        val v = db.versions().latest(articleId) ?: return null
        val src = db.sources().get(a.sourceId)
        val text = buildString {
            appendLine(v.title)
            appendLine(listOfNotNull(src?.name, v.author).joinToString(" · "))
            appendLine()
            appendLine("Published (publisher-reported): ${a.publishedAt?.let(Fmt::full) ?: "not provided"}")
            a.publisherModifiedAt?.let { appendLine("Modified (publisher-reported): ${Fmt.full(it)}") }
            appendLine("Discovered by this app: ${Fmt.full(a.discoveredAt)}")
            appendLine("This snapshot captured: ${Fmt.full(v.capturedAt)} (version ${v.versionNo} of ${a.versionCount})")
            appendLine("Original URL: ${a.url}")
            if (a.availability == "GONE") appendLine("Note: no longer available from the publisher.")
            appendLine()
            v.paragraphs().forEach { appendLine(it); appendLine() }
        }
        val name = Fmt.fileSafe(v.title).take(80) + ".txt"
        return name to text
    }

    suspend fun exportArticleText(articleId: Long, uri: Uri) {
        val (_, text) = articleAsText(articleId) ?: error("Article not found")
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Couldn't open file")
    }

    /** Writes one .txt per article into a folder the user picked. */
    suspend fun exportTextFolder(treeUri: Uri, filter: ArticleFilter, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val items = db.articles().queryList(ArticleQuery.build(filter.copy(limit = 2000)))
        var n = 0
        for (item in items) {
            val (name, text) = articleAsText(item.id) ?: continue
            val doc = DocumentsContract.createDocument(resolver, parent, "text/plain", "${item.id}-$name") ?: continue
            resolver.openOutputStream(doc)?.use { it.write(text.toByteArray()) }
            n++
            progress(n, items.size)
        }
        return n
    }

    suspend fun exportOpml(uri: Uri): Int {
        val entries = db.sources().all().filter { it.kind != "TELEGRAM" }.map { OpmlEntry(it.name, it.url, it.homepage, it.category) }
        resolver.openOutputStream(uri, "wt")?.use { it.write(Opml.write("Personal News Archive sources", entries).toByteArray()) }
            ?: error("Couldn't open file")
        return entries.size
    }

    fun readOpml(uri: Uri): List<OpmlEntry> {
        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't read file")
        return Opml.parse(text)
    }

    /** Copies the SQLite database (after a WAL checkpoint) to the chosen file. */
    suspend fun backup(uri: Uri) {
        maintenance.checkpoint()
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        resolver.openOutputStream(uri, "wt")?.use { os -> dbFile.inputStream().use { it.copyTo(os) } } ?: error("Couldn't open file")
    }

    /**
     * Validates a backup file and stages it. The app must restart to use it; the caller confirms with
     * the user first because the current archive is replaced.
     */
    fun stageRestore(uri: Uri): File {
        val staged = File(context.filesDir, "restore-staged.db")
        resolver.openInputStream(uri)?.use { input -> staged.outputStream().use { input.copyTo(it) } } ?: error("Couldn't read file")
        val header = staged.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        require(String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { "This file is not a database backup." }
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('articles','article_versions','sources')", null).use { c ->
                require(c.count == 3) { "This database is not a News Archive backup." }
            }
        }
        return staged
    }

    companion object {
        /** Called at startup before Room opens the database. */
        fun applyStagedRestore(context: Context) {
            val staged = File(context.filesDir, "restore-staged.db")
            if (!staged.exists()) return
            val target = context.getDatabasePath(AppDatabase.NAME)
            File(target.path + "-wal").delete(); File(target.path + "-shm").delete()
            target.parentFile?.mkdirs()
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }
}
