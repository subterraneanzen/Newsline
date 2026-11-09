package ca.newsarchive.core.extract

import ca.newsarchive.core.model.ArticleImage
import ca.newsarchive.core.model.ArticleType
import ca.newsarchive.core.model.Block
import ca.newsarchive.core.model.BlockType
import ca.newsarchive.core.model.ExtractedArticle
import ca.newsarchive.core.model.ExtractionStatus
import ca.newsarchive.core.text.Dates
import ca.newsarchive.core.text.MiniJson
import ca.newsarchive.core.text.TextNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Readability-style extractor. Pulls metadata from JSON-LD / OpenGraph / meta tags and the
 * article body from the densest block of paragraphs.
 *
 * It never bypasses access controls: when a publisher marks content as not freely accessible
 * (schema.org isAccessibleForFree=false), the paywalled parts it identifies are removed and
 * the result is reported as PAYWALLED.
 */
object ArticleExtractor {

    private val negative = Regex(
        "(^|[-_ ])(ad|ads|advert|advertisement|adslot|dfp|promo|promoted|sponsor|sponsored|related|recommend|recommended|" +
            "recirc|newsletter|subscribe|subscription|signup|share|sharing|social|comment|comments|cookie|consent|gdpr|" +
            "popup|modal|overlay|sidebar|breadcrumb|breadcrumbs|footer|nav|navbar|navigation|menu|outbrain|taboola|" +
            "teaser|trending|most-read|mostread|popular|toolbar|tags|byline-share|print-only|visually-hidden|sr-only|" +
            "author-bio|more-stories|read-more|readmore|paywall|piano|tp-modal|banner|skip)($|[-_ ])",
        RegexOption.IGNORE_CASE,
    )
    private val positive = Regex("(article|story|entry|post|content|body|text|main)", RegexOption.IGNORE_CASE)

    private val boilerplateLine = Regex(
        "^(advertisement|story continues below advertisement|continue reading( below)?|read more:?.*|related:.*|" +
            "sign up for .*|subscribe (now|today).*|share this (article|story).*|click here.*|" +
            "©.*|copyright ©?.*all rights reserved.*|this article was originally published.*newsletter.*|" +
            "listen to this article.*|getty images|ap photo)$",
        RegexOption.IGNORE_CASE,
    )

