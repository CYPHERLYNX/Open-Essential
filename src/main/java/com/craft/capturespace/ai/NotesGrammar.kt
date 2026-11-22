package com.craft.capturespace.ai

/**
 * A GBNF grammar (llama.cpp's constrained-output format) for the notes JSON.
 *
 * Measured on a Nothing Phone (2), 2026-11-20: without it the 0.8B model sometimes kept writing
 * until the 768-token limit (about 22 s), the JSON was cut off, and the request was repeated.
 * With the grammar the reply is always one complete JSON object with the expected keys, every
 * list and text has an upper bound, so it can't loop, and the end of the object ends the reply.
 *
 * Bounds are in characters. The worst case still fits in [MAX_TOKENS]; typical replies are far shorter.
 */
object NotesGrammar {
    const val MAX_TOKENS = 1024

    /** [voice] adds "topics" and "key_moments" (the voice prompt asks for them). */
    fun build(voice: Boolean): String {
        val fields = buildList {
            add(field("title", "str-title"))
            add(field("summary", "str-summary"))
            add(field("highlights", list("str-item", 5)))
            add(field("tags", list("str-tag", 5)))
            add(field("details", list("detail", 6)))
            add(field("action_items", list("str-item", 5)))
            add(field("tasks", list("task", 5)))
            add(field("events", list("event", 3)))
            add(field("places", list("str-place", 5)))
            if (voice) {
                add(field("topics", list("str-tag", 5)))
                add(field("key_moments", list("str-moment", 6)))
            }
            add(field("language", "str-lang"))
        }
        return buildString {
            append("root ::= \"{\" ws ")
            append(fields.joinToString(" \",\" ws "))
            append(" ws \"}\"\n")
            append("detail ::= \"{\" ws ").append(field("label", "str-label")).append(" \",\" ws ")
                .append(field("value", "str-item")).append(" ws \"}\"\n")
            append("task ::= \"{\" ws ").append(field("title", "str-item")).append(" \",\" ws ")
                .append(field("due", "str-date")).append(" ws \"}\"\n")
            append("event ::= \"{\" ws ").append(field("title", "str-item")).append(" \",\" ws ")
                .append(field("start", "str-date")).append(" \",\" ws ")
                .append(field("end", "str-date")).append(" \",\" ws ")
                .append(field("all_day", "bool")).append(" \",\" ws ")
                .append(field("location", "str-place")).append(" ws \"}\"\n")
            append(str("str-title", 80))
            append(str("str-summary", 450))
            append(str("str-item", 140))
            append(str("str-moment", 180))
            append(str("str-tag", 24))
            append(str("str-label", 40))
            append(str("str-place", 60))
            append(str("str-lang", 24))
            append("str-date ::= \"\\\"\" [0-9T:-]{0,16} \"\\\"\"\n")
            append("bool ::= \"true\" | \"false\"\n")
            append("char ::= [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4})\n")
            // Bounded whitespace, so the model can't stall by writing spaces or new lines forever.
            // Wide enough for its usual indented style: with only 2 characters allowed (measured on
            // the phone) its indent was always rejected and it closed every list empty instead.
            append("ws ::= [ \\t\\n]{0,24}\n")
        }
    }

    private fun field(key: String, rule: String) = "\"\\\"$key\\\":\" ws $rule"

    private fun list(item: String, max: Int) =
        "\"[\" ws ( $item ( \",\" ws $item ){0,${max - 1}} )? ws \"]\""

    private fun str(name: String, max: Int) = "$name ::= \"\\\"\" char{0,$max} \"\\\"\"\n"
}
