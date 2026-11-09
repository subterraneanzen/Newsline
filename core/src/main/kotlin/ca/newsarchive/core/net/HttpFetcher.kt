package ca.newsarchive.core.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

data class FetchResult(
    val status: Int,
    val finalUrl: String,
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
) {
    val notModified get() = status == 304
    val ok get() = status in 200..299
    val gone get() = status == 404 || status == 410

    fun text(): String = decode(bytes, contentType)

    companion object {
        fun decode(bytes: ByteArray, contentType: String?): String {
            val headerCs = contentType?.let { Regex("charset=\"?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val declCs = Regex("encoding=[\"']([\\w.:-]+)[\"']").find(head)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+charset=[\"']?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val cs = listOfNotNull(headerCs, declCs).firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, cs)
        }
    }
}

class FetchException(message: String, val status: Int? = null) : IOException(message)

/**
 * Minimal HTTP client on HttpURLConnection (no third-party dependency). Supports conditional
 * requests, gzip, cross-protocol redirects and a response size cap.
 */
class HttpFetcher(
    private val userAgent: String = "Mozilla/5.0 (Linux; Android) PersonalNewsArchive/1.0 (personal news reader)",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 25_000,
    private val maxBytes: Int = 6 * 1024 * 1024,
) {
    fun get(url: String, etag: String? = null, lastModified: String? = null, accept: String? = null): FetchResult {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", accept ?: "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.8, */*;q=0.5")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept-Language", "en-CA,en;q=0.9,fr;q=0.6,*;q=0.3")
                etag?.let { setRequestProperty("If-None-Match", it) }
                lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = conn.getHeaderField("Location") ?: throw FetchException("Redirect without location", code)
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { readCapped(decodeStream(it, conn.contentEncoding)) } ?: ByteArray(0)
                return FetchResult(code, current, bytes, conn.contentType, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
            } finally {
                conn.disconnect()
            }
        }
        throw FetchException("Too many redirects")
    }

    private fun decodeStream(s: InputStream, enc: String?): InputStream = when (enc?.lowercase()) {
        "gzip", "x-gzip" -> GZIPInputStream(s)
        "deflate" -> InflaterInputStream(s)
        else -> s
    }

    private fun readCapped(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) throw FetchException("Response larger than ${maxBytes / 1024 / 1024} MB")
        }
        return out.toByteArray()
    }
}
