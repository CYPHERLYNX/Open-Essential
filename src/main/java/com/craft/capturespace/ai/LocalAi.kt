package com.craft.capturespace.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.craft.capturespace.App
import java.io.File

/** A failure the person can read; [offline] means "try again later". */
class AiException(val userMessage: String, val offline: Boolean = false, cause: Throwable? = null, val code: Int = 0) :
    Exception(userMessage, cause)

/** Vector maths for meaning-based matching, done on this phone. */
object Similarity {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }

    fun centroid(vecs: List<FloatArray>): FloatArray? {
        if (vecs.isEmpty()) return null
        val n = vecs[0].size
        val out = FloatArray(n)
        vecs.filter { it.size == n }.forEach { v -> for (i in 0 until n) out[i] += v[i] }
        return normalize(out)
    }

    fun normalize(v: FloatArray): FloatArray {
        val len = Math.sqrt(v.sumOf { (it * it).toDouble() })
        return if (len == 0.0) v else FloatArray(v.size) { (v[it] / len).toFloat() }
    }
}

/** Sampling settings for one request (see ENGINES.md for where each comes from). */
data class Sampling(val temp: Float, val topP: Float, val topK: Int, val presencePenalty: Float) {
    companion object {
        /** Qwen3.5 model card, non-thinking mode, images. */
        val IMAGE_CARD = Sampling(0.7f, 0.8f, 20, 1.5f)
        /** Qwen3.5 model card, non-thinking mode, text. */
        val TEXT_CARD = Sampling(1.0f, 1.0f, 20, 2.0f)
        /**
         * Structured notes (JSON, held to [NotesGrammar]). Presence penalty is off: it punishes the
         * braces, quotes and key names JSON must repeat. At 0.6, measured on a Nothing Phone (2), the
         * 0.8B model misspelt words it was copying ("Bread the Q4 budget sheet" for "Bring"); at 0.3
         * it still invented a second time range on a poster. 0.1 is near-greedy; the grammar's bounds
         * stop the looping that greedy decoding can otherwise cause.
         */
        val NOTES = Sampling(0.1f, 0.8f, 20, 0.0f)
    }
}

/**
 * The bounded picture policy. A picture is scaled so its longer side is at most [DEFAULT_SIDE]
 * (or [DETAIL_SIDE] for screenshots, which usually carry small text), never above [HARD_CAP_PIXELS].
 * With Qwen3.5's 16 px patches merged 2x2, 768 px is about 576 picture tokens and 1280 px about 1,600.
 */
object ImagePolicy {
    const val DEFAULT_SIDE = 768
    const val DETAIL_SIDE = 1280
    const val HARD_CAP_PIXELS = 1280 * 1280
}

/**
 * The app's only AI: Qwen3.5-0.8B (with its vision projector) through llama.cpp, and Whisper small
 * through whisper.cpp, both running on this phone's CPU. Nothing is sent to an online AI service.
 * One request runs at a time; the speech and text engines are never loaded together.
 */
object LocalAi {
    private const val TAG = "OE-ai"
    const val N_CTX = 4096
    const val MARKER = "<__media__>" // mtmd_default_marker()

    private val lock = Any()
    @Volatile private var llama = 0L
    @Volatile private var whisper = 0L
    @Volatile private var busyHandle = 0L to ""

    /** Null when the engine can run here; otherwise the reason it can't. */
    val engineProblem: String? get() = NativeLlama.unavailable ?: NativeWhisper.unavailable

    /** Engine present on this phone and all three model files verified. */
    val ready: Boolean get() = engineProblem == null && Models.allReady()

