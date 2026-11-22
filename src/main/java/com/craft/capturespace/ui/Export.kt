package com.craft.capturespace.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.craft.capturespace.App
import com.craft.capturespace.data.Card
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_VOICE
import java.io.File
import java.io.OutputStream

/** Sharing and saving captures (Markdown, PDF, image, recording). */
object Export {
    private const val AUTH = "com.craft.capturespace.files"

    fun markdown(c: Card): String = buildString {
        append("# ").append(c.displayTitle).append("\n\n")
        append("_").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.created))).append("_\n\n")
        if (c.summary.isNotBlank()) append("## Summary\n\n").append(c.summary).append("\n\n")
        if (c.details.isNotEmpty()) {
            append("## Details\n\n"); c.details.forEach { append("- **${it.label}:** ${it.value}\n") }; append('\n')
        }
        if (c.topics.isNotEmpty()) { append("## Main Topics\n\n"); c.topics.forEach { append("- $it\n") }; append('\n') }
        if (c.actions.isNotEmpty()) { append("## Action Items\n\n"); c.actions.forEach { append("- [ ] $it\n") }; append('\n') }
        if (c.keyMoments.isNotEmpty()) { append("## Key Moments\n\n"); c.keyMoments.forEach { append("- $it\n") }; append('\n') }
        if (c.note.isNotBlank()) append("## Note\n\n").append(c.note).append("\n\n")
        if (c.transcript.isNotBlank()) append("## Transcript\n\n").append(c.transcript).append("\n\n")
        if (c.tags.isNotEmpty()) append(c.tags.joinToString(" ") { "#" + it.replace(' ', '_') }).append('\n')
    }

    fun share(ctx: Context, c: Card) {
        val text = markdown(c)
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, c.displayTitle).putExtra(Intent.EXTRA_TEXT, text)
        val f = c.file?.let { File(it) }?.takeIf { it.exists() && (c.type == TYPE_IMAGE || c.type == TYPE_VOICE) }
        if (f != null) {
            i.type = c.mime ?: if (c.type == TYPE_IMAGE) "image/*" else "audio/*"
            i.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, AUTH, f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else i.type = "text/plain"
        try {
            ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(ctx, "Share failed. Please try again.")
        }
    }

    private fun safeName(c: Card) = c.displayTitle.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "capture" }.take(40)

    /** Writes into Downloads (Android 10+) or the app's own documents folder (older). */
    private fun save(ctx: Context, name: String, mime: String, collection: String, write: (OutputStream) -> Unit): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = when (collection) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, when (collection) {
                    "image" -> Environment.DIRECTORY_PICTURES + "/Open Essential"
                    "audio" -> Environment.DIRECTORY_MUSIC + "/Open Essential"
                    else -> Environment.DIRECTORY_DOWNLOADS + "/Open Essential"
                })
            }
            val out = ctx.contentResolver.insert(uri, values) ?: error("no target")
            ctx.contentResolver.openOutputStream(out)!!.use(write)
            true
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Open Essential").apply { mkdirs() }
            File(dir, name).outputStream().use(write)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun saveMarkdown(ctx: Context, c: Card) {
        val ok = save(ctx, safeName(c) + ".md", "text/markdown", "files") { it.write(markdown(c).toByteArray()) }
        toast(ctx, if (ok) "Markdown saved to Files" else "Failed to save Markdown file.")
    }

    fun saveImage(ctx: Context, c: Card) {
        val f = c.file?.let { File(it) }
        val ok = f != null && f.exists() && save(ctx, safeName(c) + (if (c.mime == "image/png") ".png" else ".jpg"), c.mime ?: "image/jpeg", "image") { o -> f.inputStream().use { it.copyTo(o) } }
        toast(ctx, if (ok) "Image saved to Photos" else "Failed to save image.")
    }

    fun saveAudio(ctx: Context, c: Card) {
        val f = c.file?.let { File(it) }
        val ok = f != null && f.exists() && save(ctx, safeName(c) + "." + f.extension, c.mime ?: "audio/aac", "audio") { o -> f.inputStream().use { it.copyTo(o) } }
        toast(ctx, if (ok) "Audio recording saved to Files" else "Failed to save audio recording.")
    }

    /** Share in one format from the share sheet: "image" (the capture itself), "pdf" or "markdown". */
    fun shareAs(ctx: Context, c: Card, format: String) {
        if (format == "image") return share(ctx, c)
        try {
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, safeName(c) + if (format == "pdf") ".pdf" else ".md")
            f.outputStream().use { if (format == "pdf") writePdf(c, it) else it.write(markdown(c).toByteArray()) }
            val i = Intent(Intent.ACTION_SEND).setType(if (format == "pdf") "application/pdf" else "text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, c.displayTitle)
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, AUTH, f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (format != "pdf") i.putExtra(Intent.EXTRA_TEXT, markdown(c))
            ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(ctx, "Share failed. Please try again.")
        }
    }

    /** Save in one format from the share sheet. */
    fun saveAs(ctx: Context, c: Card, format: String) = when (format) {
        "image" -> if (c.type == TYPE_VOICE) saveAudio(ctx, c) else saveImage(ctx, c)
        "pdf" -> savePdf(ctx, c)
        else -> saveMarkdown(ctx, c)
    }

    private fun writePdf(c: Card, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val w = 595; val h = 842; val margin = 48
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
            val text = markdown(c).replace("**", "").replace("_", "")
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w - margin * 2).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo++).create())
                val top = layout.getLineTop(line)
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= h - margin * 2) last++
                page.canvas.save()
                page.canvas.translate(margin.toFloat(), (margin - top).toFloat())
                page.canvas.clipRect(0, top, w, layout.getLineBottom(last - 1))
                layout.draw(page.canvas)
                page.canvas.restore()
                doc.finishPage(page)
                line = last
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun savePdf(ctx: Context, c: Card) {
        val doc = PdfDocument()
        try {
            val w = 595; val h = 842; val margin = 48
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
            val text = markdown(c).replace("**", "").replace("_", "")
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w - margin * 2).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo++).create())
                val top = layout.getLineTop(line)
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= h - margin * 2) last++
                page.canvas.save()
                page.canvas.translate(margin.toFloat(), (margin - top).toFloat())
                page.canvas.clipRect(0, top, w, layout.getLineBottom(last - 1))
                layout.draw(page.canvas)
                page.canvas.restore()
                doc.finishPage(page)
                line = last
            }
            val ok = save(ctx, safeName(c) + ".pdf", "application/pdf", "files") { doc.writeTo(it) }
            toast(ctx, if (ok) "PDF saved to Files" else "Failed to save PDF.")
        } catch (e: Exception) {
            toast(ctx, "Failed to save PDF.")
        } finally {
            doc.close()
        }
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private val app get() = App.instance
}
