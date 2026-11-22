package com.craft.capturespace.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.craft.capturespace.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Share to Open Essential" from any app: text, pictures and audio files. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i?.action != Intent.ACTION_SEND && i?.action != Intent.ACTION_SEND_MULTIPLE) { finish(); return }
        val type = i.type.orEmpty()
        val from = referrer?.host?.let { pkg ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { null }
        }
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: List<Uri> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty().take(20)
            val ctx = applicationContext
            App.instance.scope.launch {
                // Several files at once: each is saved by its own type (pictures, audio, documents).
                var why: String? = null
                val saved = uris.count { u ->
                    val t = try { ctx.contentResolver.getType(u) } catch (_: Exception) { null }.orEmpty()
                    when {
                        t.startsWith("image/") -> Importer.imageUri(ctx, u, from, "") != null
                        t.startsWith("audio/") -> Importer.audioUri(ctx, u, from) != null
                        else -> Importer.documentUri(ctx, u, from).let { (id, reason) -> if (id == null && why == null) why = reason; id != null }
                    }
                }
                withContext(Dispatchers.Main) {
                    val msg = when {
                        saved == uris.size -> "Saved $saved to Open Essential"
                        saved > 0 -> "Saved $saved of ${uris.size}. " + (why ?: "Some files couldn't be read.")
                        else -> why ?: "The file type is not supported"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    // Finish only now: Android withdraws the read access to shared files when this
                    // screen closes, so every file must be copied first.
                    finish()
                }
            }
            return
        }
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
            .joinToString("\n").trim()
        val ctx = applicationContext
        // The first shared link: ask once whether the app may read the public page (see LinkPreview).
        if (uri == null && type.startsWith("text/") && LinkPreview.firstUrl(text) != null && App.instance.settings.linkPreviews == "ask") {
            App.instance.scope.launch {
                val id = Importer.note(ctx, text, from)
                withContext(Dispatchers.Main) {
                    if (id == null || isFinishing) { finish(); return@withContext }
                    LinkConsent.show(this@ShareActivity) { fetch ->
                        Importer.linkChoice(ctx, id, fetch)
                        Toast.makeText(ctx, "Saved to Open Essential", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            return
        }
        App.instance.scope.launch {
            var why: String? = null
            val ok = when {
                type.startsWith("image/") && uri != null -> Importer.imageUri(ctx, uri, from, text) != null
                type.startsWith("audio/") && uri != null -> Importer.audioUri(ctx, uri, from) != null
                uri != null -> Importer.documentUri(ctx, uri, from).let { (id, reason) -> why = reason; id != null }
                type.startsWith("text/") && text.isNotEmpty() -> Importer.note(ctx, text, from) != null
                else -> false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, if (ok) "Saved to Open Essential" else why ?: "The file type is not supported", Toast.LENGTH_LONG).show()
                finish() // after the file is copied (see above)
            }
        }
    }
}