    /** The built-in check (a real text and picture run) has passed on this phone, for this app version. */
    val proven: Boolean get() = App.instance.settings.prefs.getBoolean(provenKey(), false)
    private fun provenKey() = "engine_selftest_ok_" + App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).longVersionCodeCompat()

    val statusLine: String
        get() = when {
            engineProblem != null -> engineProblem!!
            !Models.allReady() -> "Import or download the three model files to analyse captures on this phone."
            !proven -> "Engine included. It hasn't been checked on this phone yet: run \"Check the engine\" in Settings › On-device AI."
            else -> "Captures are analysed on this phone."
        }

    fun busy(): Boolean = busyHandle.first != 0L

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private fun ensureLlama(ctx: Context): Long {
        if (llama != 0L) return llama
        engineProblem?.let { throw AiException(it) }
        if (!Models.allReady()) throw AiException("The model files aren't installed yet.")
        if (whisper != 0L) { NativeWhisper.nativeFree(whisper); whisper = 0L }
        val t0 = SystemClock.elapsedRealtime()
        val h = try {
            NativeLlama.nativeLoad(Models.file(ctx, Models.ANALYSIS).path, Models.file(ctx, Models.VISION).path, N_CTX, threads())
        } catch (e: OutOfMemoryError) { 0L }
        if (h == 0L) throw AiException(NativeLlama.nativeLastError().ifBlank { "The model couldn't be loaded." })
        Log.i(TAG, "text+vision engine loaded in ${SystemClock.elapsedRealtime() - t0} ms; ${memLine()}")
        llama = h
        return h
    }

    private fun ensureWhisper(ctx: Context): Long {
        if (whisper != 0L) return whisper
        engineProblem?.let { throw AiException(it) }
        if (Models.state[Models.SPEECH.name] != ModelState.Ready) throw AiException("The speech model isn't installed yet.")
        if (llama != 0L) { NativeLlama.nativeFree(llama); llama = 0L }
        val h = try { NativeWhisper.nativeLoad(Models.file(ctx, Models.SPEECH).path) } catch (e: OutOfMemoryError) { 0L }
        if (h == 0L) throw AiException(NativeWhisper.nativeLastError().ifBlank { "The speech model couldn't be loaded." })
        Log.i(TAG, "speech engine loaded; ${memLine()}")
        whisper = h
        return whisper
    }

    /** Builds a Qwen3.5 chat prompt with thinking off, exactly as its chat template renders it. */
    fun chatPrompt(system: String, user: String, withImage: Boolean): String = buildString {
        append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
        append("<|im_start|>user\n")
        if (withImage) append(MARKER)
        append(user.trim()).append("<|im_end|>\n")
        append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    private fun generate(ctx: Context, prompt: String, rgb: ByteArray?, w: Int, h: Int, maxTokens: Int, s: Sampling, grammar: String? = null): String =
        synchronized(lock) {
            val handle = ensureLlama(ctx)
            busyHandle = handle to "llama"
            try {
                val t0 = SystemClock.elapsedRealtime()
                val out = NativeLlama.nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), rgb, w, h, maxTokens, s.temp, s.topP, s.topK, s.presencePenalty,
                    grammar?.toByteArray(Charsets.UTF_8))
                Log.i(TAG, "${if (rgb != null) "image" else "text"} task took ${SystemClock.elapsedRealtime() - t0} ms; ${memLine()}")
                String(out, Charsets.UTF_8).replace("<think>", "").replace("</think>", "").trim()
            } catch (e: RuntimeException) {
                throw AiException(e.message ?: "The model stopped with an error.")
            } catch (e: OutOfMemoryError) {
                close(); throw AiException("Not enough memory on this phone for this input.")
            } finally { busyHandle = 0L to "" }
        }

    /** Reads a picture (RGB bytes, [w] x [h]) with the prompt. */
    fun analyseImage(ctx: Context, system: String, prompt: String, rgb: ByteArray, w: Int, h: Int, maxTokens: Int = 768, s: Sampling = Sampling.NOTES, grammar: String? = null): String =
        generate(ctx, chatPrompt(system, prompt, true), rgb, w, h, maxTokens, s, grammar)

    /** Text in, text out. */
    fun summariseText(ctx: Context, system: String, prompt: String, maxTokens: Int = 768, s: Sampling = Sampling.NOTES, grammar: String? = null): String =
        generate(ctx, chatPrompt(system, prompt, false), null, 0, 0, maxTokens, s, grammar)

    data class Transcript(val language: String, val text: String)

    /** Speech to text; [language] is a whisper code ("en", "hi", "ml"...) or "auto". */
    fun transcribeAudio(ctx: Context, path: String, language: String = "auto"): Transcript {
        val pcm = try { AudioDecode.to16kMono(path) } catch (e: AiException) { throw e } catch (e: Exception) {
            throw AiException("This recording couldn't be decoded (${e.javaClass.simpleName}).")
        }
        if (pcm.isEmpty()) throw AiException("The recording is empty.")
        return synchronized(lock) {
            val handle = ensureWhisper(ctx)
            busyHandle = handle to "whisper"
            try {
                val t0 = SystemClock.elapsedRealtime()
                val raw = String(NativeWhisper.nativeTranscribe(handle, pcm, language, threads()), Charsets.UTF_8)
                Log.i(TAG, "speech task: ${pcm.size / AudioDecode.RATE} s audio in ${SystemClock.elapsedRealtime() - t0} ms; ${memLine()}")
                Transcript(raw.substringBefore('\n'), raw.substringAfter('\n', "").trim())
            } catch (e: RuntimeException) {
                throw AiException(e.message ?: "Speech recognition failed.")
            } finally {
                busyHandle = 0L to ""
                NativeWhisper.nativeFree(handle); whisper = 0L // free its memory before the notes step
            }
        }
    }

    fun cancel() {
        val (h, kind) = busyHandle
        if (h == 0L) return
        if (kind == "llama") NativeLlama.nativeCancel(h) else NativeWhisper.nativeCancel(h)
    }

    /** Frees both engines (also used when the phone is low on memory). */
    fun close() {
        cancel()
        synchronized(lock) {
            if (llama != 0L) { NativeLlama.nativeFree(llama); llama = 0L }
            if (whisper != 0L) { NativeWhisper.nativeFree(whisper); whisper = 0L }
        }
    }

    /** Peak and current memory of this process, from /proc/self/status. */
    fun memLine(): String = try {
        val m = File("/proc/self/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        "peak RAM ${m["VmHWM"]}, now ${m["VmRSS"]}"
    } catch (_: Exception) { "memory unknown" }

    /**
     * A real check on this phone: a short text task and a picture with printed words, run through
     * the engine. Nothing is canned: it passes only if the model's own answers mention what was asked.
     */
    fun selfTest(ctx: Context): String {
        val t0 = SystemClock.elapsedRealtime()
        val text = summariseText(ctx, "You answer briefly.", "Reply with just the number: what is 12 plus 30?", 16, Sampling(0.2f, 0.8f, 20, 0f))
        val t1 = SystemClock.elapsedRealtime()
        val bmp = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText("PICK UP KEYS 4 PM", 40f, 180f, Paint().apply { color = Color.BLACK; textSize = 56f; isAntiAlias = true })
        }
        val (rgb, w, h) = Pipeline.rgb(bmp)
        val seen = analyseImage(ctx, "You read pictures.", "What text is written in this picture? Reply with the text only.", rgb, w, h, 32, Sampling(0.2f, 0.8f, 20, 0f))
        val t2 = SystemClock.elapsedRealtime()
        val okText = text.contains("42")
        val okImage = seen.uppercase().contains("KEYS")
        val report = "Text: \"${text.take(40)}\" (${t1 - t0} ms) ${if (okText) "OK" else "unexpected"}. " +
            "Picture: \"${seen.take(60)}\" (${t2 - t1} ms) ${if (okImage) "OK" else "unexpected"}. ${memLine()}."
        Log.i(TAG, "self-test: text=${okText} image=${okImage} ${t1 - t0}ms/${t2 - t1}ms ${memLine()}")
        if (okText && okImage) App.instance.settings.prefs.edit().putBoolean(provenKey(), true).apply()
        return (if (okText && okImage) "Passed. " else "Did not pass. ") + report
    }
}

@Suppress("DEPRECATION")
private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
