package ca.newsarchive.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date formatting. Every displayed time is labelled with what it means (published / discovered / captured). */
object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayTime = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
    private val full = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    private val date = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    private val dayHeader = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())

    fun relative(t: Long, now: Long = System.currentTimeMillis()): String {
        val d = now - t
        return when {
            d < -60_000 -> dayTime.format(Instant.ofEpochMilli(t).atZone(zone))
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 12 * 3_600_000 -> "${d / 3_600_000} h ago"
            isSameDay(t, now) -> "Today ${time.format(Instant.ofEpochMilli(t).atZone(zone))}"
            isSameDay(t, now - 86_400_000) -> "Yesterday ${time.format(Instant.ofEpochMilli(t).atZone(zone))}"
            d < 300L * 86_400_000 -> dayTime.format(Instant.ofEpochMilli(t).atZone(zone))
            else -> date.format(Instant.ofEpochMilli(t).atZone(zone))
        }
    }

    fun full(t: Long): String = full.format(Instant.ofEpochMilli(t).atZone(zone))
    fun date(t: Long): String = date.format(Instant.ofEpochMilli(t).atZone(zone))
    fun dayHeader(t: Long): String = dayHeader.format(Instant.ofEpochMilli(t).atZone(zone))
    fun iso(t: Long): String = Instant.ofEpochMilli(t).toString()
    fun dayKey(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    fun startOfToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun isSameDay(a: Long, b: Long) = dayKey(a) == dayKey(b)

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun fileSafe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().replace(Regex("\\s+"), " ")

    fun articleType(t: String): String? = when (t) {
        "OPINION" -> "Opinion"; "EDITORIAL" -> "Editorial"; "ANALYSIS" -> "Analysis"; "REVIEW" -> "Review"; else -> null
    }
}
