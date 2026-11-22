package com.craft.capturespace.ai

import androidx.compose.ui.graphics.toArgb
import com.craft.capturespace.data.TRASH_DAYS
import com.craft.capturespace.shortcuts.KeyTiming
import com.craft.capturespace.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compares app/mechanics.json with the app: each value in the sheet against the value the code
 * uses, and the rules it describes against what the code does with reference inputs.
 */
class MechanicsSheetTest {

    private val sheet: Map<String, String> by lazy {
        val f = listOf(File("mechanics.json"), File("app/mechanics.json")).first { it.exists() }
        val re = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[-0-9.eE]+)")
        re.findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    }

    private fun num(name: String): Double = sheet[name]?.toDouble() ?: error("$name missing from mechanics.json")

    // ---------- every value in the sheet is the value the code uses ----------

    @Test fun limitsMatchSheet() {
        assertEquals(num("trash_days"), TRASH_DAYS.toDouble(), 0.0)
        assertEquals(num("min_audio_ms"), Limits.MIN_AUDIO_MS.toDouble(), 0.0)
        assertEquals(num("max_audio_bytes"), Limits.MAX_AUDIO_BYTES.toDouble(), 0.0)
        assertEquals(num("image_max_px"), Limits.IMAGE_MAX_PX.toDouble(), 0.0)
        assertEquals(num("related_min_similarity"), Limits.RELATED_MIN.toDouble(), 1e-6)
        assertEquals(num("suggest_add_min_similarity"), Limits.SUGGEST_ADD_MIN.toDouble(), 1e-6)
        assertEquals(num("suggest_create_min_similarity"), Limits.SUGGEST_CREATE_MIN.toDouble(), 1e-6)
        assertEquals(num("suggest_create_min_count"), Limits.SUGGEST_CREATE_COUNT.toDouble(), 0.0)
        assertEquals(num("search_min_similarity"), Limits.SEARCH_MIN.toDouble(), 1e-6)
        assertEquals(num("smart_collection_min_similarity"), Limits.SMART_COLLECTION_MIN.toDouble(), 1e-6)
        assertEquals(num("key_moment_window_ms"), Limits.KEY_MOMENT_WINDOW_MS.toDouble(), 0.0)
    }

    @Test fun keyTimingMatchesSheet() {
        assertEquals(num("double_press_ms"), KeyTiming.DOUBLE_PRESS_MS.toDouble(), 0.0)
        assertEquals(num("hold_ms"), KeyTiming.HOLD_MS.toDouble(), 0.0)
        assertEquals(num("volume_repeat_ms"), KeyTiming.REPEAT_MS.toDouble(), 0.0)
        assertEquals(num("screenshot_delay_ms"), KeyTiming.SCREENSHOT_DELAY_MS.toDouble(), 0.0)
    }

    @Test fun speechCheckSettingsMatchSheet() {
        assertEquals(num("vad_sample_rate_hz"), SpeechCheck.SAMPLE_RATE.toDouble(), 0.0)
        assertEquals(num("vad_frame_ms"), SpeechCheck.FRAME_MS.toDouble(), 0.0)
        assertEquals(num("vad_max_analyze_ms"), SpeechCheck.MAX_ANALYZE_MS.toDouble(), 0.0)
        assertEquals(num("vad_min_speech_dbfs"), SpeechCheck.MIN_SPEECH_DBFS.toDouble(), 1e-6)
        assertEquals(num("vad_min_speech_ms"), SpeechCheck.MIN_SPEECH_MS.toDouble(), 0.0)
        assertEquals(num("vad_zcr_min"), SpeechCheck.ZCR_MIN.toDouble(), 1e-6)
        assertEquals(num("vad_gap_forgiveness_ms"), SpeechCheck.GAP_FORGIVENESS_MS.toDouble(), 0.0)
        assertEquals(num("vad_silence_dbfs"), SpeechCheck.SILENCE_DBFS.toDouble(), 1e-6)
    }

    @Test fun modelsMatchSheet() {
        assertEquals(sheet["analysis_model"], Models.ANALYSIS.name)
        assertEquals(sheet["speech_model"], Models.SPEECH.name)
    }

    @Test fun coloursMatchSheet() {
        fun hex(c: androidx.compose.ui.graphics.Color): String {
            val argb = c.toArgb().toLong() and 0xFFFFFFFFL
            val a = (argb ushr 24).toInt()
            return if (a == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
        }
        assertEquals(sheet["accent_red"], hex(Palette.Red))
        assertEquals(sheet["yellow"], hex(Palette.Yellow))
        assertEquals(sheet["hint_background"], hex(Palette.YellowTint))
        assertEquals(sheet["input_bar"], hex(Palette.InputBar))
        assertEquals(sheet["input_text"], hex(Palette.InputText))
        assertEquals(sheet["link_blue"], hex(Palette.Blue))
    }

    // ---------- the rules in the sheet, run on reference inputs ----------

    private val rate get() = SpeechCheck.SAMPLE_RATE

    /** A 1 kHz tone (zero-crossing rate 0.125) at the given level, for [ms]. */
    private fun tone(ms: Int, dbfs: Double): FloatArray {
        val amp = 10.0.pow(dbfs / 20) * sqrt(2.0) // RMS → peak
        return FloatArray(rate * ms / 1000) { (amp * sin(2 * PI * 1000 * it / rate)).toFloat() }
    }

    private fun silence(ms: Int) = FloatArray(rate * ms / 1000)

    @Test fun vadRule_silenceIsNotSpeech() {
        val r = SpeechCheck.detect(silence(2000))
        assertFalse(r.speech)
        assertEquals(num("vad_silence_dbfs").toFloat(), r.loudestDbfs, 0.01f)
    }

    @Test fun vadRule_loudEnoughForMinSpeechIsSpeech() {
        val min = num("vad_min_speech_ms").toInt()
        val r = SpeechCheck.detect(silence(500) + tone(min + 100, -20.0) + silence(500))
        assertTrue(r.speech)
        assertEquals(500, r.onsetMs)
    }

    @Test fun vadRule_shorterThanMinSpeechIsNot() {
        // The forgiven gap after a sound counts toward the run (the original adds a frame per
        // forgiven frame), so a sound is speech once sound + forgiveness reaches the minimum.
        val min = num("vad_min_speech_ms").toInt()
        val gap = num("vad_gap_forgiveness_ms").toInt()
        val frame = num("vad_frame_ms").toInt()
        assertFalse(SpeechCheck.detect(silence(500) + tone(min - gap - frame, -20.0) + silence(500)).speech)
        assertTrue(SpeechCheck.detect(silence(500) + tone(min - gap, -20.0) + silence(500)).speech)
    }

    @Test fun vadRule_quieterThanThresholdIsNot() {
        val floor = num("vad_min_speech_dbfs")
        assertFalse(SpeechCheck.detect(tone(1000, floor - 5)).speech)
        assertTrue(SpeechCheck.detect(tone(1000, floor + 5)).speech)
    }

    @Test fun vadRule_shortGapsAreForgiven() {
        val gap = num("vad_gap_forgiveness_ms").toInt()
        // 120 ms + a forgivable gap + 120 ms joins into one run of at least 200 ms.
        assertTrue(SpeechCheck.detect(tone(120, -20.0) + silence(gap) + tone(120, -20.0) + silence(300)).speech)
        // The same pieces with a gap longer than the forgiveness don't.
        assertFalse(SpeechCheck.detect(tone(120, -20.0) + silence(gap + 100) + tone(120, -20.0) + silence(300)).speech)
    }

    @Test fun vadRule_onlyFirstMaxAnalyzeMsIsRead() {
        val max = num("vad_max_analyze_ms").toInt()
        assertFalse(SpeechCheck.detect(silence(max) + tone(1000, -20.0)).speech)
    }

    @Test fun keyMomentRule_windowLengthAndMerge() {
        val w = num("key_moment_window_ms").toLong()
        assertEquals(listOf(10_000L to 10_000L + w), keyMomentWindows(listOf(10_000L), 600_000L))
        // A second mark inside the first window extends it.
        assertEquals(listOf(10_000L to 20_000L + w), keyMomentWindows(listOf(10_000L, 20_000L), 600_000L))
        // Clamped to the recording's length.
        assertEquals(listOf(50_000L to 60_000L), keyMomentWindows(listOf(50_000L), 60_000L))
    }

    private operator fun FloatArray.plus(o: FloatArray): FloatArray {
        val r = FloatArray(size + o.size); System.arraycopy(this, 0, r, 0, size); System.arraycopy(o, 0, r, size, o.size); return r
    }
}
