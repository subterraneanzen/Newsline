package ca.newsarchive.app.ingest

import androidx.room.withTransaction
import ca.newsarchive.app.data.AppDatabase
import ca.newsarchive.app.data.ArticleEntity
import ca.newsarchive.app.data.ArticleFts
import ca.newsarchive.app.data.ArticleVersionEntity
import ca.newsarchive.app.data.Availability
import ca.newsarchive.app.data.GeoTagEntity
import ca.newsarchive.app.data.SettingsStore
import ca.newsarchive.app.data.SourceEntity
import ca.newsarchive.app.data.TopicTagEntity
import ca.newsarchive.core.cluster.StoryClusterer
import ca.newsarchive.core.diff.VersionDiff
import ca.newsarchive.core.diff.VersionSnapshot
import ca.newsarchive.core.extract.ArticleExtractor
import ca.newsarchive.core.geo.GeoClassifier
import ca.newsarchive.core.geo.Gazetteer
import ca.newsarchive.core.model.Block
import ca.newsarchive.core.model.BlockType
import ca.newsarchive.core.model.ExtractedArticle
import ca.newsarchive.core.model.ExtractionStatus
import ca.newsarchive.core.model.FeedItem
import ca.newsarchive.core.net.FetchResult
import ca.newsarchive.core.net.HttpFetcher
import ca.newsarchive.core.text.TextNormalizer
import ca.newsarchive.core.text.UrlCanonicalizer
import ca.newsarchive.core.topic.TopicClassifier

/**
 * Turns a discovered feed item into an archived article (first snapshot), and revisits archived
 * articles to record new versions when their accessible content changes meaningfully.
 */
