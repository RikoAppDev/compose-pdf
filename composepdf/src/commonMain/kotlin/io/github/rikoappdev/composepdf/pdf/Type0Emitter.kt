package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.font.TrueTypeFont
import io.github.rikoappdev.composepdf.font.subsetFont
import io.github.rikoappdev.composepdf.util.toHex4

/**
 * Emits a complete embedded Type0/CIDFontType2 font (Identity-H) into [doc] and returns the
 * Type0 font object number. Shared by all generators so the font-embedding path is exercised
 * by the Slice 0 identity test.
 *
 * Objects added (in dependency order): FontFile2 (subset) → ToUnicode → FontDescriptor →
 * CIDFontType2 → Type0.
 */
internal fun emitType0Font(
    doc: PdfDoc,
    font: TrueTypeFont,
    usedGids: Set<Int>,
    gidToCp: Map<Int, Int>,
    baseFont: String,
): Int {
    val upm = font.unitsPerEm
    val subset = subsetFont(font, usedGids)
    val sorted = usedGids.toList().sorted()

    fun scale(v: Int): Int = (v.toLong() * 1000 / upm).toInt()
    fun width1000(gid: Int): Int = ((font.advanceWidth(gid).toLong() * 1000 + upm / 2) / upm).toInt()

    val fontFile2 = doc.add(streamObject("/Length1 ${subset.bytes.size}", subset.bytes, compress = true))
    val toUnicode = doc.add(streamObject("", asciiBytes(buildToUnicode(sorted, gidToCp)), compress = true))

    val descriptor = doc.addDict(
        "<< /Type /FontDescriptor /FontName /$baseFont /Flags 32 " +
            "/FontBBox [${scale(font.xMin)} ${scale(font.yMin)} ${scale(font.xMax)} ${scale(font.yMax)}] " +
            "/ItalicAngle 0 /Ascent ${scale(font.ascender)} /Descent ${scale(font.descender)} " +
            "/CapHeight ${scale(font.ascender)} /StemV 80 /FontFile2 $fontFile2 0 R >>"
    )

    val w = StringBuilder()
    for ((i, g) in sorted.withIndex()) {
        if (i > 0) w.append(' ')
        w.append(g).append(" [").append(width1000(g)).append(']')
    }
    val cidFont = doc.addDict(
        "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$baseFont " +
            "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
            "/FontDescriptor $descriptor 0 R /CIDToGIDMap /Identity /DW 1000 /W [$w] >>"
    )
    return doc.addDict(
        "<< /Type /Font /Subtype /Type0 /BaseFont /$baseFont /Encoding /Identity-H " +
            "/DescendantFonts [$cidFont 0 R] /ToUnicode $toUnicode 0 R >>"
    )
}

internal fun buildToUnicode(sortedGids: List<Int>, gidToCp: Map<Int, Int>): String = buildString {
    append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
    append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
    append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
    append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
    var i = 0
    while (i < sortedGids.size) {
        val chunk = minOf(100, sortedGids.size - i)
        append(chunk).append(" beginbfchar\n")
        for (j in i until i + chunk) {
            val g = sortedGids[j]
            append('<').append(g.toHex4()).append("> <").append(cpToUtf16BeHex(gidToCp[g] ?: 0)).append(">\n")
        }
        append("endbfchar\n")
        i += chunk
    }
    append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
}

internal fun cpToUtf16BeHex(cp: Int): String =
    if (cp <= 0xFFFF) cp.toHex4()
    else {
        val v = cp - 0x10000
        (0xD800 + (v ushr 10)).toHex4() + (0xDC00 + (v and 0x3FF)).toHex4()
    }
