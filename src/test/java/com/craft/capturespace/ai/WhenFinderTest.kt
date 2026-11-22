package com.craft.capturespace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WhenFinderTest {
    // Friday 20 November 2026, 19:23 (when the phone test note was written).
    private val now = LocalDateTime.of(2026, 11, 20, 19, 23)

    @Test fun nextWeekdayWithTime() {
        val f = WhenFinder.find("Team lunch with Priya next Tuesday at 1pm at Cafe Mocha Kochi.", now)
        assertEquals(listOf(WhenFinder.Found(LocalDate.of(2026, 11, 24), LocalTime.of(13, 0))), f)
        assertEquals("2026-11-24T13:00", f[0].toIso())
    }

    @Test fun hintsForThePrompt() {
        assertEquals("\"next Tuesday at 1pm\" = Tuesday, 24 November 2026, 13:00",
            WhenFinder.hints("Team lunch with Priya next Tuesday at 1pm at Cafe Mocha Kochi.", now))
        assertEquals("", WhenFinder.hints("Bring the Q4 budget sheet.", now))
    }

    @Test fun relativeWords() {
        assertEquals(LocalDate.of(2026, 11, 21), WhenFinder.find("call mum tomorrow", now)[0].date)
        assertEquals(LocalDate.of(2026, 11, 22), WhenFinder.find("the day after tomorrow", now)[0].date)
        val t = WhenFinder.find("Dinner tonight 8.30 pm", now)[0]
        assertEquals(LocalDate.of(2026, 11, 20), t.date); assertEquals(LocalTime.of(20, 30), t.time)
    }

    @Test fun sameWeekdayMeansNextWeek() {
        assertEquals(LocalDate.of(2026, 11, 27), WhenFinder.find("on Friday", now)[0].date)
    }

    @Test fun datesWithAndWithoutYear() {
        assertEquals(LocalDate.of(2026, 11, 28), WhenFinder.find("Workshop 28 Nov, 10:00 at Town Hall", now)[0].date)
        assertEquals(LocalTime.of(10, 0), WhenFinder.find("Workshop 28 Nov, 10:00 at Town Hall", now)[0].time)
        assertEquals(LocalDate.of(2027, 1, 5), WhenFinder.find("Jan 5th exam", now)[0].date)
        assertEquals(LocalDate.of(2026, 3, 2), WhenFinder.find("paid on 2026-03-02", now)[0].date)
    }

    @Test fun noDateMeansNothing() {
        assertTrue(WhenFinder.find("Bring the Q4 budget sheet. Meet at 1pm.", now).isEmpty())
        assertTrue(WhenFinder.find("I may go to the sun deck", now).isEmpty())
    }
}
