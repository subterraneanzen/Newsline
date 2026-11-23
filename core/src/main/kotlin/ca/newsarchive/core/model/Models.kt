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

/** Inline emphasis the publisher applied inside a block's text. */
enum class InlineStyle { BOLD, ITALIC, CODE, LINK }

/**
 * A run of inline formatting over [start, end) of a block's plain [Block.text] (character offsets,
 * end exclusive). [href] is set only for [InlineStyle.LINK]. Spans may overlap (a bold link is two
 * spans). Offsets always index the stored plain text, so diffing, search and hashing ignore them.
 */
data class Span(val start: Int, val end: Int, val style: InlineStyle, val href: String? = null)

/**
 * A block of article body. Paragraph-level granularity is what versions are diffed on. [text] is the
 * plain text used everywhere (diff, search, hashing); [spans] carry the publisher's inline formatting
 * for display only and are empty when none was found or could not be aligned to [text].
 *
 * Display-only layout (never part of [text], so it never shows up as an edit between versions):
 * - [level]: heading level 2–4 (0 = unknown, older archives).
 * - [ordinal]: for LIST_ITEM, 0 = bullet, n > 0 = numbered "n.", -1 = a further paragraph of the same item.
 * - [caption]: a paragraph that is a photo caption ([isCaption]), or for IMAGE the caption text that
 *   wasn't captured as its own paragraph.
 * - IMAGE blocks have empty [text]; [imageUrl] and the publisher's [width]/[height] (0 = unknown).
 */
data class Block(
    val type: BlockType,
    val text: String,
    val spans: List<Span> = emptyList(),
    val level: Int = 0,
    val ordinal: Int = 0,
    val imageUrl: String? = null,
    val caption: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val isCaption: Boolean = false,
) {
    val isImage: Boolean get() = type == BlockType.IMAGE
}

enum class BlockType { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, IMAGE }

/** The text paragraphs only (photos left out): what versions are diffed, searched and classified on. */
fun List<Block>.texts(): List<String> = filter { !it.isImage }.map { it.text }

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