    fun extract(html: String, url: String): ExtractedArticle {
        val doc = Jsoup.parse(html, url)
        val ld = JsonLd.find(doc)
        val meta = Meta(doc)

        val title = firstNonBlank(
            meta.prop("og:title"), ld?.string("headline"), meta.name("twitter:title"),
            doc.selectFirst("h1")?.text(), doc.title(),
        )?.let { TextNormalizer.clean(it) }?.let { stripSiteSuffix(it, meta.prop("og:site_name")) }

        val author = firstNonBlank(
            ld?.authors()?.joinToString(", ")?.ifBlank { null },
            meta.name("author")?.takeUnless { it.startsWith("http") },
            meta.prop("article:author")?.takeUnless { it.startsWith("http") },
            meta.name("byl")?.removePrefix("By ")?.removePrefix("by "),
            doc.selectFirst("[rel=author], [itemprop=author] [itemprop=name], .byline__name, .author-name")?.text(),
        )?.let { TextNormalizer.clean(it) }?.take(200)

        val published = Dates.parse(ld?.string("datePublished"))
            ?: Dates.parse(meta.prop("article:published_time"))
            ?: Dates.parse(meta.name("pubdate") ?: meta.name("publish-date") ?: meta.name("parsely-pub-date") ?: meta.name("dc.date") ?: meta.name("date") ?: meta.itemprop("datePublished"))
            ?: Dates.parse(doc.selectFirst("article time[datetime], time[pubdate][datetime]")?.attr("datetime"))
        val modified = Dates.parse(ld?.string("dateModified"))
            ?: Dates.parse(meta.prop("article:modified_time"))
            ?: Dates.parse(meta.prop("og:updated_time"))
            ?: Dates.parse(meta.itemprop("dateModified"))

        val section = firstNonBlank(meta.prop("article:section"), ld?.stringOrFirst("articleSection"))
        val keywords = buildList {
            meta.name("news_keywords")?.split(',')?.let(::addAll)
            meta.name("keywords")?.split(',')?.let(::addAll)
            doc.select("meta[property=article:tag]").forEach { add(it.attr("content")) }
            ld?.keywords()?.let(::addAll)
        }.map { it.trim() }.filter { it.isNotEmpty() && it.length < 60 }.distinct().take(30)

        val type = articleType(ld?.types().orEmpty(), section, url)
        val accessibleForFree = ld?.accessibleForFree()

        // Remove publisher-declared paywalled sections before extracting anything.
        if (accessibleForFree == false) {
            ld?.paywalledSelectors()?.forEach { sel -> runCatching { doc.select(sel).remove() } }
        }

        val leadImage = firstNonBlank(meta.prop("og:image"), meta.name("twitter:image"), ld?.image())
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.ifBlank { null } ?: meta.prop("og:url")

        val (blocks, images) = extractBody(doc)
        var finalBlocks = blocks
        val ldBody = ld?.string("articleBody")
        if (accessibleForFree != false && ldBody != null && ldBody.length > finalBlocks.sumOf { it.text.length } * 1.3) {
            finalBlocks = ldBody.split(Regex("\\n\\s*\\n|\\n")).map { TextNormalizer.clean(it) }
                .filter { it.isNotEmpty() }.map { Block(BlockType.PARAGRAPH, it) }
        }
        // Drop a leading block that just repeats the headline.
        if (title != null && finalBlocks.firstOrNull()?.let { TextNormalizer.comparable(it.text) == TextNormalizer.comparable(title) } == true) {
            finalBlocks = finalBlocks.drop(1)
        }

        val words = finalBlocks.sumOf { b -> b.text.split(' ').count { it.isNotBlank() } }
        val (status, note) = when {
            accessibleForFree == false && words < 120 -> ExtractionStatus.PAYWALLED to
                "The publisher marks this article as subscriber-only. Only the freely accessible portion was saved."
            accessibleForFree == false -> ExtractionStatus.PAYWALLED to
                "The publisher marks part of this article as subscriber-only. Paywalled sections were not saved."
            words >= 100 -> ExtractionStatus.FULL to null
            words >= 30 -> ExtractionStatus.PARTIAL to "Only a short passage could be extracted. The full article may contain more."
            else -> ExtractionStatus.FAILED to "No readable article text was found on the page (it may require JavaScript, a login, or not be an article)."
        }

        return ExtractedArticle(
            title = title,
            author = author,
            publishedAt = published,
            modifiedAt = modified,
            siteName = firstNonBlank(meta.prop("og:site_name"), ld?.publisherName()),
            canonicalUrl = canonical,
            description = firstNonBlank(meta.prop("og:description"), meta.name("description"))?.let { TextNormalizer.clean(it) },
            language = doc.selectFirst("html")?.attr("lang")?.ifBlank { null }?.substringBefore('-')?.lowercase(),
            section = section,
            keywords = keywords,
            articleType = type,
            blocks = if (status == ExtractionStatus.FAILED) emptyList() else finalBlocks,
            images = images,
            leadImage = leadImage,
            status = status,
            note = note,
        )
    }

    /** Converts feed-supplied HTML (e.g. content:encoded) into blocks. */
    fun blocksFromHtml(html: String): List<Block> {
        val doc = Jsoup.parseBodyFragment(html)
        clean(doc)
        val out = collectBlocks(doc.body())
        if (out.isNotEmpty()) return out
        val t = TextNormalizer.clean(doc.text())
        return if (t.isEmpty()) emptyList() else listOf(Block(BlockType.PARAGRAPH, t))
    }

    // ---------------------------------------------------------------------------------------

    private fun extractBody(doc: Document): Pair<List<Block>, List<ArticleImage>> {
        clean(doc)
        val explicit = doc.select("[itemprop=articleBody], [data-component=text-block]").firstOrNull()?.takeIf { textLen(it) > 400 }
        val candidate = explicit ?: bestCandidate(doc) ?: doc.body() ?: return emptyList<Block>() to emptyList()
        val blocks = collectBlocks(candidate)
        val images = candidate.select("img").mapNotNull { img ->
            val src = listOf("data-src", "data-original", "data-lazy-src", "src").map { img.absUrl(it) }.firstOrNull { it.startsWith("http") }
                ?: return@mapNotNull null
            if (src.endsWith(".svg") || src.contains("pixel") || src.contains("spacer") || src.contains("1x1")) return@mapNotNull null
            val w = img.attr("width").toIntOrNull()
            if (w != null && w < 120) return@mapNotNull null
            val cap = img.closest("figure")?.selectFirst("figcaption")?.text()?.let { TextNormalizer.clean(it) }?.ifBlank { null }
                ?: img.attr("alt").ifBlank { null }
            ArticleImage(src, cap)
        }.distinctBy { it.url }.take(12)
        return blocks to images
    }

