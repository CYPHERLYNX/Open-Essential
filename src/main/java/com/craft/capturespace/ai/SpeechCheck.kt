package com.craft.capturespace.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Checks a voice recording for speech before it is sent for analysis, with the same settings and
 * rule the original's on-device voice check uses (see MECHANICS.md, "Speech check"). Written fresh
 * from the recovered values; no original code is reused.
 */
object SpeechCheck {
    // Defaults recovered from the original (AdaptiveVad$Options constructor).
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val MAX_ANALYZE_MS = 10_000
    const val MIN_SPEECH_DBFS = -50f
    const val MIN_SPEECH_MS = 200
    const val ZCR_MIN = 0.03f
    const val GAP_FORGIVENESS_MS = 60
    const val SILENCE_DBFS = -120f

    data class Result(val speech: Boolean, val onsetMs: Int, val longestRunMs: Int, val loudestDbfs: Float)

    /** Decodes the start of [path] to 16 kHz mono and runs [detect]. Null when the file can't be decoded. */
    fun check(path: String): Result? = try {
        decode(path)?.let { detect(it) }
    } catch (_: Exception) {
        null
    }

    fun detect(pcm: FloatArray): Result {
        val frame = SAMPLE_RATE * FRAME_MS / 1000
        if (pcm.isEmpty() || frame <= 0) return Result(false, -1, 0, SILENCE_DBFS)
        val frames = min(pcm.size / frame, SAMPLE_RATE * MAX_ANALYZE_MS / 1000 / frame)
        var run = 0
        var gap = 0
        var longest = 0
        var loudest = SILENCE_DBFS
        var onset = -1
        for (i in 0 until frames) {
            val from = i * frame
            val db = dbfs(pcm, from, frame)
            val zc = zcr(pcm, from, frame)
            loudest = max(loudest, db)
            if (db >= MIN_SPEECH_DBFS && zc >= ZCR_MIN) {
                run += FRAME_MS
                gap = GAP_FORGIVENESS_MS
            } else if (run > 0 && gap >= FRAME_MS) {
                gap -= FRAME_MS
                run += FRAME_MS
            } else {
                run = 0
                gap = 0
            }
            longest = max(longest, run)
            if (onset < 0 && run >= MIN_SPEECH_MS) onset = max(0, (i + 1) * FRAME_MS - run)
        }
        return Result(onset >= 0, onset, longest, loudest)
    }

    private fun clamp(x: Float) = if (x > 1f) 1f else if (x < -1f) -1f else x

    private fun dbfs(a: FloatArray, from: Int, n: Int): Float {
        var sum = 0.0
        for (k in from until from + n) { val x = clamp(a[k]); sum += x * x }
        val rms = min(1.0, sqrt(sum / n))
        return if (rms > 1e-12) (20 * log10(rms)).toFloat() else SILENCE_DBFS
    }

    private fun zcr(a: FloatArray, from: Int, n: Int): Float {
        var changes = 0
        for (k in from + 1 until from + n) {
            val p = clamp(a[k - 1]); val c = clamp(a[k])
            if ((p >= 0f && c < 0f) || (p < 0f && c >= 0f)) changes++
        }
        return changes.toFloat() / n
    }

    /** First [MAX_ANALYZE_MS] of the audio as mono floats at [SAMPLE_RATE] (linear resampling). */
    private fun decode(path: String): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mono = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val need = { rate.toLong() * (MAX_ANALYZE_MS + 200) / 1000 }
            try {
                while (!outputDone && mono.size < need()) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val shorts = out.asShortBuffer()
                        val ch = max(1, channels)
                        while (shorts.remaining() >= ch) {
                            var s = 0f
                            repeat(ch) { s += shorts.get() / 32768f }
                            mono.add(s / ch)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            if (mono.isEmpty() || rate <= 0) return null
            if (rate == SAMPLE_RATE) return mono.toFloatArray()
            val outLen = (mono.size.toLong() * SAMPLE_RATE / rate).toInt()
            val step = rate.toDouble() / SAMPLE_RATE
            return FloatArray(outLen) { j ->
                val pos = j * step
                val a = pos.toInt().coerceAtMost(mono.size - 1)
                val b = min(a + 1, mono.size - 1)
                val t = (pos - a).toFloat()
                mono[a] * (1 - t) + mono[b] * t
            }
        } finally {
            ex.release()
        }
    }
}
