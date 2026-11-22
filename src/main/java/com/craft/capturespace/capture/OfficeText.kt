package com.craft.capturespace.capture

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Plain text from Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files with the platform's own
 * zip and XML parsers (no third-party library, nothing leaves the phone). Lenient: a part that
 * can't be read is skipped. Limits guard against oversized or hostile files (zip bombs, entity tricks).
 */
object OfficeText {
    /** Largest part read from the zip (uncompressed). */
    private const val MAX_PART = 24L * 1024 * 1024
    private const val MAX_PARTS = 400

    data class Doc(val text: String, val pages: Int?, val title: String?, val unit: String)

    class Unreadable(msg: String) : Exception(msg)

    fun read(file: File, kind: String, maxChars: Int): Doc = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().take(5000).map { it.name }.toList()
        fun part(name: String): ByteArray? {
            val e = zip.getEntry(name) ?: return null
            if (e.size > MAX_PART) throw Unreadable("A part of this file is too large to read.")
            return zip.getInputStream(e).use { it.readCapped(MAX_PART) }
        }
        val title = part("docProps/core.xml")?.let { coreTitle(it) }
        when (kind) {
            "docx" -> {
                val body = part("word/document.xml") ?: throw Unreadable("This doesn't look like a Word document (word/document.xml is missing).")
                val pages = part("docProps/app.xml")?.let { appNumber(it, "Pages") }
                Doc(docx(body, maxChars), pages, title, "pages")
            }
            "pptx" -> {
                val slides = names.mapNotNull { n -> Regex("""^ppt/slides/slide(\d+)\.xml$""").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { it to n } }
                    .sortedBy { it.first }.take(MAX_PARTS)
                if (slides.isEmpty()) throw Unreadable("This presentation has no slides.")
                val sb = StringBuilder()
                for ((i, s) in slides.withIndex()) {
                    if (sb.length >= maxChars) break
                    val t = part(s.second)?.let { drawingText(it) }.orEmpty().trim()
                    sb.append("Slide ").append(i + 1).append(if (t.isEmpty()) "\n" else ":\n$t\n").append('\n')
                }
                Doc(sb.toString().take(maxChars), slides.size, title, "slides")
            }
            "xlsx" -> {
                val shared = part("xl/sharedStrings.xml")?.let { sharedStrings(it) } ?: emptyList()
                val sheetNames = part("xl/workbook.xml")?.let { sheetNames(it) } ?: emptyList()
                val sheets = names.mapNotNull { n -> Regex("""^xl/worksheets/sheet(\d+)\.xml$""").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { it to n } }
                    .sortedBy { it.first }.take(MAX_PARTS)
                if (sheets.isEmpty()) throw Unreadable("This workbook has no sheets.")
                val sb = StringBuilder()
                for ((i, s) in sheets.withIndex()) {
                    if (sb.length >= maxChars) break
                    val rows = part(s.second)?.let { sheetRows(it, shared, maxChars - sb.length) }.orEmpty()
                    sb.append("Sheet: ").append(sheetNames.getOrNull(i) ?: "Sheet ${i + 1}").append('\n')
                    rows.forEach { sb.append(it.joinToString(" | ")).append('\n') }
                    sb.append('\n')
                }
                Doc(sb.toString().take(maxChars), sheets.size, title, "sheets")
            }
            else -> throw Unreadable("Unsupported Office file.")
        }
    }

    // ---------- parts ----------

    private fun docx(xml: ByteArray, maxChars: Int): String {
        val sb = StringBuilder()
        var inText = false
        var inCell = 0
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q.local()) {
                    "t" -> inText = true
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append(if (inCell > 0) ' ' else '\n')
                    "tc" -> inCell++
                }
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                when (q.local()) {
                    "t" -> inText = false
                    // Table cells on one line ("a | b | c"), one row per line.
                    "p" -> sb.append(if (inCell > 0) ' ' else '\n')
                    "tc" -> { inCell--; while (sb.endsWith(" ")) sb.setLength(sb.length - 1); sb.append(" | ") }
                    "tr" -> sb.append('\n')
                }
                if (sb.length > maxChars) throw Stop()
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) sb.appendRange(ch, start, start + length) }
        })
        return tidy(sb.toString())
    }

    /** Text of DrawingML (slides): a:t runs, one line per a:p paragraph. */
    private fun drawingText(xml: ByteArray): String {
        val sb = StringBuilder()
        var inText = false
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q.local()) { "t" -> inText = true; "br" -> sb.append('\n') }
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                when (q.local()) { "t" -> inText = false; "p" -> sb.append('\n') }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) sb.appendRange(ch, start, start + length) }
        })
        return tidy(sb.toString())
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inSi = false; var inT = false; var inRph = false
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q.local()) { "si" -> { inSi = true; cur.clear() }; "t" -> inT = true; "rPh" -> inRph = true }
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                when (q.local()) { "si" -> { inSi = false; out += cur.toString() }; "t" -> inT = false; "rPh" -> inRph = false }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inSi && inT && !inRph) cur.appendRange(ch, start, start + length) }
        })
        return out
    }

    private fun sheetNames(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                if (q.local() == "sheet") a?.getValue("name")?.let { out += it }
            }
        })
        return out
    }

    private fun sheetRows(xml: ByteArray, shared: List<String>, budget: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var type: String? = null
        val v = StringBuilder()
        var inV = false; var inIs = false
        var used = 0
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q.local()) {
                    "row" -> row = ArrayList()
                    "c" -> { type = a?.getValue("t"); v.clear() }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inV = true
                }
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                when (q.local()) {
                    "v" -> inV = false
                    "t" -> if (inIs) inV = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString().trim()
                        val cell = when (type) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else if (raw == "0") "FALSE" else raw
                            else -> raw
                        }
                        row += cell.trim()
                    }
                    "row" -> if (row.any { it.isNotEmpty() }) {
                        rows += row.dropLastWhile { it.isEmpty() }
                        used += row.sumOf { it.length + 3 }
                        if (used > budget) throw Stop()
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inV) v.appendRange(ch, start, start + length) }
        })
        return rows
    }

    private fun coreTitle(xml: ByteArray): String? {
        var inTitle = false
        val sb = StringBuilder()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) { if (q == "dc:title" || q.local() == "title") inTitle = true }
            override fun endElement(uri: String?, local: String?, q: String) { if (q == "dc:title" || q.local() == "title") inTitle = false }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inTitle) sb.appendRange(ch, start, start + length) }
        })
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun appNumber(xml: ByteArray, tag: String): Int? {
        var inTag = false
        val sb = StringBuilder()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) { if (q.local() == tag) inTag = true }
            override fun endElement(uri: String?, local: String?, q: String) { if (q.local() == tag) inTag = false }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inTag) sb.appendRange(ch, start, start + length) }
        })
        return sb.toString().trim().toIntOrNull()?.takeIf { it > 0 }
    }

    // ---------- helpers ----------

    private class Stop : RuntimeException()

    private fun String.local() = substringAfter(':')

    private fun parse(xml: ByteArray, h: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        // Office parts never need a DTD or external entities: refuse them.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (k, v) -> try { f.setFeature(k, v) } catch (_: Exception) {} }
        if (String(xml, 0, minOf(xml.size, 2048), Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) throw Unreadable("This file contains a document type declaration, which Office files don't use.")
        try {
            f.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), h)
        } catch (_: Stop) {
        }
    }

    private fun InputStream.readCapped(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw Unreadable("A part of this file is too large to read.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun tidy(s: String) = s.lines().joinToString("\n") { it.trimEnd().removeSuffix("|").trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n").trim()
}
