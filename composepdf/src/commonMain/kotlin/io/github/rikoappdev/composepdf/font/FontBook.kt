package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.image.decodePng
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.PngImageData
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.util.toCodePoints

/**
 * Holds the Regular + Bold faces, shapes text to glyph ids, measures widths (integer font-unit
 * math), and tracks which glyphs each face actually uses so only those are embedded.
 *
 * An optional **color-emoji face** ([emojiBytes], an Apple `sbix` or Noto `CBLC`/`CBDT` font) acts as
 * a fallback: code points the text faces can't render but the emoji face can are drawn as inline color
 * **bitmap images** instead of `.notdef` boxes. Each distinct emoji glyph is decoded once into an
 * [EmbeddedImage] (accumulated in [emojiImages]); the layout places it with an [ImageOp]. When no emoji
 * face is supplied the shaping/measurement/placement paths are byte-for-byte identical to before.
 */
internal class FontBook(
    regularBytes: ByteArray,
    boldBytes: ByteArray,
    emojiBytes: ByteArray? = null,
    /** Index in the final image list at which [emojiImages] begin (= the document's own image count). */
    private val baseImageIndex: Int = 0,
) : TextMetrics {

    val regular = TrueTypeFont(regularBytes)
    val bold = TrueTypeFont(boldBytes)

    /** The emoji face, kept only if it parses AND actually carries color bitmaps we can extract. */
    private val emoji: TrueTypeFont? = emojiBytes
        ?.let { runCatching { TrueTypeFont(it) }.getOrNull() }
        ?.takeIf { it.hasColorBitmaps }

    val usedRegular = HashSet<Int>()
    val usedBold = HashSet<Int>()
    val gidToCpRegular = HashMap<Int, Int>()
    val gidToCpBold = HashMap<Int, Int>()

    /** Color-emoji bitmaps discovered during placement, in discovery order (deduped per emoji glyph). */
    val emojiImages = ArrayList<EmbeddedImage>()
    private val emojiGlobalIndex = HashMap<Int, EmojiImg>() // emoji gid -> placed image

    private class EmojiImg(val globalIndex: Int, val intrinsicW: Int, val intrinsicH: Int)

    fun fontFor(w: FontWeight): TrueTypeFont = if (w == FontWeight.Bold) bold else regular
    private fun usedFor(w: FontWeight) = if (w == FontWeight.Bold) usedBold else usedRegular
    private fun gidMapFor(w: FontWeight) = if (w == FontWeight.Bold) gidToCpBold else gidToCpRegular

    /** Shapes [text] to glyph ids, recording used glyphs + gid→code-point for ToUnicode. */
    override fun shape(text: String, w: FontWeight): IntArray {
        val font = fontFor(w); val used = usedFor(w); val map = gidMapFor(w)
        val cps = text.toCodePoints()
        return IntArray(cps.size) { i ->
            val g = font.gidForCodePoint(cps[i])
            used.add(g); map[g] = cps[i]
            g
        }
    }

    /** Width of [text] in PDF points at [fontSizePt] (does not record usage). */
    override fun measureWidthPt(text: String, w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w); val upm = font.unitsPerEm
        if (emoji == null) {
            var sum = 0L
            for (cp in text.toCodePoints()) sum += font.advanceWidth(font.gidForCodePoint(cp))
            return ((sum * fontSizePt + upm / 2) / upm).toInt()
        }
        // Emoji-aware: emoji code points consume the emoji advance, joiners/selectors consume nothing.
        var sum = 0
        for (cp in text.toCodePoints()) {
            when (val k = classify(cp, w)) {
                is Glyph -> sum += scaleAdvance(font.advanceWidth(font.gidForCodePoint(cp)), upm, fontSizePt)
                is Emoji -> sum += emojiAdvancePt(k.gid, fontSizePt)
                ZeroWidth -> {}
            }
        }
        return sum
    }

    /** Width of already-shaped [gids] in PDF points at [fontSizePt]. */
    override fun widthOfPt(gids: IntArray, w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w); val upm = font.unitsPerEm
        var sum = 0L
        for (g in gids) sum += font.advanceWidth(g)
        return ((sum * fontSizePt + upm / 2) / upm).toInt()
    }

    /** Ascent (positive) in PDF points at [fontSizePt]. */
    override fun ascentPt(w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w)
        return ((font.ascender.toLong() * fontSizePt + font.unitsPerEm / 2) / font.unitsPerEm).toInt()
    }

    /** Descent magnitude (positive) in PDF points at [fontSizePt]. */
    fun descentPt(w: FontWeight, fontSizePt: Int): Int {
        val font = fontFor(w)
        return ((-font.descender.toLong() * fontSizePt + font.unitsPerEm / 2) / font.unitsPerEm).toInt()
    }

    override fun placeLine(
        line: String,
        w: FontWeight,
        fontSizePt: Int,
        color: PdfColor,
        xPt: Int,
        baselineYPt: Int,
        out: MutableList<DrawOp>,
    ) {
        if (line.isEmpty()) return
        // Fast path: no emoji face → the whole line is one glyph run, exactly as before.
        if (emoji == null) {
            out.add(TextOp(xPt, baselineYPt, shape(line, w), w, fontSizePt, color))
            return
        }
        var penX = xPt
        val run = StringBuilder()
        fun flushRun() {
            if (run.isEmpty()) return
            val text = run.toString()
            out.add(TextOp(penX, baselineYPt, shape(text, w), w, fontSizePt, color))
            penX += widthOfPt(shape(text, w), w, fontSizePt)
            run.setLength(0)
        }
        for (cp in line.toCodePoints()) when (val c = classify(cp, w)) {
            is Glyph -> appendCodePoint(run, cp)
            ZeroWidth -> {} // variation selector / ZWJ / skin-tone modifier: no glyph, no advance
            is Emoji -> {
                flushRun()
                penX += placeEmoji(c.gid, fontSizePt, penX, baselineYPt, out)
            }
        }
        flushRun()
    }

    /** Places one emoji bitmap and returns the horizontal advance consumed (0 if it can't be drawn). */
    private fun placeEmoji(gid: Int, fontSizePt: Int, penX: Int, baselineYPt: Int, out: MutableList<DrawOp>): Int {
        val img = emojiImage(gid) ?: return 0
        val advance = emojiAdvancePt(gid, fontSizePt)
        val side = fontSizePt
        val left = penX + (advance - side) / 2
        val top = baselineYPt - (side * 4) / 5 // ≈0.8em above the baseline, ≈0.2em below
        out.add(ImageOp(left, top, side, side, img.globalIndex, img.intrinsicW, img.intrinsicH, PhotoFit.Contain))
        return advance
    }

    /** Decodes (once, cached) the emoji glyph's color bitmap into an embeddable image. */
    private fun emojiImage(gid: Int): EmojiImg? = emojiGlobalIndex.getOrPut(gid) {
        val png = emoji!!.colorPng(gid) ?: return null
        val decoded = runCatching { decodePng(png) }.getOrNull() ?: return null
        emojiImages.add(PngImageData(decoded.width, decoded.height, decoded.rgb, decoded.alpha))
        EmojiImg(baseImageIndex + emojiImages.size - 1, decoded.width, decoded.height)
    }

    // --- emoji classification --------------------------------------------------------------------

    private sealed interface CodePointKind
    private object Glyph : CodePointKind        // render with the text face
    private object ZeroWidth : CodePointKind    // joiner / selector / modifier: drop it
    private class Emoji(val gid: Int) : CodePointKind

    /** Decides how a code point renders: text glyph, color emoji, or a dropped zero-width control. */
    private fun classify(cp: Int, w: FontWeight): CodePointKind {
        if (isEmojiZeroWidth(cp)) return ZeroWidth
        if (fontFor(w).gidForCodePoint(cp) != 0) return Glyph // text face has it → keep as text
        val ef = emoji ?: return Glyph
        val g = ef.gidForCodePoint(cp)
        return if (g != 0 && ef.colorPng(g) != null) Emoji(g) else Glyph // else fall back to .notdef
    }

    /** U+FE0E/FE0F variation selectors, U+200D ZWJ, and the skin-tone modifiers U+1F3FB–1F3FF.
     *  Without GSUB the engine can't form ZWJ/modifier sequences, so these are dropped (the base
     *  emoji still renders) rather than shown as their own stray glyph/swatch. */
    private fun isEmojiZeroWidth(cp: Int): Boolean =
        cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp in 0x1F3FB..0x1F3FF

    private fun emojiAdvancePt(gid: Int, fontSizePt: Int): Int {
        val ef = emoji!!
        val a = scaleAdvance(ef.advanceWidth(gid), ef.unitsPerEm, fontSizePt)
        return if (a < fontSizePt) fontSizePt else a // at least one em of horizontal space
    }

    private fun scaleAdvance(advanceUnits: Int, upm: Int, fontSizePt: Int): Int =
        ((advanceUnits.toLong() * fontSizePt + upm / 2) / upm).toInt()

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v ushr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    fun usedWeights(): List<FontWeight> = buildList {
        if (usedRegular.isNotEmpty()) add(FontWeight.Normal)
        if (usedBold.isNotEmpty()) add(FontWeight.Bold)
    }

    fun usedGids(w: FontWeight): Set<Int> = usedFor(w)
    fun gidToCp(w: FontWeight): Map<Int, Int> = gidMapFor(w)
}
