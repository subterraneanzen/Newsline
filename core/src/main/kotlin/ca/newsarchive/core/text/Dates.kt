package ca.newsarchive.core.text

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Lenient parser for the date formats found in feeds and article metadata.
 * Returns epoch millis, or null if the string can't be understood. Never guesses.
 */
object Dates {
    private val zoneAbbrev = mapOf(
        "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "AST" to "-0400", "ADT" to "-0300", "NST" to "-0330", "NDT" to "-0230",
        "BST" to "+0100", "CET" to "+0100", "CEST" to "+0200", "EET" to "+0200",
        "EEST" to "+0300", "IST" to "+0530", "JST" to "+0900", "AEST" to "+1000",
        "AEDT" to "+1100", "SAST" to "+0200", "WAT" to "+0100", "EAT" to "+0300",
        "HKT" to "+0800", "SGT" to "+0800", "KST" to "+0900", "MSK" to "+0300",
    )

    private val rfc822: List<DateTimeFormatter> = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm Z",
        "EEE, d MMMM yyyy HH:mm:ss Z",
        "EEE d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yy HH:mm:ss Z",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(it)
            .toFormatter(Locale.ENGLISH)
    }

    private val isoLocal = DateTimeFormatterBuilder().parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE).optionalStart().appendLiteral('T').optionalEnd()
        .optionalStart().appendLiteral(' ').optionalEnd()
        .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':').appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter(Locale.ENGLISH)

    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().replace(Regex("\\s+"), " ")
        parseIso(s)?.let { return it }
        parseRfc822(s)?.let { return it }
        // Unix seconds / millis
        if (s.matches(Regex("\\d{10}"))) return s.toLong() * 1000
        if (s.matches(Regex("\\d{13}"))) return s.toLong()
        return null
    }

    private fun parseIso(s: String): Long? {
        if (!s.matches(Regex("^\\d{4}-\\d{2}-\\d{2}.*"))) return null
        var t = s.replace(' ', 'T').let { if (it.length > 10 && it[10] != 'T') it else it }
        // Normalise "+0000" -> "+00:00"
        t = t.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t, isoLocal).toInstant(ZoneOffset.UTC).toEpochMilli() }
        if (t.length == 10) runCatching { return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    private fun parseRfc822(input: String): Long? {
        var s = input.removeSuffix(".").trim()
        // Replace a trailing zone abbreviation with a numeric offset.
        val last = s.substringAfterLast(' ')
        zoneAbbrev[last.uppercase()]?.let { s = s.substringBeforeLast(' ') + " " + it }
        // "+00:00" -> "+0000"
        s = s.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        if (!Regex("[+-]\\d{4}$").containsMatchIn(s)) s = "$s +0000"
        for (f in rfc822) {
            runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        }
        return null
    }
}
