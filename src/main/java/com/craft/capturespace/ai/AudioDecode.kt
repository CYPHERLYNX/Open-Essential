package com.craft.capturespace.ai

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/** Decodes any audio file Android can read into 16 kHz mono float PCM (what whisper.cpp takes). */
object AudioDecode {
    const val RATE = 16_000
    /** Hard cap so a long file can't exhaust memory: 30 minutes of 16 kHz floats is about 115 MB. */
    const val MAX_SECONDS = 30 * 60

    fun to16kMono(path: String): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); throw AiException("No audio track found in this file.") }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFmt, null, null, 0)
        codec.start()

        var srcRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val mono = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxSrcSamples = MAX_SECONDS.toLong() * 192_000
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; mono.add(s / channels) }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; mono.add(s / channels) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size > maxSrcSamples) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return resample(mono.toArray(), srcRate, RATE).let { if (it.size > MAX_SECONDS * RATE) it.copyOf(MAX_SECONDS * RATE) else it }
    }

    /** Linear-interpolation resampler with a simple box low-pass when shrinking. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val src = if (from > to) {
            val k = (from / to).coerceAtLeast(1)
            if (k == 1) x else FloatArray(x.size) { i ->
                var s = 0f; var n = 0
                for (j in maxOf(0, i - k / 2) until minOf(x.size, i + (k + 1) / 2)) { s += x[j]; n++ }
                s / n
            }
        } else x
        val outLen = (src.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(outLen) { i ->
            val p = i * ratio
            val a = p.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = (p - a).toFloat()
            src[a] * (1 - t) + src[b] * t
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
