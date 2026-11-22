package com.craft.capturespace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test
    fun placesFromVenueAndAddress() {
        assertEquals(listOf("Green Street clinic"), PlaceFinder.find("Dentist appointment Friday 3pm at Green Street clinic"))
        assertEquals(listOf("221 Baker Street"), PlaceFinder.find("Parcel goes to 221 Baker Street, London"))
        assertEquals(listOf("12 MG Road"), PlaceFinder.find("Meet at 12 MG Road tomorrow"))
        assertEquals(listOf("Central Station"), PlaceFinder.find("Leave for the meeting near Central Station"))
    }

    @Test
    fun noPlaceInPlainText() {
        assertTrue(PlaceFinder.find("call mum about the plans in the evening").isEmpty())
        assertTrue(PlaceFinder.find("Buy 2 apples and milk").isEmpty())
    }

    @Test
    fun datesFound() {
        assertEquals(listOf("2026-11-24"), DateFinder.find("Due 24 Nov 2026"))
        assertEquals(listOf("2026-03-05"), DateFinder.find("on 05/03/2026"))
        assertEquals(listOf("2026-12-01"), DateFinder.find("December 1st, 2026"))
    }
}