class ArticleProcessor(
    private val db: AppDatabase,
    private val http: HttpFetcher,
    private val settings: SettingsStore,
    private val stories: StoryService,
) {
    private val articles = db.articles()
    private val versions = db.versions()

    data class Body(val blocks: List<Block>, val status: ExtractionStatus, val note: String?)

    /** Returns the new article id, or null if it already existed. */
    suspend fun ingest(source: SourceEntity, item: FeedItem, now: Long = System.currentTimeMillis()): Long? {
        val link = item.link?.takeIf { it.startsWith("http") } ?: return null
        // Video clips aren't articles: leave them out of the archive.
        if (ca.newsarchive.core.filter.VideoLinks.isVideo(link, item.title)) return null
        val canonical = UrlCanonicalizer.canonicalize(link)
        if (articles.byCanonical(canonical) != null) return null
        item.guid?.let { g -> if (articles.idByGuid(source.id, g) != null) return null }
        // Same story from the same publisher under a different link (e.g. listed in two of its feeds).
        item.title?.let { TextNormalizer.clean(it) }?.takeIf { it.length > 15 }?.let { t ->
            val host = UrlCanonicalizer.host(link)
            if (host != null && articles.urlsWithTitle(t, now - 14 * 86_400_000L).any { UrlCanonicalizer.host(it) == host }) return null
        }

        // Fetch the article page. Failures are recorded, never papered over.
        var fetchNote: String? = null
        var page: FetchResult? = null
        try {
            page = http.get(link, accept = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            if (!page.ok) fetchNote = "The article page returned HTTP ${page.status}."
        } catch (e: Exception) {
            fetchNote = "The article page could not be downloaded (${e.message ?: e.javaClass.simpleName})."
        }
        val extracted: ExtractedArticle? = page?.takeIf { it.ok }?.let { p ->
            runCatching { ArticleExtractor.extract(p.text(), p.finalUrl) }.getOrNull()
        }
        // Same article reached via a different link (syndicated across feeds)?
        extracted?.canonicalUrl?.let { c ->
            val cc = UrlCanonicalizer.canonicalize(c)
            if (cc != canonical && articles.byCanonical(cc) != null) return null
        }
        val body = chooseBody(item, extracted, fetchNote)

        val title = TextNormalizer.clean(item.title ?: extracted?.title ?: "").ifBlank { "Untitled article" }
        val published = item.publishedAt ?: extracted?.publishedAt
        val modified = (extracted?.modifiedAt ?: item.updatedAt)?.takeIf { it != published }
        val words = body.blocks.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
        val revisit = settings.current.revisitEnabled

        val geo = GeoClassifier.classify(title, body.blocks.map { it.text }, item.categories, source.placeCode.takeIf { source.isLocal })
        val topics = TopicClassifier.classify(title, body.blocks.map { it.text }, item.categories + listOfNotNull(extracted?.section))
        val sortTime = published?.coerceAtMost(now + 3_600_000) ?: now
        val lead = (item.summary ?: "") + " " + body.blocks.take(3).joinToString(" ") { it.text }
        val sig = StoryClusterer.signature(title, lead, geo.filter { it.level.depth >= 2 }.map { it.code }.toSet(), sortTime)

        val article = ArticleEntity(
            sourceId = source.id,
            url = link,
            canonicalUrl = canonical,
            guid = item.guid,
            title = title,
            author = item.author ?: extracted?.author,
            summary = (item.summary ?: extracted?.description)?.take(600),
            publishedAt = published,
            publisherModifiedAt = modified,
            discoveredAt = now,
            sortTime = sortTime,
            extractionStatus = body.status.name,
            extractionNote = body.note,
            articleType = extracted?.articleType?.name ?: "UNKNOWN",
            imageUrl = item.imageUrl ?: extracted?.leadImage,
            language = extracted?.language,
            section = extracted?.section,
            keywords = extracted?.keywords?.joinToString(", ")?.ifBlank { null },
            wordCount = words,
            versionCount = 1,
            lastCheckedAt = now,
            nextCheckAt = if (revisit) now + RevisitSchedule.delayFor(0) else null,
            signature = sig.serialize(),
        )
        val id = db.withTransaction {
            val newId = articles.insert(article)
            if (newId <= 0) return@withTransaction null
            versions.insert(
                ArticleVersionEntity(
                    articleId = newId, versionNo = 1, capturedAt = now, title = title, author = article.author,
                    publisherModifiedAt = modified, body = ArticleVersionEntity.encodeBlocks(body.blocks),
                    contentHash = TextNormalizer.contentHash(title, body.blocks), changeSummary = null,
                    images = extracted?.images?.joinToString("\n") { it.url }?.ifBlank { null }, extractionStatus = body.status.name,
                ),
            )
            db.tags().insertGeo(geo.map { GeoTagEntity(newId, it.code, it.level.name, it.confidence, it.inferred) })
            db.tags().insertTopics(topics.map { TopicTagEntity(newId, it.topic, it.confidence) })
            db.fts().upsert(ftsRow(newId, title, body.blocks, source.name, article.author, geo.map { it.code }, topics.map { it.topic }, article.summary))
            newId
        } ?: return null
        stories.assign(id, sig, canonical)
        return id
    }

    private fun chooseBody(item: FeedItem, extracted: ExtractedArticle?, fetchNote: String?): Body {
        val feedBlocks = item.contentHtml?.let { ArticleExtractor.blocksFromHtml(it) }?.takeIf { it.isNotEmpty() }
            ?: item.summary?.let { listOf(Block(BlockType.PARAGRAPH, it)) } ?: emptyList()
        val feedWords = feedBlocks.sumOf { b -> b.text.split(' ').size }
        if (extracted != null && extracted.status != ExtractionStatus.FAILED) {
            val ew = extracted.wordCount
            if (extracted.status == ExtractionStatus.PAYWALLED || ew >= feedWords * 0.8) {
                return Body(extracted.blocks, extracted.status, extracted.note)
            }
        }
        // Extraction unavailable or weaker than what the feed itself supplies.
        return if (feedWords >= 150 && item.contentHtml != null) {
            Body(feedBlocks, ExtractionStatus.FULL, "Text taken from the publisher's feed.")
        } else {
            val reason = extracted?.note ?: fetchNote ?: "The article page could not be extracted."
            Body(feedBlocks, ExtractionStatus.FEED_ONLY, "$reason Only the feed summary was saved.")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Revisiting

    enum class RevisitOutcome { UNCHANGED, NEW_VERSION, GONE, FAILED }

    suspend fun revisit(a: ArticleEntity, now: Long = System.currentTimeMillis()): RevisitOutcome {
        val res = try {
            http.get(a.url, accept = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
        } catch (e: Exception) {
            articles.update(a.copy(lastCheckedAt = now, checkCount = a.checkCount + 1, nextCheckAt = next(a, now, false)))
            return RevisitOutcome.FAILED
        }
        if (res.gone) {
            // Require two consecutive "not found" answers before declaring the article removed.
            val confirmed = a.availability != Availability.AVAILABLE
            articles.update(
                a.copy(
                    availability = if (confirmed) Availability.GONE else Availability.UNREACHABLE,
                    unavailableSince = a.unavailableSince ?: now,
                    lastCheckedAt = now, checkCount = a.checkCount + 1,
                    nextCheckAt = if (confirmed) null else now + 6 * 3_600_000L,
                ),
            )
            return if (confirmed) RevisitOutcome.GONE else RevisitOutcome.FAILED
        }
        if (!res.ok) {
            articles.update(a.copy(lastCheckedAt = now, checkCount = a.checkCount + 1, nextCheckAt = next(a, now, false)))
            return RevisitOutcome.FAILED
        }
        val ex = runCatching { ArticleExtractor.extract(res.text(), res.finalUrl) }.getOrNull()
        val prev = versions.latest(a.id)
        val back = a.copy(availability = Availability.AVAILABLE, unavailableSince = null, lastCheckedAt = now, checkCount = a.checkCount + 1)

        // An extraction failure or a new paywall is an access change, not an edit: don't record it as removals.
        val prevStatus = prev?.extractionStatus
        if (ex == null || ex.status == ExtractionStatus.FAILED || prev == null ||
            (ex.status == ExtractionStatus.PAYWALLED && prevStatus != ExtractionStatus.PAYWALLED.name) ||
            (prevStatus == ExtractionStatus.FEED_ONLY.name && ex.status != ExtractionStatus.FULL)
        ) {
            val note = when {
                ex?.status == ExtractionStatus.PAYWALLED && prevStatus != ExtractionStatus.PAYWALLED.name ->
                    "The publisher now marks this article as subscriber-only; the archived version is preserved."
                else -> a.extractionNote
            }
            articles.update(back.copy(extractionNote = note, nextCheckAt = next(a, now, false)))
            return RevisitOutcome.UNCHANGED
        }
        val title = TextNormalizer.clean(ex.title ?: a.title).ifBlank { a.title }
        val old = VersionSnapshot(prev.title, prev.author, prev.publisherModifiedAt, prev.paragraphs())
        val new = VersionSnapshot(title, ex.author ?: prev.author, ex.modifiedAt, ex.blocks.map { it.text })
        if (!VersionDiff.isMeaningfulChange(old, new)) {
            articles.update(back.copy(publisherModifiedAt = ex.modifiedAt ?: a.publisherModifiedAt, nextCheckAt = next(a, now, false)))
            return RevisitOutcome.UNCHANGED
        }
        val cmp = VersionDiff.compare(old, new)
        val source = db.sources().get(a.sourceId)
        db.withTransaction {
            versions.insert(
                ArticleVersionEntity(
                    articleId = a.id, versionNo = prev.versionNo + 1, capturedAt = now, title = title,
                    author = new.author, publisherModifiedAt = ex.modifiedAt, body = ArticleVersionEntity.encodeBlocks(ex.blocks),
                    contentHash = TextNormalizer.contentHash(title, ex.blocks), changeSummary = cmp.summary(),
                    images = ex.images.joinToString("\n") { it.url }.ifBlank { null }, extractionStatus = ex.status.name,
                ),
            )
            articles.update(
                back.copy(
                    title = title, author = new.author, publisherModifiedAt = ex.modifiedAt ?: a.publisherModifiedAt,
                    versionCount = prev.versionNo + 1, extractionStatus = ex.status.name, extractionNote = ex.note,
                    wordCount = ex.wordCount, nextCheckAt = next(a, now, true),
                ),
            )
            val paragraphs = ex.blocks.map { it.text }
            if (!a.geoManual) {
                db.tags().clearAutoGeo(a.id)
                val g = GeoClassifier.classify(title, paragraphs, emptyList(), source?.takeIf { it.isLocal }?.placeCode)
                db.tags().insertGeo(g.map { GeoTagEntity(a.id, it.code, it.level.name, it.confidence, it.inferred) })
            }
            val geoCodes = db.tags().geoFor(a.id).map { it.code }
            if (!a.topicsManual) {
                db.tags().clearAutoTopics(a.id)
                db.tags().insertTopics(TopicClassifier.classify(title, paragraphs).map { TopicTagEntity(a.id, it.topic, it.confidence) })
            }
            val topicNames = db.tags().topicsFor(a.id).map { it.topic }
            db.fts().upsert(ftsRow(a.id, title, ex.blocks, source?.name ?: "", new.author, geoCodes, topicNames, a.summary))
        }
        return RevisitOutcome.NEW_VERSION
    }

    private fun next(a: ArticleEntity, now: Long, changed: Boolean): Long? {
        if (!settings.current.revisitEnabled) return null
        val limit = a.discoveredAt + settings.current.revisitDays * 86_400_000L
        val delay = if (changed) RevisitSchedule.delayFor(1) else RevisitSchedule.delayFor(a.checkCount + 1)
        val t = now + delay
        return if (t > limit) null else t
    }

    companion object {
        fun ftsRow(id: Long, title: String, blocks: List<Block>, publisher: String, author: String?, geo: List<String>, topics: List<String>, summary: String?) =
            ArticleFts(
                rowId = id, title = title,
                body = (listOfNotNull(summary) + blocks.map { it.text }).joinToString("\n"),
                publisher = publisher, author = author ?: "",
                places = geo.mapNotNull { Gazetteer.get(it)?.name ?: if (it == Gazetteer.SPACE_CODE) "Space" else null }.joinToString(" "),
                topics = topics.joinToString(" "),
            )
    }
}

/** Revisit cadence: frequent at first (articles change most in their first hours), then tapering off. */
object RevisitSchedule {
    private val hours = listOf(1, 3, 6, 12, 24, 48, 96, 168)
    fun delayFor(checkCount: Int): Long = hours[checkCount.coerceIn(0, hours.size - 1)] * 3_600_000L
}
