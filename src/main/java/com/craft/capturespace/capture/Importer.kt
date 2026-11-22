package com.craft.capturespace.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.craft.capturespace.App
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.Meta
import com.craft.capturespace.data.Status
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_NOTE
import com.craft.capturespace.data.TYPE_VOICE
import java.io.File

/** Saves new captures and starts their analysis. */
object Importer {
    private fun analyse(ctx: Context, id: Long) {
        if (App.instance.settings.autoAnalyze) Pipeline.enqueue(ctx, id)
        else App.instance.repo.setStatus(id, Status.OFF)
    }

    /**
     * Shared or typed text. Text with a web link becomes a link capture: its public page is read only
     * when the person allowed link previews ([LinkPreview]); otherwise it is analysed from the text alone.
     */
    fun note(ctx: Context, text: String, sourceApp: String? = null): Long? {
        if (text.isBlank()) return null
        val url = LinkPreview.firstUrl(text)
        val choice = App.instance.settings.linkPreviews
        val meta = url?.let { Meta.of(Meta.Link(it, state = when (choice) { "on" -> "new"; "off" -> "skipped"; else -> "asked" })) }
        val id = App.instance.repo.insertCard(TYPE_NOTE, note = text.trim(), sourceApp = sourceApp, meta = meta)
        when {
            url != null && choice == "on" -> LinkPreview.fetchThenAnalyse(ctx, id)
            url != null && choice == "ask" -> {} // analysed once the person answers the question (ShareActivity / the capture page)
            else -> analyse(ctx, id)
        }
        return id
    }

    /** Called with the person's answer to "Fetch previews for links you share?". */
    fun linkChoice(ctx: Context, id: Long, fetch: Boolean) {
        val repo = App.instance.repo
        val c = repo.card(id) ?: return
        val l = Meta.link(c.meta) ?: return analyse(ctx, id)
        if (fetch) LinkPreview.fetchThenAnalyse(ctx, id)
        else { repo.setMeta(id, Meta.of(l.copy(state = "skipped"))); analyse(ctx, id) }
    }

    /** A document's stored title, unless it is a placeholder a program wrote ("Untitled", "Microsoft Word - x.docx"). */
    internal fun usefulDocTitle(t: String): Boolean {
        val s = t.trim()
        return s.length in 3..200 && !Regex("""(?i)^(untitled|title|document\d*|presentation\d*|book\d*|slide \d+|powerpoint presentation|microsoft (word|excel|powerpoint) - .*|.*\.(docx?|pdf|pptx?|xlsx?|txt))$""").matches(s)
    }

    fun bitmap(ctx: Context, bmp: Bitmap, sourceApp: String?, note: String = ""): Long {
        val f = App.instance.repo.newFile("png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val id = App.instance.repo.insertCard(TYPE_IMAGE, note = note, file = f.absolutePath, mime = "image/png", sourceApp = sourceApp)
        analyse(ctx, id)
        return id
    }

    fun imageFile(ctx: Context, file: File, note: String = "", audio: File? = null, audioMs: Long = 0, mime: String = "image/jpeg", sourceApp: String? = null): Long {
        val id = App.instance.repo.insertCard(TYPE_IMAGE, note = note, file = file.absolutePath, mime = mime, sourceApp = sourceApp,
            audio = audio?.absolutePath, audioMs = if (audio != null) audioMs else 0)
        analyse(ctx, id)
        return id
    }

    fun imageUri(ctx: Context, uri: Uri, sourceApp: String? = null, note: String = ""): Long? {
        val f = copy(ctx, uri, "img") ?: return null
        val id = App.instance.repo.insertCard(TYPE_IMAGE, note = note, file = f.absolutePath, mime = ctx.contentResolver.getType(uri) ?: "image/jpeg", sourceApp = sourceApp)
        analyse(ctx, id)
        return id
    }

    fun audioUri(ctx: Context, uri: Uri, sourceApp: String? = null): Long? {
        val mime = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
        val f = copy(ctx, uri, "audio") ?: return null
        val duration = try {
            MediaMetadataRetriever().run {
                setDataSource(f.absolutePath)
                val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                release(); d
            }
        } catch (_: Exception) { 0L }
        val id = App.instance.repo.insertCard(TYPE_VOICE, file = f.absolutePath, mime = normaliseAudioMime(mime), durationMs = duration, sourceApp = sourceApp)
        analyse(ctx, id)
        return id
    }

    /**
     * A shared or picked document: its text is extracted with code and saved as a text capture
     * (the original file is kept with it). A scanned PDF's first page becomes a picture capture.
     * Returns the capture id, or a reason it couldn't be saved.
     */
    fun documentUri(ctx: Context, uri: Uri, sourceApp: String? = null): Pair<Long?, String?> {
        val mime = ctx.contentResolver.getType(uri)
        val name = try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        val kind = Documents.kind(mime, name)
        android.util.Log.i("OE-import", "document mime=$mime ext=${name?.substringAfterLast('.', "")?.take(8)} kind=$kind") // no names or content
        kind ?: return null to "This file type isn't supported. Supported: ${Documents.SUPPORTED.joinToString()}."
        val f = copy(ctx, uri, kind) ?: return null to "The file couldn't be read."
        return when (val r = Documents.extract(ctx, f, kind)) {
            is Documents.Result.Text -> {
                if (r.text.isBlank()) { f.delete(); null to "The file has no text." }
                else {
                    val header = name?.let { "$it\n\n" } ?: ""
                    // Type, pages and the title stored in the file are facts from the parser, shown as they are.
                    val meta = Meta.of(Meta.Doc(name, kind, Documents.label(kind), r.pages, r.unit, r.title?.takeIf { usefulDocTitle(it) }, Documents.cover(f, kind)))
                    val id = App.instance.repo.insertCard(TYPE_NOTE, note = header + r.text, file = f.absolutePath, mime = mime ?: kind, sourceApp = sourceApp, meta = meta)
                    analyse(ctx, id); id to null
                }
            }
            is Documents.Result.Scanned -> {
                val note = "${name ?: "Scanned PDF"}: page 1 of ${r.pages} (scanned, read as a picture)"
                bitmap(ctx, r.firstPage, sourceApp, note) to null
            }
            is Documents.Result.Unsupported -> { f.delete(); null to r.reason }
        }
    }

    private fun normaliseAudioMime(m: String): String = when (m.lowercase()) {
        "audio/x-wav", "audio/wave" -> "audio/wav"
        "audio/mp3", "audio/mpga" -> "audio/mpeg"
        "audio/x-aac", "audio/aac-adts" -> "audio/aac"
        "audio/m4a", "audio/x-m4a" -> "audio/mp4"
        else -> m
    }

    private fun copy(ctx: Context, uri: Uri, ext: String): File? = try {
        val f = App.instance.repo.newFile(ext)
        // Android removes the GPS position from shared photos unless the app may read media
        // locations (Settings › Photos); then the original file, with its location, is read.
        val src = if (android.os.Build.VERSION.SDK_INT >= 29 && uri.authority == android.provider.MediaStore.AUTHORITY &&
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { android.provider.MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        (runCatching { ctx.contentResolver.openInputStream(src) }.getOrNull() ?: ctx.contentResolver.openInputStream(uri))
            ?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.length() > 0) f else { f.delete(); null }
    } catch (e: Exception) {
        null
    }
}
