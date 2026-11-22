package com.craft.capturespace.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateMapOf
import com.craft.capturespace.App
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One on-device model file: official name, where it comes from, exact size and SHA-256. */
data class ModelFile(val name: String, val purpose: String, val url: String, val bytes: Long, val sha256: String)

sealed class ModelState {
    data object Missing : ModelState()
    data class Downloading(val done: Long, val total: Long) : ModelState()
    data object Verifying : ModelState()
    data object Ready : ModelState()
    data class Failed(val message: String) : ModelState()
}

/**
 * Downloads the on-device models only when the person asks, into the app's private storage.
 * A download resumes from its ".part" file (also after the app was closed), is checked for its
 * exact size and SHA-256, and only then renamed to its official name. Nothing else is sent.
 */
object Models {
    /** Qwen3.5-0.8B (Apache 2.0), Q4_K_M quantisation by Unsloth. Paired with [VISION]. */
    val ANALYSIS = ModelFile(
        "Qwen3.5-0.8B-Q4_K_M.gguf", "Qwen3.5-0.8B: reads text and pictures, writes notes",
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
        532_517_120L, "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
    )
    /** The vision projector that belongs to Qwen3.5-0.8B (its upstream file name is generic). */
    val VISION = ModelFile(
        "mmproj-F16.gguf", "Qwen3.5-0.8B vision projector: lets it see pictures",
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf",
        204_987_232L, "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453"
    )
    val SPEECH = ModelFile(
        "ggml-small-q5_1.bin", "Whisper small: turns speech into text",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    )
    val ALL = listOf(ANALYSIS, VISION, SPEECH)
    val TOTAL_BYTES = ALL.sumOf { it.bytes } // 927,589,839

    /** Files from the earlier model set. Removed only once the new set has proved itself on the phone. */
    val RETIRED = listOf(
        "LFM2-VL-450M-Q4_0.gguf" to 219_307_424L,
        "mmproj-LFM2-VL-450M-Q8_0.gguf" to 103_890_016L,
        "ggml-tiny-q5_1.bin" to 32_152_673L,
    )

