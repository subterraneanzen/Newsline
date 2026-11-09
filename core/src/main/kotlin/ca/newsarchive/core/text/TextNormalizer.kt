package ca.newsarchive.core.text

import ca.newsarchive.core.model.Block
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer

object TextNormalizer {
    private val ws = Regex("[\\s\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]+")
    private val zeroWidth = Regex("[\\u200B-\\u200D\\uFEFF\\u00AD]")

    /** Display-safe cleanup: collapses whitespace, removes invisible characters. Keeps punctuation/case. */
    fun clean(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(zeroWidth, "").replace(ws, " ").trim()

    /**
     * Comparison form used for hashing and change detection. Formatting-only differences
     * (curly vs straight quotes, dash styles, whitespace, case) produce the same output.
     */
    fun comparable(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(zeroWidth, "")
        t = t.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
            .replace('–', '-').replace('—', '-').replace('…', '.')
            .replace("...", ".")
        return t.replace(ws, " ").trim().lowercase()
    }

    fun sha256(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Hash of the meaningful content of an article version (headline + body). */
    fun contentHash(title: String?, blocks: List<Block>): String =
        sha256(comparable(title ?: "") + "\n" + blocks.filter { it.text.isNotBlank() }
            .joinToString("\n") { comparable(it.text) })

    fun bodyHash(paragraphs: List<String>): String =
        sha256(paragraphs.filter { it.isNotBlank() }.joinToString("\n") { comparable(it) })
}

/** Removes tracking parameters and fragments so the same article is recognised under different links. */
object UrlCanonicalizer {
    private val trackingPrefixes = listOf("utm_", "mc_", "pk_", "hsa_", "oly_", "vero_", "_hs")
    private val trackingExact = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "yclid", "igshid", "ref", "ref_src", "ref_url",
        "cmpid", "cmp", "ito", "ns_mchannel", "ns_source", "ns_campaign", "ns_linkname", "ns_fee",
        "smid", "smtyp", "taid", "at_medium", "at_campaign",
        "s_cid", "spm", "guccounter", "guce_referrer", "guce_referrer_sig",
    )

    fun canonicalize(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            val u = URI(trimmed.replace(" ", "%20"))
            val scheme = (u.scheme ?: "https").lowercase().let { if (it == "http") "https" else it }
            val host = (u.host ?: return trimmed).lowercase().removePrefix("www.")
                .removePrefix("m.").removePrefix("amp.")
            var path = u.rawPath ?: ""
            path = path.replace(Regex("/amp/?$"), "").replace(Regex("\\.amp$"), "")
            if (path.length > 1) path = path.trimEnd('/')
            val query = u.rawQuery?.split('&')?.filter { p ->
                val k = p.substringBefore('=').lowercase()
                k.isNotEmpty() && trackingPrefixes.none { k.startsWith(it) } && k !in trackingExact
            }?.sorted()?.joinToString("&")
            buildString {
                append(scheme).append("://").append(host)
                if (u.port != -1 && u.port != 80 && u.port != 443) append(':').append(u.port)
                append(path)
                if (!query.isNullOrEmpty()) append('?').append(query)
            }
        }.getOrDefault(trimmed)
    }

    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    fun resolve(base: String?, href: String?): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim()
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (base == null) return null
        return runCatching { URI(base).resolve(h.replace(" ", "%20")).toString() }.getOrNull()
    }
}
