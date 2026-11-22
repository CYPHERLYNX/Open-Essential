package com.craft.capturespace.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Turns shared or picked files into text with ordinary parsers (never the model).
 * Supported: plain text, Markdown, CSV, HTML, JSON, PDF, Word (.docx), Excel (.xlsx) and
 * PowerPoint (.pptx). A PDF without embedded text (a scan) goes through the picture path instead.
 * The old binary Office formats (.doc, .xls, .ppt) are not supported.
 */
object Documents {
    const val MAX_CHARS = 200_000

    val SUPPORTED = listOf("PDF (.pdf; a scanned PDF is read from its first page)", "Word (.docx)", "Excel (.xlsx)", "PowerPoint (.pptx)",
        "Plain text (.txt)", "Markdown (.md)", "CSV (.csv)", "HTML (.html)", "JSON (.json)")

    /** "PDF", "Word document"... for the capture's details. */
    fun label(kind: String): String = when (kind) {
        "pdf" -> "PDF"; "docx" -> "Word document"; "xlsx" -> "Excel workbook"; "pptx" -> "PowerPoint presentation"
        "json" -> "JSON"; "html" -> "Web page (HTML)"; "csv" -> "CSV table"; "md" -> "Markdown"; else -> "Text file"
    }

    sealed class Result {
        /** [pages]: pages, slides or sheets ([unit]); [title]: the title stored in the file, if any. */
        data class Text(val text: String, val pages: Int? = null, val title: String? = null, val unit: String = "pages") : Result()
        data class Scanned(val firstPage: Bitmap, val pages: Int) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    fun kind(mime: String?, name: String?): String? {
        val m = mime.orEmpty().lowercase()
        val n = name.orEmpty().lowercase()
        return when {
            m == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            m == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || n.endsWith(".docx") -> "docx"
            m == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || n.endsWith(".xlsx") -> "xlsx"
            m == "application/vnd.openxmlformats-officedocument.presentationml.presentation" || n.endsWith(".pptx") -> "pptx"
            m == "application/msword" || m == "application/vnd.ms-excel" || m == "application/vnd.ms-powerpoint" ||
                n.endsWith(".doc") || n.endsWith(".xls") || n.endsWith(".ppt") -> "legacy"
            m == "application/json" || n.endsWith(".json") -> "json"
            m == "text/html" || n.endsWith(".html") || n.endsWith(".htm") -> "html"
            m == "text/csv" || m == "text/comma-separated-values" || n.endsWith(".csv") -> "csv"
            m == "text/markdown" || n.endsWith(".md") || n.endsWith(".markdown") -> "md"
            m.startsWith("text/") || n.endsWith(".txt") -> "txt"
            else -> null
        }
    }

    fun extract(ctx: Context, file: File, kind: String): Result = try {
        when (kind) {
            "pdf" -> pdf(ctx, file)
            "docx", "xlsx", "pptx" -> OfficeText.read(file, kind, MAX_CHARS).let { Result.Text(it.text, it.pages, it.title, it.unit) }
            "legacy" -> Result.Unsupported("Unsupported file type: the old Office formats (.doc, .xls, .ppt) can't be read. Save it as .docx, .xlsx or .pptx and share it again.")
            "json" -> Result.Text(json(file.readText()))
            "html" -> Result.Text(Html.fromHtml(file.readText(), Html.FROM_HTML_MODE_LEGACY).toString())
            "csv" -> Result.Text(csv(file.readText()))
            else -> Result.Text(file.readText())
        }.let { if (it is Result.Text) it.copy(text = it.text.trim().take(MAX_CHARS)) else it }
    } catch (e: OfficeText.Unreadable) {
        Result.Unsupported(e.message ?: "This file couldn't be read.")
    } catch (e: java.util.zip.ZipException) {
        Result.Unsupported("This file is damaged or isn't really a ${label(kind)}.")
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        Result.Unsupported("This PDF is protected with a password, so it can't be read.")
    } catch (e: OutOfMemoryError) {
        Result.Unsupported("This file is too large to read on this phone.")
    } catch (e: Exception) {
        Result.Unsupported("This file couldn't be read (${e.javaClass.simpleName}).")
    }

