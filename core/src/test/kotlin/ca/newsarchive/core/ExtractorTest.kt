package ca.newsarchive.core

import ca.newsarchive.core.extract.ArticleExtractor
import ca.newsarchive.core.model.ArticleType
import ca.newsarchive.core.model.Block
import ca.newsarchive.core.model.BlockCodec
import ca.newsarchive.core.model.BlockType
import ca.newsarchive.core.model.InlineStyle
import ca.newsarchive.core.model.Span
import ca.newsarchive.core.model.texts
import ca.newsarchive.core.model.ExtractionStatus
import ca.newsarchive.core.telegram.TelegramPreview
import ca.newsarchive.core.text.Dates
import ca.newsarchive.core.text.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractorTest {
    private val para = "The provincial government announced on Monday that it will expand funding for rural health clinics, citing long wait times, staffing shortages and travel distances that many residents face."

    private fun page(extraHead: String = "", body: String) = """<!doctype html><html lang="en-CA"><head>
        <title>Clinic funding expanded | Example Post</title>
        <meta property="og:title" content="Clinic funding expanded">
        <meta property="og:site_name" content="Example Post">
        <meta property="article:published_time" content="2026-11-09T12:00:00Z">
        <meta property="article:modified_time" content="2026-11-09T15:00:00Z">
        <meta name="author" content="Alex Writer">
        <link rel="canonical" href="https://post.example/news/clinic-funding">
        $extraHead</head><body>
        <header><nav><a href="/">Home</a><a href="/news">News</a></nav></header>
        <div class="cookie-consent">We use cookies <button>Accept</button></div>
        $body
        <aside class="related-stories"><h3>Related</h3><p><a href="/x">Another story that is related to this one and long enough</a></p></aside>
        <footer><p>Copyright 2026 Example Post. All rights reserved.</p></footer>
        <script>trackEverything()</script></body></html>"""

    @Test fun extractsArticleBodyAndMetadata() {
        val html = page(body = """<main><article class="story-body"><h1>Clinic funding expanded</h1>
            <p>$para</p><div class="ad-slot"><p>Advertisement</p></div>
            <p>$para Officials said the first clinics would open next spring.</p>
            <h2>What happens next</h2><p>$para A second phase is planned.</p>
            <p>Advertisement</p>
            <div class="newsletter-signup"><p>Sign up for our newsletter to receive the latest headlines every morning.</p></div>
            </article></main>""")
        val a = ArticleExtractor.extract(html, "https://post.example/news/clinic-funding?utm_source=x")
        assertEquals("Clinic funding expanded", a.title)
        assertEquals("Alex Writer", a.author)
        assertEquals(Dates.parse("2026-11-09T12:00:00Z"), a.publishedAt)
        assertEquals(Dates.parse("2026-11-09T15:00:00Z"), a.modifiedAt)
        assertEquals(ExtractionStatus.FULL, a.status)
        assertEquals(4, a.blocks.size, a.blocks.joinToString("\n") { it.type.name + ": " + it.text })
        assertFalse(a.bodyText().contains("Advertisement"))
        assertFalse(a.bodyText().contains("newsletter"))
        assertFalse(a.bodyText().contains("Related"))
        assertEquals("en", a.language)
    }

    @Test fun keepsInlineFormatting() {
        val html = page(body = """<main><article class="story-body">
            <p>The <strong>premier</strong> said the plan is <em>fully funded</em> and cited the
               <a href="/budget/2026">provincial budget</a> released last week, with more at
               <a href="https://gov.example/health">gov.example</a> for residents to read.</p>
            <p>$para</p></article></main>""")
        val a = ArticleExtractor.extract(html, "https://post.example/news/clinic-funding")
        val first = a.blocks.first()
        // Spans index the stored plain text, so slicing the text by a span yields the formatted phrase.
        fun phrase(style: ca.newsarchive.core.model.InlineStyle) =
            first.spans.filter { it.style == style }.map { first.text.substring(it.start, it.end) }
        assertEquals(listOf("premier"), phrase(ca.newsarchive.core.model.InlineStyle.BOLD))
        assertEquals(listOf("fully funded"), phrase(ca.newsarchive.core.model.InlineStyle.ITALIC))
        val links = first.spans.filter { it.style == ca.newsarchive.core.model.InlineStyle.LINK }
        assertEquals(listOf("provincial budget", "gov.example"), links.map { first.text.substring(it.start, it.end) })
        assertEquals("https://post.example/budget/2026", links[0].href)
        assertEquals("https://gov.example/health", links[1].href)
        // The plain text carries no markup, so diffing and search are unaffected.
        assertTrue(first.text.startsWith("The premier said the plan is fully funded"), first.text)
    }

    @Test fun keepsPhotosInPlaceWithCaptionsHeadingsAndNumbers() {
        val html = page(body = """<main><article class="story-body">
            <p>$para</p>
            <figure><img src="/img/clinic.jpg" srcset="/img/clinic-400.jpg 400w, /img/clinic-1200.jpg 1200w, /img/clinic-2400.jpg 2400w" width="1200" height="800" alt="A clinic">
              <figcaption>The clinic in Rosetown. (Jo Lee/Example Post)</figcaption></figure>
            <h3>What changes</h3>
            <ol start="3"><li>More nurses in rural areas across the province this year.</li><li>Longer hours at night and on weekends for all patients.</li></ol>
            <img class="author-avatar" src="/img/alex.jpg" width="200" height="200">
            <div class="lazy"><img src="data:image/gif;base64,R0lGOD" data-x="1"><noscript><img src="https://cdn.example/photo2.jpg" width="900" height="600"></noscript></div>
            <p>$para Second.</p></article></main>""")
        val a = ArticleExtractor.extract(html, "https://post.example/news/clinic-funding")
        val types = a.blocks.map { it.type.name }
        assertEquals(listOf("PARAGRAPH", "IMAGE", "HEADING", "LIST_ITEM", "LIST_ITEM", "IMAGE", "PARAGRAPH"), types)
        val photo = a.blocks[1]
        assertEquals("https://post.example/img/clinic-1200.jpg", photo.imageUrl)
        assertEquals("The clinic in Rosetown. (Jo Lee/Example Post)", photo.caption)
        assertEquals(1200 to 800, photo.width to photo.height)
        assertEquals("", photo.text)
        assertEquals(3, a.blocks[2].level)
        assertEquals(listOf(3, 4), a.blocks.filter { it.type.name == "LIST_ITEM" }.map { it.ordinal })
        assertEquals("https://cdn.example/photo2.jpg", a.blocks[5].imageUrl)
        // Photos add nothing to the text that versions are compared on.
        assertEquals(5, a.blocks.texts().size)
    }

    @Test fun blockCodecRoundTripsAndReadsOldArchives() {
        val blocks = listOf(
            Block(BlockType.HEADING, "What changes", level = 2),
            Block(BlockType.IMAGE, "", imageUrl = "https://x.example/a.jpg", caption = "A caption", width = 640, height = 480),
            Block(BlockType.LIST_ITEM, "First", ordinal = 1),
            Block(BlockType.PARAGRAPH, "Photo: someone", isCaption = true),
            Block(BlockType.PARAGRAPH, "Bold here", spans = listOf(Span(0, 4, InlineStyle.BOLD))),
        )
        assertEquals(blocks, BlockCodec.decode(BlockCodec.encode(blocks)))
        val old = "H|Old heading\nP|Old text\u0001" + "0\u00034\u0003B\u0003"
        val dec = BlockCodec.decode(old)
        assertEquals(listOf("Old heading", "Old text"), dec.texts())
        assertEquals(0, dec[0].level)
        assertEquals(1, dec[1].spans.size)
    }

    @Test fun respectsPaywallMarkup() {
        val ld = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"NewsArticle","headline":"Clinic funding expanded",
            "isAccessibleForFree":"False","hasPart":{"@type":"WebPageElement","isAccessibleForFree":"False","cssSelector":".paywalled"},
            "author":[{"@type":"Person","name":"Alex Writer"}]}</script>"""
        val html = page(ld, """<article><p>$para</p><div class="paywalled"><p>SECRET subscriber paragraph $para</p><p>More secret $para</p></div></article>""")
        val a = ArticleExtractor.extract(html, "https://post.example/news/clinic-funding")
        assertEquals(ExtractionStatus.PAYWALLED, a.status)
        assertFalse(a.bodyText().contains("SECRET"))
        assertTrue(a.note!!.contains("subscriber"))
    }

    @Test fun detectsOpinionFromJsonLdAndUrl() {
        val ld = """<script type="application/ld+json">[{"@type":"WebSite"},{"@type":"OpinionNewsArticle","headline":"x"}]</script>"""
        assertEquals(ArticleType.OPINION, ArticleExtractor.extract(page(ld, "<article><p>$para</p></article>"), "https://a.com/x").articleType)
        assertEquals(ArticleType.EDITORIAL, ArticleExtractor.extract(page(body = "<article><p>$para</p></article>"), "https://a.com/editorials/x").articleType)
        assertEquals(ArticleType.UNKNOWN, ArticleExtractor.extract(page(body = "<article><p>$para</p></article>"), "https://a.com/news/x").articleType)
    }

    @Test fun reportsFailureInsteadOfInventing() {
        val a = ArticleExtractor.extract("<html><head><title>App</title></head><body><div id=root></div><script>render()</script></body></html>", "https://spa.example/a")
        assertEquals(ExtractionStatus.FAILED, a.status)
        assertTrue(a.blocks.isEmpty())
    }

    @Test fun parsesTelegramPreview() {
        val html = """<html><body><div class="tgme_channel_info"><div class="tgme_channel_info_header_title"><span>Test Channel</span></div></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="testchan/101">
              <div class="tgme_widget_message_text">Breaking: something happened<br>Second line <a href="https://news.example/a">link</a></div>
              <a class="tgme_widget_message_date" href="https://t.me/testchan/101"><time datetime="2026-11-09T10:00:00+00:00">10:00</time></a>
              <span class="tgme_widget_message_views">1.2K</span></div></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="testchan/102">
              <a class="tgme_widget_message_photo_wrap" style="width:100px;background-image:url('https://cdn.example/p.jpg')"></a>
              <a class="tgme_widget_message_date"><time datetime="2026-11-09T11:00:00+00:00">11:00</time></a></div></div></body></html>"""
        val p = TelegramPreview.parse(html, "testchan")
        assertTrue(p.previewAvailable)
        assertEquals("Test Channel", p.title)
        assertEquals(listOf(102L, 101L), p.posts.map { it.postId })
        assertEquals("Breaking: something happened\nSecond line link", p.posts[1].text)
        assertEquals(listOf("https://news.example/a"), p.posts[1].linkUrls)
        assertEquals("https://cdn.example/p.jpg", p.posts[0].imageUrl)
        assertEquals("testchan", TelegramPreview.normalizeUsername("https://t.me/s/testchan/55"))
        assertEquals("testchan", TelegramPreview.normalizeUsername("@testchan"))
        assertNull(TelegramPreview.normalizeUsername("https://t.me/+AbCdEf"))
        assertFalse(TelegramPreview.parse("<html><body><div class=tgme_page>Private</div></body></html>", "x").previewAvailable)
    }

    @Test fun splitsLooseTextIntoParagraphs() {
        val s1 = "The city council voted on Tuesday to approve the new transit plan for the downtown core area."
        val s2 = "Residents had raised concerns about <b>parking</b> and noise during several long public meetings."
        val s3 = "Construction is expected to begin next spring and last about two years, officials said today."
        val s4 = "Opponents say they will keep pressing the council to reconsider parts of the plan before then."
        val html = """<html><body><article><div class="story">$s1<br><br>$s2<br>
            <br>$s3<p>$s4<br><br>$s1</p></div></article></body></html>"""
        val texts = ArticleExtractor.extract(html, "https://example.com/news/a").blocks.texts()
        assertEquals(5, texts.size, texts.toString())
        assertTrue(texts[1].startsWith("Residents had raised concerns about parking"))
        assertTrue(texts[3].startsWith("Opponents"))
    }

    @Test fun breaksUpLongJsonLdText() {
        val sentence = "This is a sentence in a story that came with no paragraph breaks at all from the publisher. "
        val parts = ArticleExtractor.splitLongParagraph(sentence.repeat(20).trim())
        assertTrue(parts.size >= 4, parts.size.toString())
        assertEquals(sentence.repeat(20).trim(), parts.joinToString(" "))
        assertEquals(1, ArticleExtractor.splitLongParagraph("Short one. Two.").size)
        assertEquals(TextNormalizer.bodyHash(listOf("A b. C d.")), TextNormalizer.bodyHash(listOf("A b.", "C d.")))
    }
}
