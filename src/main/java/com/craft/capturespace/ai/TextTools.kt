package com.craft.capturespace.ai

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Splits long text into parts that fit the model's 4,096-token window with room for the prompt and
 * the reply. 3,500 characters stays under about 2,500 tokens even for scripts that tokenise densely.
 */
object Chunker {
    const val CHUNK_CHARS = 3_500
    const val MAX_CHUNKS = 12

    /** All of [text] in parts of about [size] characters, cut at a paragraph or sentence end where possible. */
    fun split(text: String, size: Int): List<String> {
        val t = text.trim()
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            var end = minOf(t.length, i + size)
            if (end < t.length) {
                val para = t.lastIndexOf("\n\n", end).takeIf { it > i + size / 2 }
                val sentence = t.lastIndexOf(". ", end).takeIf { it > i + size / 2 }?.plus(1)
                end = para ?: sentence ?: end
            }
            t.substring(i, end).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            i = end
        }
        return out
    }

    fun chunks(text: String): List<String> {
        val t = text.trim()
        if (t.length <= CHUNK_CHARS) return listOf(t)
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length && out.size < MAX_CHUNKS) {
            var end = minOf(t.length, i + CHUNK_CHARS)
            if (end < t.length) {
                val para = t.lastIndexOf("\n\n", end).takeIf { it > i + CHUNK_CHARS / 2 }
                val sentence = t.lastIndexOf(". ", end).takeIf { it > i + CHUNK_CHARS / 2 }?.plus(1)
                end = para ?: sentence ?: end
            }
            out += t.substring(i, end).trim()
            i = end
        }
        if (i < t.length) out[out.size - 1] = out.last() + "\n[The rest of this document (${t.length - i} characters) was not analysed.]"
        return out.filter { it.isNotBlank() }
    }
}

/**
 * Finds places written out in text with code (not the model): street addresses ("221 Baker Street",
 * "12 MG Road") and named venues after "at", "in", "to" or "near" ("at Green Street clinic",
 * "to Central Station"). Only what is literally written is returned; nothing is looked up online.
 */
object PlaceFinder {
    private const val NAME = """(?:[A-Z][\w'&.-]*\s+){1,4}"""
    private const val STREET = """(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Boulevard|Blvd|Drive|Dr|Way|Nagar|Marg|Square|Court|Highway)"""
    private const val VENUE = """(?i:street|road|clinic|hospital|hotel|cafe|café|restaurant|station|airport|mall|park|school|college|university|office|centre|center|cinema|theatre|theater|bank|market|church|temple|mosque|library|museum|stadium|gym|pharmacy|salon)"""
    private val address = Regex("""\b\d{1,5}[A-Za-z]?,?\s+$NAME?$STREET\b\.?""")
    private val venue = Regex("""\b(?:at|in|to|near)\s+(?:the\s+)?($NAME$VENUE)\b""")

    fun find(text: String, limit: Int = 4): List<String> {
        val out = ArrayList<String>()
        fun add(raw: String) {
            val p = raw.trim().trimEnd('.', ',')
            if (p.length < 4) return
            // Keep the longer form when one place is part of another ("Green Street" in "Green Street clinic").
            if (out.any { it.contains(p, true) }) return
            out.removeAll { p.contains(it, true) }
            out += p
        }
        address.findAll(text).forEach { add(it.value) }
        venue.findAll(text).forEach { add(it.groupValues[1]) }
        return out.take(limit)
    }
}

/**
 * Finds when something happens, written in the text, with code (not the model): absolute dates
 * (with or without a year), "today", "tonight", "tomorrow", "day after tomorrow", weekdays
 * ("Tuesday", "next Tuesday", "this Friday"), and a time near the date ("1pm", "10:30 am", "13:00",
 * "noon"). Relative words count from [now], the moment the capture was made. Used to check the
 * model's dates, which a small model sometimes guesses.
 */
object WhenFinder {
    data class Found(val date: LocalDate, val time: LocalTime?) {
        fun toIso(): String = if (time == null) date.toString() else LocalDateTime.of(date, time).toString().take(16)
    }

