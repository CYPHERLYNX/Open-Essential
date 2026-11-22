package com.craft.capturespace

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.ai.SpeechCheck
import com.craft.capturespace.ai.keyMomentWindows
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs inside the installed app on Android and measures what it does with reference inputs:
 * sound files written to disk and read back through Android's own decoder by SpeechCheck.check
 * (the same path voice captures take), pictures through the AI image preparation, and key-moment
 * marks. Every value is found by sweeping the input and observing the result, not by reading the
 * constant. Results are reported as instrumentation status lines (measured.<name>=<value>).
 *
 * Run: adb shell am instrument -w -r com.craft.capturespace.test/com.craft.capturespace.MeasureRules
 */
class MeasureRules : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private val out = Bundle()
    private val dir by lazy { File(targetContext.cacheDir, "measure").apply { mkdirs() } }
    private val rate = 16_000

    override fun onStart() {
        try {
            measureSpeech()
            measureImage()
            measureKeyMoments()
            out.putString("status", "ok")
        } catch (e: Throwable) {
            out.putString("status", "error: ${e.javaClass.simpleName}: ${e.message}")
        }
        dir.deleteRecursively()
        finish(Activity.RESULT_OK, out)
    }

    private fun put(name: String, value: Any) {
        out.putString("measured.$name", value.toString())
        sendStatus(0, Bundle().apply { putString("measured.$name", value.toString()) })
    }

    // ---------- voice: decoded from real files by Android ----------

    private fun tone(ms: Int, dbfs: Double): FloatArray {
        val amp = 10.0.pow(dbfs / 20) * sqrt(2.0)
        return FloatArray(rate * ms / 1000) { (amp * sin(2 * PI * 1000 * it / rate)).toFloat() }
    }

    private fun silence(ms: Int) = FloatArray(rate * ms / 1000)

    private operator fun FloatArray.plus(o: FloatArray): FloatArray {
        val r = FloatArray(size + o.size); System.arraycopy(this, 0, r, 0, size); System.arraycopy(o, 0, r, size, o.size); return r
    }

    /** Writes 16-bit mono WAV and runs the app's own file check on it. */
    private fun check(pcm: FloatArray): SpeechCheck.Result {
        val f = File(dir, "probe.wav")
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.capacity()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2)
        h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(data.capacity())
        FileOutputStream(f).use { it.write(h.array()); it.write(data.array()) }
        return SpeechCheck.check(f.path) ?: error("Android couldn't decode the probe file")
    }

    private fun measureSpeech() {
        // Silence: is it speech, and what level does it read as?
        val quiet = check(silence(2000))
        put("silence_is_speech", quiet.speech)
        put("vad_silence_dbfs", quiet.loudestDbfs.roundToInt())

        // Quietest 1 s tone that counts as speech (1 dB steps).
        var level: Int? = null
        for (db in -70..-30) if (check(tone(1000, db.toDouble())).speech) { level = db; break }
        put("vad_min_speech_dbfs", level ?: "none")

        // Shortest single sound that counts (20 ms steps). The run includes the forgiven gap after it.
        var shortest: Int? = null
        for (ms in 20..400 step 20) if (check(silence(500) + tone(ms, -20.0) + silence(500)).speech) { shortest = ms; break }
        put("vad_shortest_sound_ms", shortest ?: "none")

        // Longest gap between two 120 ms sounds that still joins them into one run.
        var joined: Int? = null
        for (gap in 0..200 step 20) if (check(tone(120, -20.0) + silence(gap) + tone(120, -20.0) + silence(300)).speech) joined = gap
        put("vad_gap_forgiveness_ms", joined ?: "none")
        // Minimum speech = shortest sound + the gap forgiven after it.
        if (shortest != null && joined != null) put("vad_min_speech_ms", shortest + joined)

        // Where a sound starting at 500 ms is reported to start.
        put("vad_onset_ms_for_500", check(silence(500) + tone(400, -20.0) + silence(300)).onsetMs)

        // Latest start (1 s steps) of a 1 s sound that is still heard: how far into a file it reads.
        var latest = -1
        for (s in 0..14) if (check(silence(s * 1000) + tone(1000, -20.0)).speech) latest = s * 1000
        put("vad_latest_heard_start_ms", latest)
    }

    // ---------- pictures sent to the AI ----------

    private fun measureImage() {
        fun sideAfter(w: Int, h: Int): Int {
            val f = File(dir, "probe.png")
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF336699.toInt())
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val jpg = Base64.decode(Pipeline.jpegBase64(f.path), Base64.NO_WRAP)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
            return maxOf(o.outWidth, o.outHeight)
        }
        put("image_max_px", sideAfter(4000, 3000))
        put("image_small_kept_px", sideAfter(1000, 800))
    }

    // ---------- key moments in a recording ----------

    private fun measureKeyMoments() {
        val one = keyMomentWindows(listOf(10_000L), 600_000L).first()
        put("key_moment_window_ms", one.second - one.first)
        put("key_moment_merge", keyMomentWindows(listOf(10_000L, 20_000L), 600_000L).joinToString { "${it.first}-${it.second}" })
        put("key_moment_clamp", keyMomentWindows(listOf(50_000L), 60_000L).joinToString { "${it.first}-${it.second}" })
    }
}