    private fun clean(doc: Document) {
        doc.select("script, style, noscript, iframe, form, nav, footer, aside, button, svg, canvas, video, audio, " +
            "select, input, textarea, [hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=complementary], " +
            "[role=dialog], [role=alertdialog], template, object, embed").remove()
        doc.select("header").filter { it.select("p").size < 2 }.forEach { it.remove() }
        val toRemove = doc.getAllElements().filter { el ->
            if (el.tagName() in setOf("html", "body", "article", "main")) return@filter false
            val sig = el.className() + " " + el.id()
            sig.isNotBlank() && negative.containsMatchIn(sig) && !el.hasAttr("itemprop")
        }
        // Only remove elements that are not themselves the main text container.
        toRemove.filter { textLen(it) < 2000 || linkDensity(it) > 0.4 }.forEach { it.remove() }
    }

    private fun bestCandidate(doc: Document): Element? {
        val scores = HashMap<Element, Double>()
        for (p in doc.select("p, div > br")) {
            val el = if (p.tagName() == "br") p.parent() ?: continue else p
            val len = textLen(el)
            if (len < 25) continue
            val commas = el.text().count { it == ',' }
            val score = 1.0 + commas + minOf(len / 100.0, 3.0)
            val parent = el.parent() ?: continue
            scores.merge(parent, score, Double::plus)
            parent.parent()?.let { scores.merge(it, score / 2, Double::plus) }
        }
        return scores.entries.map { (el, s) ->
            var adj = s * (1 - linkDensity(el))
            val sig = el.className() + " " + el.id()
            if (positive.containsMatchIn(sig)) adj *= 1.25
            if (el.tagName() == "article") adj *= 1.3
            el to adj
        }.maxByOrNull { it.second }?.first
    }

    private fun collectBlocks(root: Element): List<Block> {
        val out = mutableListOf<Block>()
        for (el in root.select("p, h2, h3, h4, blockquote, li, pre")) {
            // Skip containers whose text will be captured by a nested block.
            if (el.tagName() == "blockquote" && el.selectFirst("p") != null) continue
            if (el.tagName() == "li" && (el.selectFirst("p") != null || el.parents().any { it.tagName() == "nav" })) continue
            if (el.tagName() == "p" && el.parents().any { it.tagName() == "li" }) {
                // fine – list paragraph
            }
            val text = TextNormalizer.clean(textWithBreaks(el))
            if (text.isEmpty()) continue
            if (boilerplateLine.matches(text)) continue
            if (linkDensity(el) > 0.6 && text.length < 200) continue
            val type = when (el.tagName()) {
                "h2", "h3", "h4" -> BlockType.HEADING
                "blockquote" -> BlockType.QUOTE
                "li" -> BlockType.LIST_ITEM
                else -> if (el.parents().any { it.tagName() == "blockquote" }) BlockType.QUOTE else BlockType.PARAGRAPH
            }
            if (type == BlockType.HEADING && text.length > 200) continue
            if (out.lastOrNull()?.text == text) continue
            out += Block(type, text)
        }
        // Trailing headings with no following text are usually "Related" widgets.
        while (out.isNotEmpty() && out.last().type == BlockType.HEADING) out.removeAt(out.size - 1)
        return out
    }

    private fun textWithBreaks(el: Element): String {
        val sb = StringBuilder()
        fun walk(e: Element) {
            for (n in e.childNodes()) {
                when (n) {
                    is TextNode -> sb.append(n.text())
                    is Element -> if (n.tagName() == "br") sb.append(' ') else walk(n)
                }
            }
        }
        walk(el)
        return sb.toString()
    }

    private fun textLen(e: Element): Int = e.text().length
    private fun linkDensity(e: Element): Double {
        val total = e.text().length
        if (total == 0) return 0.0
        return e.select("a").sumOf { it.text().length }.toDouble() / total
    }

    private fun articleType(types: List<String>, section: String?, url: String): ArticleType {
        val t = types.map { it.lowercase() }
        when {
            "opinionnewsarticle" in t -> return ArticleType.OPINION
            "analysisnewsarticle" in t || "backgroundnewsarticle" in t -> return ArticleType.ANALYSIS
            "reviewnewsarticle" in t || "review" in t -> return ArticleType.REVIEW
            "reportagenewsarticle" in t -> return ArticleType.REPORTING
        }
        val s = section?.lowercase().orEmpty()
        when {
            Regex("\\beditorials?\\b").containsMatchIn(s) -> return ArticleType.EDITORIAL
            Regex("\\b(opinions?|commentary|columns?|op-ed)\\b").containsMatchIn(s) -> return ArticleType.OPINION
            Regex("\\banalysis\\b").containsMatchIn(s) -> return ArticleType.ANALYSIS
        }
        val path = url.lowercase().substringAfter("://").substringAfter('/')
        val segs = path.split('/', '?').take(4)
        return when {
            segs.any { it == "editorial" || it == "editorials" } -> ArticleType.EDITORIAL
            segs.any { it == "opinion" || it == "opinions" || it == "commentary" || it == "op-ed" || it == "comment" } -> ArticleType.OPINION
            segs.any { it == "analysis" } -> ArticleType.ANALYSIS
            else -> ArticleType.UNKNOWN
        }
    }

