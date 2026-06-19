package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.TrueTypeFont
import io.github.rikoappdev.composepdf.pdf.PdfDoc
import io.github.rikoappdev.composepdf.pdf.emitType0Font
import io.github.rikoappdev.composepdf.pdf.streamObject
import io.github.rikoappdev.composepdf.util.ByteBuf
import io.github.rikoappdev.composepdf.util.toCodePoints
import io.github.rikoappdev.composepdf.util.toHex4

/**
 * Low-level Slice 0 helper: a single-page A4 PDF with one line of vector, selectable text drawn
 * with an embedded subset of [fontBytes]. Pure-`commonMain` integer math over byte-parsed font
 * metrics, so output is identical on every platform running this code.
 */
object ComposePdf {

    fun buildSinglePageText(
        fontBytes: ByteArray,
        text: String,
        fontSizePt: Int = 14,
        xPt: Int = 50,
        baselineFromTopPt: Int = 80,
        pageWidthPt: Int = 595,
        pageHeightPt: Int = 842,
        subsetTag: String = "CPDFAA",
    ): ByteArray {
        val font = TrueTypeFont(fontBytes)
        val cps = text.toCodePoints()
        val gids = IntArray(cps.size) { font.gidForCodePoint(cps[it]) }

        val usedSet = HashSet<Int>()
        val gidToCp = HashMap<Int, Int>()
        for (i in cps.indices) {
            usedSet.add(gids[i])
            gidToCp[gids[i]] = cps[i]
        }

        val doc = PdfDoc()
        val catalog = doc.reserve()
        val pages = doc.reserve()
        val page = doc.reserve()

        val type0 = emitType0Font(doc, font, usedSet, gidToCp, "$subsetTag+ComposePdfFont")

        val glyphHex = StringBuilder(gids.size * 4)
        for (g in gids) glyphHex.append(g.toHex4())
        val yPt = pageHeightPt - baselineFromTopPt
        val content = ByteBuf(glyphHex.length + 64).apply {
            ascii("BT\n/F0 $fontSizePt Tf\n$xPt $yPt Td\n<")
            ascii(glyphHex.toString())
            ascii("> Tj\nET\n")
        }
        val contents = doc.add(streamObject("", content.toByteArray()))

        doc.setDict(
            page,
            "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 $pageWidthPt $pageHeightPt] " +
                "/Resources << /Font << /F0 $type0 0 R >> >> /Contents $contents 0 R >>"
        )
        doc.setDict(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        doc.setDict(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
        return doc.build(rootObj = catalog)
    }
}
