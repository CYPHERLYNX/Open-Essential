package com.craft.capturespace.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Word search over a capture's own text, on the phone.
 *
 * The old search matched letters anywhere ("hen" inside "when" and "then") and listed a capture
 * when half the words matched, so a query like "hen is chicken" listed an unrelated PDF. Here a
 * result must contain the query's words as words:
 * 1. the exact phrase (after normalising case, accents and punctuation),
 * 2. else every meaningful word (simple word forms: hen/hens, box/boxes, city/cities; the last
 *    word may be the start of a longer word while typing, "chick" → "chicken"),
 * 3. else, for longer queries (3+ meaningful words), all but one of them, ranked lower.
 * Nothing is listed for being recent, unanalysed or failed: only its stored text counts.
 */
object TextSearch {
    data class Field(val name: String, val text: String, val weight: Int)

    /** [start] and [end] mark the matched words inside [snippet], for highlighting. */
    data class Hit(val score: Int, val field: String, val snippet: String, val start: Int, val end: Int)

    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "be", "of", "to", "in", "on", "at", "and", "or",
        "for", "it", "this", "that", "my", "me", "i", "with", "by", "as", "from", "about", "do", "does", "did")

    /** Lowercase, accents removed, everything that isn't a letter or digit becomes a space. */
    fun normalise(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return d.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** A simple English word form, so "hens" finds "hen" and "cities" finds "city". */
    fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }

    private class Words(text: String) {
        // Each word with its position in the original text, for snippets.
        val words = ArrayList<Triple<String, Int, Int>>()
        init {
            Regex("[\\p{L}\\p{N}]+").findAll(text).forEach { m ->
                words += Triple(normalise(m.value), m.range.first, m.range.last + 1)
            }
        }
    }

    fun match(query: String, fields: List<Field>): Hit? {
        val qWords = normalise(query).split(' ').filter { it.isNotEmpty() }
        if (qWords.isEmpty()) return null
        val content = qWords.filter { it !in stop }.ifEmpty { qWords }
        val lastIsPrefix = query.lastOrNull()?.isLetterOrDigit() == true
        var best: Hit? = null
        for (f in fields) {
            if (f.text.isBlank()) continue
            val h = matchField(qWords, content, lastIsPrefix, f) ?: continue
            if (best == null || h.score > best.score) best = h
        }
        return best
    }

    private fun matchField(qWords: List<String>, content: List<String>, lastIsPrefix: Boolean, f: Field): Hit? {
        val w = Words(f.text).words
        if (w.isEmpty()) return null
        val stems = w.map { stem(it.first) }

        // 1. Exact phrase, word by word.
        if (qWords.size > 1) {
            for (i in 0..w.size - qWords.size) {
                if (qWords.indices.all { j -> w[i + j].first == qWords[j] || stems[i + j] == stem(qWords[j]) }) {
                    return hit(3, f, w[i].second, w[i + qWords.size - 1].third)
                }
            }
        }
        // 2./3. Each meaningful word somewhere in this field.
        fun find(term: String, isLast: Boolean): Int {
            val st = stem(term)
            val i = stems.indexOfFirst { it == st }
            if (i >= 0) return i
            if (isLast && lastIsPrefix && term.length >= 3) return w.indexOfFirst { it.first.startsWith(term) }
            return -1
        }
        val found = content.mapIndexed { k, term -> find(term, k == content.size - 1) }
        val n = found.count { it >= 0 }
        val level = when {
            n == content.size -> 2
            content.size >= 3 && n == content.size - 1 -> 1
            else -> return null
        }
        val first = found.filter { it >= 0 }.minOrNull() ?: return null
        return hit(level, f, w[first].second, w[first].third)
    }

    private fun hit(level: Int, f: Field, s: Int, e: Int): Hit {
        val text = f.text
        val from = (s - 40).coerceAtLeast(0).let { p -> text.lastIndexOf(' ', p).takeIf { it >= 0 && p > 0 }?.plus(1) ?: p }
        val to = (e + 60).coerceAtMost(text.length).let { p -> text.indexOf(' ', p).takeIf { it >= 0 } ?: p }
        val raw = text.substring(from, to)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        val snippet = (prefix + raw + suffix).replace('\n', ' ')
        return Hit(level * 100 + f.weight, f.name, snippet, s - from + prefix.length, e - from + prefix.length)
    }
}
