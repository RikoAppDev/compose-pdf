package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.util.toCodePoints

/**
 * Holds the Regular + Bold faces, shapes text to glyph ids, measures widths (integer font-unit
 * math), and tracks which glyphs each face actually uses so only those are embedded.
 */
internal class FontBook(regularBytes: ByteArray, boldBytes: ByteArray) {

    val regular = TrueTypeFont(regularBytes)
    val bold = TrueTypeFont(boldBytes)

    val usedRegular = HashSet<Int>()
    val usedBold = HashSet<Int>()
    val gidToCpRegular = HashMap<Int, Int>()
    val gidToCpBold = HashMap<Int, Int>()

    fun fontFor(w: FontWeight): TrueTypeFont = if (w == FontWeight.Bold) bold else regular
    private fun usedFor(w: FontWeight) = if (w == FontWeight.Bold) usedBold else usedRegular
    private fun gidMapFor(w: FontWeight) = if (w == FontWeight.Bold) gidToCpBold else gidToCpRegular

    /** Shapes [text] to glyph ids, recording used glyphs + gid→code-point for ToUnicode. */
    fun shape(text: String, w: FontWeight): IntArray {
        val font = fontFor(w); val used = usedFor(w); val map = gidMapFor(w)
        val cps = text.toCodePoints()
        return IntArray(cps.size) { i ->
            val g = font.gidForCodePoint(cps[i])
            used.add(g); map[g] = cps[i]
            g
        }
    }

    /** Width of [text] in PDF points at [fontSizePt] (does not record usage). */
    fun measureWidthPt(text: String, w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w); val upm = font.unitsPerEm
        var sum = 0L
        for (cp in text.toCodePoints()) sum += font.advanceWidth(font.gidForCodePoint(cp))
        return ((sum * fontSizePt + upm / 2) / upm).toInt()
    }

    /** Width of already-shaped [gids] in PDF points at [fontSizePt]. */
    fun widthOfPt(gids: IntArray, w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w); val upm = font.unitsPerEm
        var sum = 0L
        for (g in gids) sum += font.advanceWidth(g)
        return ((sum * fontSizePt + upm / 2) / upm).toInt()
    }

    /** Ascent (positive) in PDF points at [fontSizePt]. */
    fun ascentPt(w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w)
        return ((font.ascender.toLong() * fontSizePt + font.unitsPerEm / 2) / font.unitsPerEm).toInt()
    }

    /** Descent magnitude (positive) in PDF points at [fontSizePt]. */
    fun descentPt(w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w)
        return ((-font.descender.toLong() * fontSizePt + font.unitsPerEm / 2) / font.unitsPerEm).toInt()
    }

    fun usedWeights(): List<FontWeight> = buildList {
        if (usedRegular.isNotEmpty()) add(FontWeight.Normal)
        if (usedBold.isNotEmpty()) add(FontWeight.Bold)
    }

    fun usedGids(w: FontWeight): Set<Int> = usedFor(w)
    fun gidToCp(w: FontWeight): Map<Int, Int> = gidMapFor(w)
}
