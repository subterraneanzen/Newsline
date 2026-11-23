package ca.newsarchive.core.extract

import ca.newsarchive.core.model.ArticleImage
import ca.newsarchive.core.model.ArticleType
import ca.newsarchive.core.model.Block
import ca.newsarchive.core.model.BlockType
import ca.newsarchive.core.model.ExtractedArticle
import ca.newsarchive.core.model.ExtractionStatus
import ca.newsarchive.core.model.InlineStyle
import ca.newsarchive.core.model.Span
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

    /** Containers that publishers use for the story text itself. */
    private const val BODY_SELECTORS = "[itemprop=articleBody], [data-component=text-block], #cmsMainContent"

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
            val paras = ldBody.split(Regex("\\n\\s*\\n|\\n")).map { TextNormalizer.clean(it) }
                .filter { it.isNotEmpty() }.map { Block(BlockType.PARAGRAPH, it) }
            finalBlocks = placePhotos(paras, blocks)
        }
        // Drop a leading block that just repeats the headline (photos before it are kept).
        val firstText = finalBlocks.indexOfFirst { !it.isImage }
        if (title != null && firstText >= 0 && TextNormalizer.comparable(finalBlocks[firstText].text) == TextNormalizer.comparable(title)) {
            finalBlocks = finalBlocks.filterIndexed { i, _ -> i != firstText }
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
    fun blocksFromHtml(html: String, baseUrl: String = ""): List<Block> {
        val doc = Jsoup.parseBodyFragment(html, baseUrl)
        clean(doc)
        val out = collectBlocks(doc.body())
        if (out.any { !it.isImage }) return out
        val t = TextNormalizer.clean(doc.text())
        return if (t.isEmpty()) out else listOf(Block(BlockType.PARAGRAPH, t)) + out
    }

    /**
     * When the body text comes from JSON-LD (no markup), put the page's photos back at roughly the same
     * point in the text as on the page, measured by the share of text that came before each photo.
     */
    private fun placePhotos(paras: List<Block>, dom: List<Block>): List<Block> {
        val photos = dom.withIndex().filter { it.value.isImage }
        if (photos.isEmpty() || paras.isEmpty()) return paras
        val domTotal = dom.sumOf { it.text.length }.coerceAtLeast(1)
        val paraTotal = paras.sumOf { it.text.length }.coerceAtLeast(1)
        val at = photos.map { (i, b) -> dom.take(i).sumOf { it.text.length }.toDouble() / domTotal to b }
        val out = mutableListOf<Block>()
        var seen = 0; var k = 0
        for (p in paras) {
            while (k < at.size && at[k].first <= seen.toDouble() / paraTotal) out += at[k++].second
            out += p; seen += p.text.length
        }
        while (k < at.size) out += at[k++].second
        return out
    }

    // ---------------------------------------------------------------------------------------

    private fun extractBody(doc: Document): Pair<List<Block>, List<ArticleImage>> {
        clean(doc)
        val explicit = doc.select(BODY_SELECTORS).firstOrNull()?.takeIf { textLen(it) > 300 }
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
        rescueNoscriptImages(doc)
        doc.select("script, style, noscript, iframe, form, nav, footer, aside, button, svg, canvas, video, audio, " +
            "select, input, textarea, [hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=complementary], " +
            "[role=dialog], [role=alertdialog], template, object, embed").remove()
        doc.select("header").filter { it.select("p").size < 2 }.forEach { it.remove() }
        // Never strip a wrapper that holds a known article-body container, even when the wrapper's
        // class looks like boilerplate (CGTN puts its story text inside "cg2023_liveblog_comment";
        // paNOW's theme wraps the whole <article> in "sc-sidebar-wrapper"). The same goes for the
        // wrappers of an <article>/<main> that holds real paragraphs.
        val protectedEls = HashSet<Element>()
        doc.select(BODY_SELECTORS).forEach { protectedEls += it; protectedEls += it.parents() }
        doc.select("article, main").filter { a -> a.select("p").count { it.text().length > 80 } >= 2 }
            .forEach { protectedEls += it.parents() }
        val toRemove = doc.getAllElements().filter { el ->
            if (el.tagName() in setOf("html", "body", "article", "main") || el in protectedEls) return@filter false
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
        val captured = HashSet<Element>()   // figcaptions whose text already became caption paragraphs
        for (el in root.select("p, h2, h3, h4, blockquote, li, pre, img")) {
            if (el.tagName() == "img") {
                photoBlock(el, captured)?.let { ph -> if (out.none { it.isImage && it.imageUrl == ph.imageUrl }) out += ph }
                continue
            }
            // Skip containers whose text will be captured by a nested block.
            if (el.tagName() == "blockquote" && el.selectFirst("p") != null) continue
            if (el.tagName() == "li" && (el.selectFirst("p") != null || el.parents().any { it.tagName() == "nav" })) continue
            val text = TextNormalizer.clean(textWithBreaks(el))
            if (text.isEmpty()) continue
            if (boilerplateLine.matches(text)) continue
            if (linkDensity(el) > 0.6 && text.length < 200) continue
            // A paragraph inside a list item is that item (the first one carries the bullet or number).
            val li = if (el.tagName() == "li") el else if (el.tagName() == "p") el.parents().firstOrNull { it.tagName() == "li" } else null
            val type = when (el.tagName()) {
                "h2", "h3", "h4" -> BlockType.HEADING
                "blockquote" -> BlockType.QUOTE
                "li" -> BlockType.LIST_ITEM
                else -> when {
                    el.parents().any { it.tagName() == "blockquote" } -> BlockType.QUOTE
                    li != null -> BlockType.LIST_ITEM
                    else -> BlockType.PARAGRAPH
                }
            }
            if (type == BlockType.HEADING && text.length > 200) continue
            if (out.lastOrNull { !it.isImage }?.text == text) continue
            val ordinal = if (type != BlockType.LIST_ITEM || li == null) 0 else {
                val firstPara = el.tagName() == "li" || li.select("p").firstOrNull() == el
                if (!firstPara) -1 else listNumber(li)
            }
            val figcaption = el.parents().firstOrNull { it.tagName() == "figcaption" }
            if (figcaption != null) captured += figcaption
            out += Block(
                type, text, inlineSpans(el, text),
                level = if (type == BlockType.HEADING) el.tagName()[1].digitToInt() else 0,
                ordinal = ordinal,
                isCaption = figcaption != null && type == BlockType.PARAGRAPH,
            )
        }
        // Trailing headings with no following text are usually "Related" widgets; drop them and
        // any photos after them (photos after the last paragraph with no heading between stay).
        val lastText = out.indexOfLast { !it.isImage && it.type != BlockType.HEADING }
        val cut = (lastText + 1 until out.size).firstOrNull { out[it].type == BlockType.HEADING }
        if (cut != null) while (out.size > cut) out.removeAt(out.size - 1)
        return out
    }

    /** 0 for a bullet, otherwise the item's number in its <ol> (honouring start=). */
    private fun listNumber(li: Element): Int {
        val list = li.parent() ?: return 0
        if (list.tagName() != "ol") return 0
        val start = list.attr("start").toIntOrNull() ?: 1
        return start + list.children().filter { it.tagName() == "li" }.indexOf(li).coerceAtLeast(0)
    }

    private val notAPhoto = Regex("(avatar|author|headshot|byline|logo|icon|emoji|sprite|badge|spinner|placeholder|tracking|pixel)", RegexOption.IGNORE_CASE)

    /** An inline photo with its caption, or null for icons, trackers, author headshots and the like. */
    private fun photoBlock(img: Element, captured: Set<Element>): Block? {
        val src = imageSrc(img) ?: return null
        val lower = src.lowercase().substringBefore('?')
        if (lower.endsWith(".svg") || lower.contains("spacer") || lower.contains("1x1") || lower.contains("blank.gif")) return null
        val sig = (listOf(img) + img.parents().take(3)).joinToString(" ") { it.className() + " " + it.id() } + " " + img.attr("alt")
        if (notAPhoto.containsMatchIn(sig) || notAPhoto.containsMatchIn(lower.substringAfterLast('/'))) return null
        val w = img.attr("width").filter { it.isDigit() }.toIntOrNull() ?: 0
        val h = img.attr("height").filter { it.isDigit() }.toIntOrNull() ?: 0
        if ((w in 1..119) || (h in 1..59)) return null
        val figure = img.parents().firstOrNull { it.tagName() == "figure" }
        // The caption goes with the last photo of a figure (galleries share one caption).
        val lastInFigure = figure == null || figure.select("img").lastOrNull() == img
        val figcaption = figure?.selectFirst("figcaption")
        val caption = if (!lastInFigure || figcaption == null) null
            else if (figcaption.selectFirst("p") != null || figcaption in captured) null   // shown as its own caption paragraph
            else TextNormalizer.clean(figcaption.text()).ifBlank { null }
        return Block(BlockType.IMAGE, "", imageUrl = src, caption = caption, width = w, height = h)
    }

    /** Best address for an <img>: a phone-sized srcset entry, then lazy-load attributes, then src. */
    private fun imageSrc(img: Element): String? {
        val sets = buildList {
            add(img.attr("data-srcset")); add(img.attr("srcset")); add(img.attr("data-lazy-srcset"))
            img.parent()?.takeIf { it.tagName() == "picture" }?.select("source")
                ?.filter { s -> !s.attr("type").contains("avif") && s.attr("media").let { m -> m.isBlank() || "min-width" !in m } }
                ?.forEach { add(it.attr("data-srcset")); add(it.attr("srcset")) }
        }.filter { it.isNotBlank() }
        val fromSet = sets.firstNotNullOfOrNull { pickFromSrcset(it, img.baseUri()) }
        val plain = listOf("data-src", "data-original", "data-lazy-src", "data-url", "src")
            .map { img.absUrl(it) }.firstOrNull { it.startsWith("http") }
        return (fromSet ?: plain)?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun pickFromSrcset(set: String, base: String): String? {
        val entries = set.split(Regex(",\\s+(?=\\S)")).mapNotNull { part ->
            val bits = part.trim().split(Regex("\\s+"))
            val url = resolve(base, bits.firstOrNull() ?: return@mapNotNull null) ?: return@mapNotNull null
            val d = bits.getOrNull(1).orEmpty()
            val width = when {
                d.endsWith("w") -> d.dropLast(1).toIntOrNull() ?: 0
                d.endsWith("x") -> ((d.dropLast(1).toDoubleOrNull() ?: 1.0) * 800).toInt()
                else -> 0
            }
            url to width
        }
        if (entries.isEmpty()) return null
        return (entries.filter { it.second in 600..1600 }.maxByOrNull { it.second }
            ?: entries.filter { it.second > 1600 }.minByOrNull { it.second }
            ?: entries.maxByOrNull { it.second })?.first
    }

    private fun resolve(base: String, url: String): String? {
        if (url.startsWith("data:")) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return runCatching { java.net.URI(base).resolve(url.replace(" ", "%20")).toString() }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    /**
     * Lazy-loading pages put the real <img> inside <noscript> and a placeholder before it. Swap the
     * real one in (or drop the noscript copy when the visible image already has a real address) so the
     * photo survives the removal of <noscript>.
     */
    private fun rescueNoscriptImages(doc: Document) {
        for (ns in doc.select("noscript")) {
            val imgs = ns.select("img").ifEmpty {
                val raw = ns.html().ifBlank { ns.wholeText() }
                if ("<img" !in raw) emptyList() else Jsoup.parseBodyFragment(org.jsoup.parser.Parser.unescapeEntities(raw, false), ns.baseUri()).select("img").toList()
            }
            if (imgs.isEmpty()) continue
            val prev = ns.previousElementSibling()?.let { if (it.tagName() == "img") it else it.selectFirst("img") }
            if (prev != null && imageSrc(prev) != null) continue
            prev?.remove()
            imgs.reversed().forEach { ns.after(it.clone()) }
        }
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

    // Inline markup we keep for display. Offsets are over the block's cleaned plain text.
    private class StyledChar(val ch: Char, val bold: Boolean, val italic: Boolean, val code: Boolean, val href: String?)

    private fun isZeroWidth(c: Char) = c == '​' || c == '‌' || c == '‍' || c == '﻿' || c == '­'
    private fun isCollapsibleWs(c: Char) =
        c.isWhitespace() || c == ' ' || c in ' '..'​' || c == ' ' || c == ' ' || c == '　'

    /**
     * Walks [el] and records bold / italic / inline-code / link runs over its text, applying the same
     * whitespace-collapse and zero-width stripping as [TextNormalizer.clean]. Returns the spans only
     * when the reconstructed text exactly equals [cleaned] (the stored plain text); otherwise returns
     * none, so the plain text that diffing and search rely on is never contradicted by stray offsets.
     */
    private fun inlineSpans(el: Element, cleaned: String): List<Span> {
        val raw = ArrayList<StyledChar>()
        fun walk(e: Element, bold: Boolean, italic: Boolean, code: Boolean, href: String?) {
            for (n in e.childNodes()) {
                when (n) {
                    is TextNode -> n.text().forEach { raw += StyledChar(it, bold, italic, code, href) }
                    is Element -> {
                        val tag = n.tagName()
                        if (tag == "br") { raw += StyledChar(' ', bold, italic, code, href); continue }
                        val link = if (href == null && tag == "a")
                            n.absUrl("href").takeIf { it.startsWith("http://") || it.startsWith("https://") } else href
                        walk(n, bold || tag == "b" || tag == "strong", italic || tag == "i" || tag == "em",
                            code || tag == "code" || tag == "tt" || tag == "kbd", link)
                    }
                }
            }
        }
        walk(el, false, false, false, null)

        val kept = ArrayList<StyledChar>()
        var pendingWs: StyledChar? = null
        for (sc in raw) {
            if (isZeroWidth(sc.ch)) continue
            // A collapsed space keeps its own run's formatting, not the neighbouring word's, so emphasis
            // doesn't bleed across the gap between an emphasised word and the next plain one.
            if (isCollapsibleWs(sc.ch)) { pendingWs = sc; continue }
            pendingWs?.let { ws -> if (kept.isNotEmpty()) kept += StyledChar(' ', ws.bold, ws.italic, ws.code, ws.href) }
            pendingWs = null
            kept += sc
        }
        if (kept.size != cleaned.length || kept.withIndex().any { (i, sc) -> sc.ch != cleaned[i] }) return emptyList()

        val spans = ArrayList<Span>()
        fun runsOf(on: (StyledChar) -> Boolean, style: InlineStyle, hrefOf: (StyledChar) -> String? = { null }) {
            var i = 0
            while (i < kept.size) {
                if (!on(kept[i])) { i++; continue }
                val href = hrefOf(kept[i])
                var j = i + 1
                while (j < kept.size && on(kept[j]) && hrefOf(kept[j]) == href) j++
                spans += Span(i, j, style, href)
                i = j
            }
        }
        runsOf({ it.bold }, InlineStyle.BOLD)
        runsOf({ it.italic }, InlineStyle.ITALIC)
        runsOf({ it.code }, InlineStyle.CODE)
        runsOf({ it.href != null }, InlineStyle.LINK) { it.href }
        return spans
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
