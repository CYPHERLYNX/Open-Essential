package com.craft.capturespace.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small but real OOXML packages built in the test (the same parts Word, Excel and PowerPoint write). */
class OfficeTextTest {
    private fun zip(vararg parts: Pair<String, String>): File {
        val f = File.createTempFile("fixture", ".zip"); f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z -> parts.forEach { (n, x) -> z.putNextEntry(ZipEntry(n)); z.write(x.toByteArray()); z.closeEntry() } }
        return f
    }

    private val core = """<?xml version="1.0"?><cp:coreProperties xmlns:cp="c" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Quarterly Plan</dc:title></cp:coreProperties>"""

    @Test fun docxParagraphsTablesAndTitle() {
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="w"><w:body>
            <w:p><w:r><w:t>Quarterly Plan</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Budget is </w:t></w:r><w:r><w:t>₹40,000 &amp; due 25 Nov.</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Cost</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>Laptop</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>900</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val app = """<Properties><Pages>3</Pages></Properties>"""
        val d = OfficeText.read(zip("word/document.xml" to doc, "docProps/core.xml" to core, "docProps/app.xml" to app), "docx", 10_000)
        assertEquals("Quarterly Plan", d.title)
        assertEquals(3, d.pages)
        assertTrue(d.text, d.text.contains("Budget is ₹40,000 & due 25 Nov."))
        assertTrue(d.text, d.text.contains("Item | Cost"))
        assertTrue(d.text, d.text.contains("Laptop | 900"))
    }

    @Test fun xlsxSharedStringsNumbersAndSheetNames() {
        val wb = """<workbook><sheets><sheet name="Expenses" sheetId="1"/></sheets></workbook>"""
        val ss = """<sst><si><t>Item</t></si><si><t>Amount</t></si><si><r><t>Rent</t></r></si></sst>"""
        val sh = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>12500</v></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>Food</t></is></c><c r="B3"><v>4200.5</v></c></row></sheetData></worksheet>"""
        val d = OfficeText.read(zip("xl/workbook.xml" to wb, "xl/sharedStrings.xml" to ss, "xl/worksheets/sheet1.xml" to sh), "xlsx", 10_000)
        assertEquals(1, d.pages)
        assertTrue(d.text, d.text.contains("Sheet: Expenses"))
        assertTrue(d.text, d.text.contains("Item | Amount"))
        assertTrue(d.text, d.text.contains("Rent | 12500"))
        assertTrue(d.text, d.text.contains("Food | 4200.5"))
    }

    @Test fun pptxSlidesInNumberOrder() {
        val s = { t: String -> """<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""" }
        val d = OfficeText.read(zip("ppt/slides/slide10.xml" to s("Ten"), "ppt/slides/slide2.xml" to s("Two"), "ppt/slides/slide1.xml" to s("Launch plan")), "pptx", 10_000)
        assertEquals(3, d.pages)
        val i1 = d.text.indexOf("Launch plan"); val i2 = d.text.indexOf("Two"); val i10 = d.text.indexOf("Ten")
        assertTrue(d.text, i1 in 0 until i2 && i2 < i10)
        assertTrue(d.text.startsWith("Slide 1:"))
    }

    @Test fun refusesDoctypeAndMissingParts() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><w:document xmlns:w="w"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>"""
        try { OfficeText.read(zip("word/document.xml" to evil), "docx", 10_000); fail("doctype accepted") } catch (e: OfficeText.Unreadable) {}
        try { OfficeText.read(zip("other.xml" to "<x/>"), "docx", 10_000); fail("missing part accepted") } catch (e: OfficeText.Unreadable) {}
    }
}
