package com.craft.capturespace.ai

import com.craft.capturespace.data.Detail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesCleanerTest {
    @Test fun dropsFillerSeenOnThePhone() {
        listOf("Review the content", "Check for accuracy", "Read the transcript carefully", "Save screenshot", "Save photo",
            "Save voice recording", "Save notes", "Identify the error in the statement").forEach { assertTrue(it, NotesCleaner.isFiller(it)) }
    }

    @Test fun keepsRealActions() {
        listOf("Check the train ticket details", "Bring the Q4 budget sheet", "Register by 25 Nov 2026", "Call the bank about the credit card statement",
            "Read chapter 4 before Monday").forEach { assertFalse(it, NotesCleaner.isFiller(it)) }
    }

    @Test fun dropsGarbageAndRestatements() {
        val out = NotesCleaner.lines(listOf("github.com", "foodsnap.git", "1" + "0".repeat(120), "8:20 pm", "8:20 pm",
            "Date is Friday, 20 November 2026", "Content is in English", "N/A"))
        assertEquals(listOf("github.com", "foodsnap.git", "8:20 pm"), out)
    }

    @Test fun mergesRepeatedTopicsAndDropsPlaceholders() {
        val out = NotesCleaner.details(listOf(Detail("Topic", "Animals"), Detail("Topic", "Uses"), Detail("Topic", "Error"),
            Detail("Location", "N/A"), Detail("User", "User"), Detail("Language", "English")))
        assertEquals(listOf(Detail("Topics", "Animals, Uses, Error"), Detail("Language", "English")), out)
    }

    @Test fun dropsPromptLeaksAndMergesAnyRepeatedLabel() {
        val out = NotesCleaner.lines(listOf("The document is named 'oe-test-trip.docx' and is a Word file.",
            "The date 'tomorrow' is not part of the content, only 'Friday' is used.", "The event is on Friday, 4 December 2026."))
        assertEquals(listOf("The event is on Friday, 4 December 2026."), out)
        val d = NotesCleaner.details(listOf(Detail("Activity", "solar panels"), Detail("Activity", "grid connection"), Detail("Activity", "maintenance")))
        assertEquals(listOf(Detail("Activities", "solar panels, grid connection, maintenance")), d)
    }

    @Test fun dropsPromptRulesRepeatedBack() {
        val out = NotesCleaner.lines(listOf("The summary describes the page itself, not the sharing.", "The content is saved from the app 'Shell'.",
            "The first message is a link to chatgpt.com/share/1.", "Benny the Bear meets Rio the Parrot"))
        assertEquals(listOf("Benny the Bear meets Rio the Parrot"), out)
    }

    @Test fun dropsRowsThatRepeatTheTitleSummaryOrKeyPoints() {
        val out = NotesCleaner.details(listOf(Detail("Title", "Evening walk"), Detail("Summary", "A walk."),
            Detail("Highlights", "Sign, Bicycle"), Detail("Place", "Marine Drive")))
        assertEquals(listOf(Detail("Place", "Marine Drive")), out)
    }
}