    private val monthNames = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    private val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val iso = Regex("""\b(20\d{2})-(\d{1,2})-(\d{1,2})\b""")
    private val dmy = Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](20\d{2})\b""")
    private val dMon = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthNames)[a-z]*\.?(?:,?\s+(20\d{2}))?\b""", RegexOption.IGNORE_CASE)
    private val monD = Regex("""\b($monthNames)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(20\d{2}))?""", RegexOption.IGNORE_CASE)
    private val rel = Regex("""\b(day after tomorrow|today|tonight|tomorrow|tmrw)\b""", RegexOption.IGNORE_CASE)
    private val weekday = Regex("""\b(?:(next|this|on|coming)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun)\b""", RegexOption.IGNORE_CASE)
    private val time12 = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""", RegexOption.IGNORE_CASE)
    private val time24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val noon = Regex("""\b(noon|midday|midnight)\b""", RegexOption.IGNORE_CASE)

    fun find(text: String, now: LocalDateTime, limit: Int = 6): List<Found> = scan(text, now).map { it.first }.distinct().take(limit)

    /**
     * The same, with the words each date was read from, for the prompt:
     * "next Tuesday at 1pm" = Tuesday, 24 November 2026, 13:00.
     */
    fun hints(text: String, now: LocalDateTime, limit: Int = 6): String =
        scan(text, now).distinctBy { it.first }.take(limit).joinToString("; ") { (f, phrase) ->
            "\"$phrase\" = " + f.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)) +
                (f.time?.let { ", " + it.toString() } ?: "")
        }

    private fun scan(text: String, now: LocalDateTime): List<Pair<Found, String>> {
        val today = now.toLocalDate()
        val dates = ArrayList<Pair<IntRange, LocalDate>>()
        fun add(r: IntRange, d: LocalDate?) { if (d != null && dates.none { it.first.first <= r.last && r.first <= it.first.last }) dates += r to d }
        fun safe(y: Int, m: Int, d: Int) = try { LocalDate.of(y, m, d) } catch (_: Exception) { null }
        fun mon(s: String) = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(s.lowercase(Locale.ROOT).take(3)) + 1
        // No year written: the next time that day comes round (today counts).
        fun upcoming(m: Int, d: Int): LocalDate? = safe(today.year, m, d)?.let { if (it < today) safe(today.year + 1, m, d) else it }

        iso.findAll(text).forEach { add(it.range, safe(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())) }
        dmy.findAll(text).forEach { add(it.range, safe(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())) }
        dMon.findAll(text).forEach { m ->
            val y = m.groupValues[3]; val mo = mon(m.groupValues[2]); val d = m.groupValues[1].toInt()
            add(m.range, if (y.isNotEmpty()) safe(y.toInt(), mo, d) else upcoming(mo, d))
        }
        monD.findAll(text).forEach { m ->
            val y = m.groupValues[3]; val mo = mon(m.groupValues[1]); val d = m.groupValues[2].toInt()
            add(m.range, if (y.isNotEmpty()) safe(y.toInt(), mo, d) else upcoming(mo, d))
        }
        rel.findAll(text).forEach { m ->
            add(m.range, when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "today", "tonight" -> today
                "day after tomorrow" -> today.plusDays(2)
                else -> today.plusDays(1)
            })
        }
        weekday.findAll(text).forEach { m ->
            val w = m.groupValues[2].lowercase(Locale.ROOT)
            // Short forms only count with a lead word ("on Sat"), so "sun" or "wed" in a sentence aren't dates.
            if (w.length <= 5 && w !in days && m.groupValues[1].isEmpty()) return@forEach
            val idx = days.indexOfFirst { it.startsWith(w.take(3)) }
            if (idx < 0) return@forEach
            var ahead = (idx + 1 - today.dayOfWeek.value + 7) % 7
            if (ahead == 0) ahead = 7 // "Tuesday" said on a Tuesday means next week's
            add(m.range, today.plusDays(ahead.toLong()))
        }

        val times = ArrayList<Pair<IntRange, LocalTime>>()
        time12.findAll(text).forEach { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min > 59) return@forEach
            val pm = m.groupValues[3].lowercase(Locale.ROOT).startsWith("p")
            if (pm && h != 12) h += 12
            if (!pm && h == 12) h = 0
            times += m.range to LocalTime.of(h, min)
        }
        time24.findAll(text).forEach { m ->
            if (times.none { it.first.first <= m.range.last && m.range.first <= it.first.last })
                times += m.range to LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        noon.findAll(text).forEach { m -> times += m.range to if (m.value.lowercase(Locale.ROOT) == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON }

        dates.sortBy { it.first.first }
        return dates.map { (r, d) ->
            // The time written closest to the date (within about a sentence) belongs to it.
            val near = times.map { it to minOf(Math.abs(it.first.first - r.last), Math.abs(r.first - it.first.last)) }
                .filter { it.second <= 40 }.minByOrNull { it.second }?.first
            val span = if (near == null) r else minOf(r.first, near.first.first)..maxOf(r.last, near.first.last)
            Found(d, near?.second) to text.substring(span.first, span.last + 1).replace(Regex("\\s+"), " ")
        }
    }
}

/** Finds explicit dates in text with code (not the model), shown as yyyy-MM-dd. */
object DateFinder {
    private val iso = Regex("""\b(20\d{2})-(\d{1,2})-(\d{1,2})\b""")
    private val dmy = Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](20\d{2})\b""")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dMonY = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?,?\s+(20\d{2})\b""", RegexOption.IGNORE_CASE)
    private val monDY = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(20\d{2})\b""", RegexOption.IGNORE_CASE)

    fun find(text: String, limit: Int = 6): List<String> {
        val out = LinkedHashSet<String>()
        fun add(y: Int, m: Int, d: Int) {
            try { out += LocalDate.of(y, m, d).format(DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) {}
        }
        fun mon(s: String) = months.indexOf(s.lowercase(Locale.ROOT).take(3)) + 1
        iso.findAll(text).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        // Day first, as in India and most of the world; impossible days are dropped.
        dmy.findAll(text).forEach { add(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        dMonY.findAll(text).forEach { add(it.groupValues[3].toInt(), mon(it.groupValues[2]), it.groupValues[1].toInt()) }
        monDY.findAll(text).forEach { add(it.groupValues[3].toInt(), mon(it.groupValues[1]), it.groupValues[2].toInt()) }
        return out.take(limit)
    }
}
