package com.craft.capturespace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesGrammarTest {
    private fun rules(g: String) = g.lines().filter { it.isNotBlank() }.map { it.substringBefore(" ::= ") }

    @Test fun everyRuleIsDefinedOnce() {
        for (voice in listOf(false, true)) {
            val g = NotesGrammar.build(voice)
            val defined = rules(g)
            assertEquals(defined.size, defined.toSet().size)
            // Every rule name used on a right-hand side (outside quotes and character classes) is defined.
            val used = g.lines().filter { it.contains(" ::= ") }.flatMap { line ->
                line.substringAfter(" ::= ")
                    .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), " ")
                    .replace(Regex("\\[(\\\\.|[^\\]\\\\])*\\]"), " ")
                    .split(Regex("[\\s()|?*+]+|\\{[0-9,]*\\}"))
                    .filter { it.matches(Regex("[a-z][a-z-]*")) }
            }.toSet()
            assertTrue("undefined: ${used - defined.toSet()}", defined.toSet().containsAll(used))
        }
    }

    @Test fun keysMatchThePrompt() {
        val text = NotesGrammar.build(false)
        listOf("title", "summary", "highlights", "tags", "details", "action_items", "tasks", "events", "places", "language")
            .forEach { assertTrue(it, text.contains("\\\"$it\\\":")) }
        assertFalse(text.contains("key_moments"))
        assertTrue(NotesGrammar.build(true).contains("key_moments"))
    }
}