    private fun pdf(ctx: Context, file: File): Result {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        var pages = 0
        var title: String? = null
        val text = PDDocument.load(file).use { doc ->
            pages = doc.numberOfPages
            title = try { doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            // Long PDFs: the text of the first 60 pages is plenty for the notes and keeps memory low.
            PDFTextStripper().apply { endPage = minOf(pages, 60) }.getText(doc)
        }.trim()
        if (text.length >= 20) return Result.Text(text, pages, title)
        // No embedded text: a scanned PDF. Render its first page for the picture path.
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        try {
            if (r.pageCount == 0) return Result.Unsupported("This PDF has no pages.")
            val p = r.openPage(0)
            try {
                val scale = 1600f / maxOf(p.width, p.height)
                val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return Result.Scanned(bmp, r.pageCount)
            } finally { p.close() }
        } finally { r.close(); fd.close() }
    }

    /**
     * A cover picture for the card, made on the phone from the file itself: a PDF's first page, or
     * the thumbnail an Office app saved inside a .docx/.xlsx/.pptx (docProps/thumbnail.*). Saved as
     * a JPEG next to the file. Returns its path, or null when the file has none.
     */
    fun cover(file: File, kind: String): String? = try {
        val bmp: Bitmap? = when (kind) {
            "pdf" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) null else r.openPage(0).use { p ->
                        val scale = 720f / maxOf(p.width, p.height)
                        Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            "docx", "xlsx", "pptx" -> java.util.zip.ZipFile(file).use { z ->
                val e = z.entries().asSequence().firstOrNull { e ->
                    val n = e.name.lowercase()
                    n.startsWith("docprops/thumbnail.") && (n.endsWith(".jpeg") || n.endsWith(".jpg") || n.endsWith(".png")) && e.size in 1..4_000_000
                }
                e?.let { z.getInputStream(it).use { s -> android.graphics.BitmapFactory.decodeStream(s) } }
                    ?.takeIf { it.width >= 64 && it.height >= 64 } // tiny placeholder thumbnails aren't worth showing
            }
            else -> null
        }
        bmp?.let { b ->
            val out = File(file.path + ".cover.jpg")
            out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            b.recycle()
            out.absolutePath
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    /** JSON as indented "key: value" lines, so the text step reads it like prose. */
    private fun json(s: String): String {
        val v: Any = s.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
        val sb = StringBuilder()
        fun walk(x: Any?, indent: String, key: String?) {
            val label = key?.let { "$it: " } ?: ""
            when (x) {
                is JSONObject -> { if (key != null) sb.append(indent).append(key).append(":\n"); x.keys().forEach { k -> walk(x.opt(k), "$indent  ", k) } }
                is JSONArray -> { if (key != null) sb.append(indent).append(key).append(":\n"); for (i in 0 until x.length()) walk(x.opt(i), "$indent  ", "-") }
                else -> sb.append(indent).append(label).append(x.toString()).append('\n')
            }
        }
        walk(v, "", null)
        return sb.toString()
    }

    /** CSV rows as "column: value" lines (handles quoted fields). */
    private fun csv(s: String): String {
        val rows = parseCsv(s)
        if (rows.isEmpty()) return ""
        val head = rows[0]
        return rows.drop(1).take(2000).joinToString("\n") { r ->
            r.mapIndexed { i, v -> "${head.getOrElse(i) { "col${i + 1}" }}: $v" }.joinToString("; ")
        }.ifBlank { head.joinToString(", ") }
    }

    internal fun parseCsv(s: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val f = StringBuilder(); var q = false; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                q && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { row += f.toString(); f.clear() }
                !q && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row += f.toString(); f.clear(); rows += row; row = ArrayList()
                }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
