package ca.newsarchive.core.feed

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

data class OpmlEntry(val title: String, val xmlUrl: String, val htmlUrl: String?, val category: String?)

object Opml {
    fun parse(xml: String): List<OpmlEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val body = doc.selectFirst("body") ?: throw FeedParseException("OPML file has no <body>")
        val out = mutableListOf<OpmlEntry>()
        fun walk(e: Element, category: String?) {
            for (o in e.children().filter { it.tagName().equals("outline", true) }) {
                val xmlUrl = o.attr("xmlUrl").ifBlank { o.attr("xmlurl") }.trim()
                val title = o.attr("title").ifBlank { o.attr("text") }.trim()
                if (xmlUrl.isNotEmpty()) {
                    out += OpmlEntry(title.ifBlank { xmlUrl }, xmlUrl, o.attr("htmlUrl").ifBlank { null }, o.attr("category").ifBlank { null } ?: category)
                } else {
                    walk(o, title.ifBlank { category })
                }
            }
        }
        walk(body, null)
        return out.distinctBy { it.xmlUrl }
    }

    fun write(title: String, entries: List<OpmlEntry>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>")
            .append(esc(title)).append("</title></head>\n<body>\n")
        entries.groupBy { it.category ?: "Uncategorized" }.toSortedMap().forEach { (cat, list) ->
            sb.append("  <outline text=\"").append(esc(cat)).append("\" title=\"").append(esc(cat)).append("\">\n")
            for (e in list) {
                sb.append("    <outline type=\"rss\" text=\"").append(esc(e.title)).append("\" title=\"").append(esc(e.title))
                    .append("\" xmlUrl=\"").append(esc(e.xmlUrl)).append('"')
                e.htmlUrl?.let { sb.append(" htmlUrl=\"").append(esc(it)).append('"') }
                sb.append("/>\n")
            }
            sb.append("  </outline>\n")
        }
        sb.append("</body>\n</opml>\n")
        return sb.toString()
    }
}
