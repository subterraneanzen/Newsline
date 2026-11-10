package ca.newsarchive.core

import ca.newsarchive.core.extract.ArticleExtractor
import ca.newsarchive.core.feed.FeedParser
import ca.newsarchive.core.net.HttpFetcher
import java.io.File
import kotlin.test.Test

class LiveProbe {
    @Test fun probe() {
        val list = System.getenv("PROBE_URLS") ?: return
        val http = HttpFetcher()
        val out = StringBuilder()
        File(list).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (name, url) = line.split('|', limit = 2)
            out.append("$name $url\n")
            try {
                val r = http.get(url)
                out.append("  fetch ${r.status} ${r.contentType} final=${r.finalUrl} bytes=${r.bytes.size}\n")
                if (url.endsWith("robots.txt")) { r.text().lines().filter { it.contains("itemap", true) }.forEach { out.append("    $it\n") }; return@forEach }
                val f = FeedParser.parse(r.text(), r.finalUrl)
                out.append("  parsed kind=${f.kind} items=${f.items.size} child=${f.childSitemaps.size} newest=${f.items.mapNotNull { it.publishedAt }.maxOrNull()?.let { java.time.Instant.ofEpochMilli(it) }}\n")
                f.items.take(2).forEach { i ->
                    out.append("   - ${i.title?.take(70)} | ${i.link}\n")
                    try {
                        val a = http.get(i.link!!, accept = "text/html,application/xhtml+xml,*/*;q=0.8")
                        val e = ArticleExtractor.extract(a.text(), a.finalUrl)
                        out.append("     article ${a.status} status=${e.status} words=${e.bodyText().split(Regex("\\s+")).size}\n")
                    } catch (t: Throwable) { out.append("     article ERR ${t::class.simpleName}: ${t.message?.take(100)}\n") }
                }
            } catch (t: Throwable) { out.append("  ERR ${t::class.simpleName}: ${t.message?.take(120)}\n") }
        }
        File("build/probe.txt").writeText(out.toString())
    }
}
