package com.craft.capturespace.ai

import android.os.Build
import java.io.File

/** JNI to liboe_llama.so (llama.cpp + libmtmd, pinned in third_party/ENGINES.md). */
object NativeLlama {
    /** Null when loaded; otherwise why the engine can't run on this phone. */
    val unavailable: String? = loadLib("oe_llama")

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoad(model: String, mmproj: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: ByteArray, rgb: ByteArray?, width: Int, height: Int, maxTokens: Int,
        temp: Float, topP: Float, topK: Int, presencePenalty: Float,
        /** GBNF rules the reply must follow (UTF-8), or null for free text. */
        grammar: ByteArray?,
    ): ByteArray
}

/** JNI to liboe_whisper.so (whisper.cpp, pinned in third_party/ENGINES.md). */
object NativeWhisper {
    val unavailable: String? = loadLib("oe_whisper")

    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLoad(model: String): Long
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, pcm16k: FloatArray, language: String, nThreads: Int): ByteArray
}

/** The engines are built for 64-bit ARM with ARMv8.2 dot product and half-precision maths. */
internal object CpuCheck {
    val problem: String? by lazy {
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) return@lazy "This phone's processor isn't 64-bit ARM, which the on-device engine needs."
        val features = try {
            File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
                .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }.toSet()
        } catch (_: Exception) { emptySet() }
        when {
            features.isEmpty() -> null // can't tell; let the load try
            "asimddp" !in features -> "This phone's processor lacks the dot-product instructions the on-device engine is built for."
            "fphp" !in features || "asimdhp" !in features -> "This phone's processor lacks the half-precision instructions the on-device engine is built for."
            else -> null
        }
    }
}

private fun loadLib(name: String): String? {
    CpuCheck.problem?.let { return it }
    return try {
        System.loadLibrary(name); null
    } catch (e: UnsatisfiedLinkError) {
        "The on-device engine isn't available for this phone's processor."
    }
}