    /** Every byte the model folder really holds (new set, old set, unfinished downloads). */
    fun diskUsed(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Old files still on the phone, by exact name and size. */
    fun retiredPresent(ctx: Context) = RETIRED.mapNotNull { (n, b) -> File(dir(ctx), n).takeIf { it.exists() && it.length() == b } }

    /** Deletes the old set (exact name and size only); returns what was deleted. */
    fun deleteRetired(ctx: Context): List<String> = retiredPresent(ctx).filter { it.delete() }.map { it.name }

    /**
     * Copies a file the person picked (system file picker) into private storage, keeping it only if
     * it is one of [ALL] by exact size and SHA-256. Returns which model it was, or a reason it wasn't.
     */
    fun import(ctx: Context, uri: android.net.Uri, onDone: (String) -> Unit) {
        App.instance.scope.launch {
            val msg = try {
                val cr = ctx.contentResolver
                var name: String? = null; var size = -1L
                cr.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                val m = ALL.firstOrNull { it.name == name } ?: ALL.firstOrNull { it.bytes == size }
                when {
                    m == null -> "\"${name ?: "That file"}\" isn't one of the three model files."
                    size >= 0 && size != m.bytes -> "${m.name} has the wrong size ($size bytes, expected ${m.bytes})."
                    dir(ctx).usableSpace < m.bytes + 50_000_000L -> "Not enough free space on this phone for ${m.name}."
                    else -> {
                        cancel(m)
                        state[m.name] = ModelState.Verifying
                        val tmp = File(dir(ctx), m.name + ".import")
                        cr.openInputStream(uri)!!.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 1 shl 20) } }
                        when {
                            tmp.length() != m.bytes -> { tmp.delete(); state[m.name] = ModelState.Failed("Imported file had the wrong size."); "${m.name}: wrong size, not kept." }
                            sha256(tmp) != m.sha256 -> { tmp.delete(); state[m.name] = ModelState.Failed("Imported file didn't match its SHA-256."); "${m.name}: damaged or a different file, not kept." }
                            else -> {
                                val f = file(ctx, m); f.delete(); part(ctx, m).delete()
                                if (!tmp.renameTo(f)) { tmp.delete(); "${m.name} couldn't be saved." }
                                else { setVerified(m, f.lastModified()); state[m.name] = ModelState.Ready; onReady(ctx); "${m.name} imported and verified." }
                            }
                        }
                    }
                }
            } catch (e: Exception) { "Import failed: ${e.message ?: e.javaClass.simpleName}" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(msg) }
        }
    }

    val state = mutableStateMapOf<String, ModelState>()
    private val jobs = HashMap<String, Job>()

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: ModelFile) = File(dir(ctx), m.name)
    private fun part(ctx: Context, m: ModelFile) = File(dir(ctx), m.name + ".part")

    /** Checks what is on the phone (size, then SHA-256 once per file change). */
    fun refresh(ctx: Context) {
        App.instance.scope.launch { refreshNow(ctx) }
    }

    /** Same as [refresh], on the calling (background) thread. */
    fun refreshNow(ctx: Context) {
        run {
            for (m in ALL) {
                if (jobs[m.name]?.isActive == true) continue
                val f = file(ctx, m)
                state[m.name] = when {
                    !f.exists() -> ModelState.Missing
                    f.length() != m.bytes -> ModelState.Failed("Wrong size; delete it and download again.")
                    verifiedStamp(m) == f.lastModified() -> ModelState.Ready
                    else -> {
                        state[m.name] = ModelState.Verifying
                        if (sha256(f) == m.sha256) { setVerified(m, f.lastModified()); ModelState.Ready }
                        else ModelState.Failed("The file is damaged; delete it and download again.")
                    }
                }
            }
        }
    }

    fun allReady() = ALL.all { state[it.name] == ModelState.Ready }

    /** Once the whole set is verified, analyse what was waiting for it. */
    private fun onReady(ctx: Context) {
        if (allReady()) App.instance.scope.launch { Pipeline.resumePending(ctx.applicationContext) }
    }

    fun download(ctx: Context, m: ModelFile) {
        if (jobs[m.name]?.isActive == true || state[m.name] == ModelState.Ready) return
        jobs[m.name] = App.instance.scope.launch {
            state[m.name] = try {
                fetch(ctx, m)
            } catch (e: Exception) {
                ModelState.Failed(if (e is kotlinx.coroutines.CancellationException) "Cancelled." else "Download failed. Check your connection and try again.")
            }
        }
        DownloadService.start(ctx.applicationContext)
    }

    fun cancel(m: ModelFile) { jobs[m.name]?.cancel() }

    fun cancelAll() { ALL.forEach { cancel(it) } }

    fun anyDownloading(): Boolean = ALL.any { jobs[it.name]?.isActive == true }

    /** Bytes downloaded and expected, over the downloads still running. */
    fun progress(): Pair<Long, Long> {
        var done = 0L; var total = 0L
        for (m in ALL) {
            if (jobs[m.name]?.isActive != true) continue
            total += m.bytes
            done += when (val s = state[m.name]) {
                is ModelState.Downloading -> s.done
                ModelState.Verifying -> m.bytes
                else -> 0L
            }
        }
        return done to total
    }

    fun delete(ctx: Context, m: ModelFile) {
        cancel(m)
        file(ctx, m).delete(); part(ctx, m).delete()
        App.instance.settings.prefs.edit().remove("model_ok_" + m.name).apply()
        state[m.name] = ModelState.Missing
    }

    private suspend fun fetch(ctx: Context, m: ModelFile): ModelState {
        if (App.instance.settings.modelsWifiOnly && !onUnmeteredNetwork(ctx)) return ModelState.Failed("Waiting for Wi-Fi. Turn off \"Wi-Fi only\" to use mobile data.")
        val part = part(ctx, m)
        if (part.length() > m.bytes) part.delete()
        val need = m.bytes - part.length()
        if (dir(ctx).usableSpace < need + 50_000_000L) return ModelState.Failed("Not enough free space on this phone.")
        if (need > 0) {
            val c = URL(m.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = c.responseCode
            val append = code == 206
            if (code != 200 && code != 206) { c.disconnect(); return ModelState.Failed("The download server answered $code. Try again later.") }
            if (!append) part.delete()
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = part.length()
                    var last = 0L
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 512 * 1024) { state[m.name] = ModelState.Downloading(done, m.bytes); last = done }
                    }
                }
            }
            c.disconnect()
        }
        state[m.name] = ModelState.Verifying
        if (part.length() != m.bytes) return ModelState.Failed("The download was incomplete. Tap Download to continue.")
        if (sha256(part) != m.sha256) { part.delete(); return ModelState.Failed("The download was damaged and was removed. Tap Download to try again.") }
        val f = file(ctx, m)
        if (!part.renameTo(f)) return ModelState.Failed("The file couldn't be saved.")
        setVerified(m, f.lastModified())
        state[m.name] = ModelState.Ready
        onReady(ctx)
        return ModelState.Ready
    }

    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun verifiedStamp(m: ModelFile) = App.instance.settings.prefs.getLong("model_ok_" + m.name, -1L)
    private fun setVerified(m: ModelFile, stamp: Long) = App.instance.settings.prefs.edit().putLong("model_ok_" + m.name, stamp).apply()

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = r.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
