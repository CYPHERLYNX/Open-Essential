package com.craft.capturespace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    private val voice = listOf(TextSearch.Field("Transcript", "This is a test subject. A hen is a chicken, and cows give milk.", 35))
    private val pdf = listOf(
        TextSearch.Field("Title", "Smatable: A Vibration-based Sensing Method for Making Surfaces Interactive", 60),
        TextSearch.Field("Document text", "Received 14 November 2023, accepted 6 December 2023. When the surface is touched, then the vibration " +
            "is sensed. This is the method we propose for interactive tables.", 35),
    )

    @Test fun henIsChickenFindsTheVoiceNoteNotThePdf() {
        val h = TextSearch.match("hen is chicken", voice)
        assertNotNull(h); assertEquals("Transcript", h!!.field)
        assertTrue(h.snippet.substring(h.start, h.end).lowercase().contains("hen"))
        assertNull(TextSearch.match("hen is chicken", pdf))
        assertNull(TextSearch.match("hen is a chicken", pdf))
        assertEquals(3, TextSearch.match("hen is a chicken", voice)!!.score / 100) // exact phrase ranks first
    }

    @Test fun aPhraseFromThePdfFindsThePdf() {
        assertNotNull(TextSearch.match("vibration is sensed", pdf))
        assertNotNull(TextSearch.match("interactive tables", pdf))
    }

    @Test fun nothingWhenTheWordsAreNowhere() {
        assertNull(TextSearch.match("dentist appointment", voice))
        assertNull(TextSearch.match("dentist appointment", pdf))
    }

    @Test fun wordFormsAndTyping() {
        assertNotNull(TextSearch.match("hens", voice))
        assertNotNull(TextSearch.match("chick", voice)) // still typing
        assertNull(TextSearch.match("chick ", voice))  // a finished word must match whole
        assertNotNull(TextSearch.match("Vibrátion", pdf)) // accents and case
    }
}