    private fun stripSiteSuffix(title: String, site: String?): String {
        if (site.isNullOrBlank()) return title
        for (sep in listOf(" | ", " - ", " – ", " — ", " :: ")) {
            if (title.endsWith(sep + site)) return title.removeSuffix(sep + site).trim()
        }
        return title
    }

    private fun firstNonBlank(vararg v: String?): String? = v.firstOrNull { !it.isNullOrBlank() }?.trim()

    private class Meta(val doc: Document) {
        fun prop(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[name=$p]")?.attr("content")?.ifBlank { null }
        fun name(n: String) = doc.selectFirst("meta[name=$n]")?.attr("content")?.ifBlank { null }
            ?: doc.select("meta[name]").firstOrNull { it.attr("name").equals(n, true) }?.attr("content")?.ifBlank { null }
        fun itemprop(n: String) = doc.selectFirst("[itemprop=$n]")?.let { it.attr("content").ifBlank { it.attr("datetime") } }?.ifBlank { null }
    }
}

/** Wrapper around the most article-like JSON-LD object on the page. */
internal class JsonLd(private val o: Map<String, Any?>) {
    companion object {
        private val articleTypes = setOf(
            "newsarticle", "article", "reportagenewsarticle", "opinionnewsarticle", "analysisnewsarticle",
            "backgroundnewsarticle", "reviewnewsarticle", "blogposting", "liveblogposting", "report", "socialmediaposting",
        )

        fun find(doc: Document): JsonLd? {
            val objs = mutableListOf<Map<String, Any?>>()
            fun collect(v: Any?) {
                when (v) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST") val m = v as Map<String, Any?>
                        objs += m
                        (m["@graph"] as? List<*>)?.forEach(::collect)
                        m["mainEntity"]?.let(::collect)
                    }
                    is List<*> -> v.forEach(::collect)
                }
            }
            for (s in doc.select("script[type=application/ld+json]")) {
                runCatching { collect(MiniJson.parse(s.data())) }
            }
            return objs.firstOrNull { o -> typesOf(o).any { it.lowercase() in articleTypes } }?.let(::JsonLd)
        }

        fun typesOf(o: Map<String, Any?>): List<String> = when (val t = o["@type"]) {
            is String -> listOf(t)
            is List<*> -> t.filterIsInstance<String>()
            else -> emptyList()
        }
    }

    fun types() = typesOf(o)
    fun string(k: String): String? = (o[k] as? String)?.ifBlank { null }
    fun stringOrFirst(k: String): String? = when (val v = o[k]) {
        is String -> v.ifBlank { null }
        is List<*> -> v.filterIsInstance<String>().firstOrNull()
        else -> null
    }

    fun authors(): List<String> {
        fun nameOf(v: Any?): String? = when (v) {
            is String -> v.takeUnless { it.startsWith("http") }
            is Map<*, *> -> v["name"] as? String
            else -> null
        }
        return when (val a = o["author"]) {
            is List<*> -> a.mapNotNull(::nameOf)
            else -> listOfNotNull(nameOf(a))
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun keywords(): List<String> = when (val k = o["keywords"]) {
        is String -> k.split(',')
        is List<*> -> k.filterIsInstance<String>()
        else -> emptyList()
    }

    fun publisherName(): String? = (o["publisher"] as? Map<*, *>)?.get("name") as? String

    fun image(): String? = when (val i = o["image"]) {
        is String -> i
        is Map<*, *> -> i["url"] as? String
        is List<*> -> i.firstOrNull()?.let { f -> f as? String ?: (f as? Map<*, *>)?.get("url") as? String }
        else -> null
    }

    fun accessibleForFree(): Boolean? = when (val v = o["isAccessibleForFree"]) {
        is Boolean -> v
        is String -> when (v.lowercase()) { "false", "no" -> false; "true", "yes" -> true; else -> null }
        else -> null
    }

    fun paywalledSelectors(): List<String> {
        fun sel(v: Any?): List<String> = when (v) {
            is Map<*, *> -> if (v["isAccessibleForFree"].toString().lowercase() == "false") listOfNotNull(v["cssSelector"] as? String) else emptyList()
            is List<*> -> v.flatMap(::sel)
            else -> emptyList()
        }
        return sel(o["hasPart"])
    }
}
