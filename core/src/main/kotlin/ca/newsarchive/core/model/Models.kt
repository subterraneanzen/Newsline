package ca.newsarchive.core.model

/** Kind of document a source URL points at. */
enum class SourceKind { RSS, ATOM, RDF, SITEMAP, WEBSITE, TELEGRAM, API }

/** One entry discovered in a feed or sitemap. Fields are null when the source did not provide them. */
data class FeedItem(
    val title: String?,
    val link: String?,
    val guid: String?,
    val publishedAt: Long?,
    val updatedAt: Long?,
    val author: String?,
    val summary: String?,
    val contentHtml: String?,
    val categories: List<String> = emptyList(),
    val imageUrl: String? = null,
)

data class ParsedFeed(
    val kind: SourceKind,
    val title: String?,
    val siteLink: String?,
    val description: String?,
    val language: String?,
    val items: List<FeedItem>,
    /** For sitemap indexes: child sitemap URLs to follow. */
    val childSitemaps: List<String> = emptyList(),
)

/** Reporting vs. opinion etc. Only set to something other than UNKNOWN when metadata says so. */
enum class ArticleType { REPORTING, OPINION, EDITORIAL, ANALYSIS, REVIEW, UNKNOWN }

enum class ExtractionStatus {
    /** Full accessible body text was extracted. */
    FULL,
    /** Some text extracted but it looks incomplete (short, or truncated by publisher). */
    PARTIAL,
    /** Publisher marks the article as not freely accessible; only freely visible text is kept. */
    PAYWALLED,
    /** Page could not be extracted; only feed-provided text is stored. */
    FEED_ONLY,
    FAILED,
}

/** A block of article body. Paragraph-level granularity is what versions are diffed on. */
data class Block(val type: BlockType, val text: String)

enum class BlockType { PARAGRAPH, HEADING, QUOTE, LIST_ITEM }

data class ExtractedArticle(
    val title: String?,
    val author: String?,
    val publishedAt: Long?,
    val modifiedAt: Long?,
    val siteName: String?,
    val canonicalUrl: String?,
    val description: String?,
    val language: String?,
    val section: String?,
    val keywords: List<String>,
    val articleType: ArticleType,
    val blocks: List<Block>,
    val images: List<ArticleImage>,
    val leadImage: String?,
    val status: ExtractionStatus,
    /** Human readable explanation when status is not FULL. Never fabricated content. */
    val note: String?,
) {
    val wordCount: Int get() = blocks.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
    fun bodyText(): String = blocks.joinToString("\n\n") { it.text }
}

data class ArticleImage(val url: String, val caption: String?)
