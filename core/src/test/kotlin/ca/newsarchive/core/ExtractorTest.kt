package ca.newsarchive.core

import ca.newsarchive.core.extract.ArticleExtractor
import ca.newsarchive.core.model.ArticleType
import ca.newsarchive.core.model.ExtractionStatus
import ca.newsarchive.core.telegram.TelegramPreview
import ca.newsarchive.core.text.Dates
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
}
