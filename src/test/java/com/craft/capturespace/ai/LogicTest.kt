package com.craft.capturespace.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LogicTest {
    @Test fun keyMomentsMergeOverlapsAndClampToDuration() {
        // 10 s and 25 s overlap (10..40); 90 s starts a new range clamped to the 100 s length.
        assertEquals(listOf(10_000L to 55_000L, 90_000L to 100_000L),
            keyMomentWindows(listOf(25_000, 90_000, 10_000), 100_000))
        assertEquals(emptyList<Pair<Long, Long>>(), keyMomentWindows(emptyList(), 100_000))
        // Unknown duration: full 30 s window.
        assertEquals(listOf(5_000L to 35_000L), keyMomentWindows(listOf(5_000), 0))
    }

    @Test fun parseWhenReadsDatesTimesAndRejectsJunk() {
        val z = ZoneOffset.UTC
        assertEquals(1_795_132_800_000L to true, Pipeline.parseWhen("2026-11-20", z))
        assertEquals(1_795_185_000_000L to false, Pipeline.parseWhen("2026-11-20T14:30", z))
        assertEquals(1_795_185_000_000L to false, Pipeline.parseWhen("2026-11-20T14:30:59Z", z))
        assertNull(Pipeline.parseWhen("", z))
        assertNull(Pipeline.parseWhen(null, z))
        assertNull(Pipeline.parseWhen("next friday", z))
    }

    @Test fun questionsAreRecognised() {
        assertTrue(Pipeline.isQuestion("when is the concert"))
        assertTrue(Pipeline.isQuestion("flight number?"))
        assertFalse(Pipeline.isQuestion("concert tickets"))
        assertFalse(Pipeline.isQuestion("whatever"))
    }

    @Test fun similarityMaths() {
        val a = Similarity.normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, Similarity.cosine(a, a), 1e-6f)
        assertEquals(0f, Similarity.cosine(a, floatArrayOf(1f)), 0f)
        val c = Similarity.centroid(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))!!
        assertEquals(0.7071f, c[0], 1e-3f)
        assertNull(Similarity.centroid(emptyList()))
    }
}
