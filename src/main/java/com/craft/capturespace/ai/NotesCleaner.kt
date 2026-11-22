package com.craft.capturespace.ai

import com.craft.capturespace.data.Detail
import java.util.Locale

/**
 * Removes what the small on-device model writes when it has nothing real to say, before anything
 * is saved or shown. Every rule here comes from output seen on the owner's phone (2026-11-20):
 * - placeholders: "N/A", "Unknown", "None", "User";
 * - runaway text: "1000000000…" (a long run of one character), the same line twice;
 * - filler actions that don't come from the content: "Review the content", "Check for accuracy",
 *   "Read the transcript carefully", "Save screenshot", "Save photo", "Identify the error…";
 * - lines that only restate the prompt: "Date is Friday, 20 November 2026", "Content is in English";
 * - the same label repeated ("Topic: Animals", "Topic: Uses", "Topic: Error") → one "Topics" row.
 * Empty fields stay empty: nothing is filled in.
 */
object NotesCleaner {
    private val placeholder = Regex("""(?i)^(n/?a|none|null|unknown|not (specified|available|mentioned|provided|applicable)|no data|-+|\?+|user|the user|person|the person|someone|tbd|\.+)$""")
    private val runaway = Regex("""(.)\1{7,}""")
    // Only a generic verb + a generic noun and nothing specific: "Check the train ticket details" stays.
    private val filler = Regex(
        """(?i)^(please\s+)?(review|check|read|re-?read|verify|confirm|examine|look at|analy[sz]e|identify|understand|save|store|keep|capture|open|view|see)\s+""" +
            """(the\s+|this\s+|a\s+|an\s+|your\s+|my\s+|all\s+|any\s+)?((full|whole|entire|saved|shared|attached|provided|instagram|original|above)\s+)?""" +
            """(content|post|link|screenshot|screen shot|photo|image|picture|recording|voice recording|notes?|transcript|text|document|statement|error|details|information|message|file|video|page|data)s?""" +
            """(\s+(carefully|again|later|thoroughly|for (accuracy|errors|details|more (info|information))|in the (statement|text|transcript)|in it))?\.?$""" +
            """|^(check|verify)\s+(for\s+)?(accuracy|correctness|errors)\.?$"""
    )
    private val restates = Regex("""(?i)^(the\s+)?(content|text|language|recording|transcript|post|note)\s+(is\s+)?(in|written in)\s+\p{L}+\.?$|^(the\s+)?date\s+is\s+.*\d{4}\.?$|^today is\b""" +
        // Lines about the prompt or the file rather than the content (seen on the phone, 2026-11-20).
        """|not part of the content|\bthe (word|words|date) '?(tomorrow|today|next \w+)'?|^(the\s+)?(document|file|capture)\s+is\s+(named|called|titled)\b""" +
        """|\bis an? (word|pdf|excel|powerpoint|text) (file|document)\b|\bfile ?name\b""" +
        // Seen on the phone with a shared link: the prompt's own rules and the share itself repeated back.
        """|\bthe summary (describes|says|is about)\b|\bsaved from the app\b|\bthe (first )?message is a link\b|\bdescribes the page itself\b""")

    fun text(s: String?): String {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || placeholder.matches(t) || runaway.containsMatchIn(t)) return ""
        return t
    }

    /** One list of short lines (action items, key points, topics). */
    fun lines(items: List<String>, dropFiller: Boolean = true): List<String> = items
        .map { text(it) }
        .filter { it.isNotEmpty() && !restates.containsMatchIn(it) && !(dropFiller && filler.matches(it)) }
        .distinctBy { TextSearch.normalise(it) }

    fun isFiller(action: String): Boolean = text(action).isEmpty() || filler.matches(action.trim())

    // Rows that only repeat what the page already shows above them (seen on the phone after a rename:
    // "Title", "Summary", "Highlights" rows copying the title, the summary and the key points).
    private val shownElsewhere = Regex("""(?i)^(title|name|summary|description|caption|highlights?|key ?points?|tags?|hashtags?|notes?)$""")

    fun details(items: List<Detail>): List<Detail> {
        val clean = items.mapNotNull { d ->
            val l = text(d.label); val v = text(d.value)
            if (l.isEmpty() || v.isEmpty() || restates.containsMatchIn(v) || shownElsewhere.matches(l)) null
            else Detail(l.replaceFirstChar { it.titlecase(Locale.ROOT) }, v)
        }
        // Same label several times with different short values → one row ("Topics: a, b, c").
        val out = ArrayList<Detail>()
        clean.groupBy { it.label.lowercase(Locale.ROOT) }.values.forEach { group ->
            val values = group.map { it.value }.distinctBy { TextSearch.normalise(it) }
                .let { vs -> vs.filter { v -> vs.none { o -> o !== v && o.length > v.length && TextSearch.normalise(o).contains(TextSearch.normalise(v)) } } }
            val label = group[0].label
            // Any label repeated with short values becomes one row (seen: "Topic" x3, "Activity" x3).
            if (values.size > 1 && values.all { it.length <= 40 })
                out += Detail(plural(label), values.joinToString(", "))
            else values.forEach { out += Detail(label, it) }
        }
        return out
    }

    private fun plural(l: String) = when (l.lowercase(Locale.ROOT)) {
        "person" -> "People"
        "people" -> "People"
        "category" -> "Categories"
        "activity" -> "Activities"
        "date", "time", "place", "price", "amount" -> l + "s"
        else -> if (l.endsWith("s")) l else l + "s"
    }
}
