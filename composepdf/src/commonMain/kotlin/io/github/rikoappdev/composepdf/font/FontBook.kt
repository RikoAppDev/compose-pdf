package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.RenderImageTable
import io.github.rikoappdev.composepdf.fontUnitsToMpt
import io.github.rikoappdev.composepdf.halfTrunc
import io.github.rikoappdev.composepdf.image.decodePng
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.PngImageData
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.util.toCodePoints
import io.github.rikoappdev.composepdf.wholeTrunc

/**
 * Holds the Regular + Bold faces (and an optional Medium face), shapes text to glyph ids, measures
 * widths (integer font-unit math), and tracks which glyphs each face actually uses so only those are
 * embedded.
 *
 * [FontWeight.Medium] is set in the Medium face when [mediumBytes] is supplied, otherwise in the
 * Regular face (see [resolve]): such text then shapes, measures, embeds and serializes exactly like
 * [FontWeight.Normal] text.
 *
 * An optional **color-emoji face** ([emojiBytes], an Apple `sbix` or Noto `CBLC`/`CBDT` font) acts as
 * a fallback: code points the text faces can't render but the emoji face can are drawn as inline color
 * **bitmap images** instead of `.notdef` boxes. Each distinct emoji glyph is decoded once into an
 * [EmbeddedImage] appended to [imageTable]; the layout places it with an [ImageOp]. When no emoji
 * face is supplied the shaping/measurement/placement paths are byte-for-byte identical to before.
 *
 * Font sizes and lengths are in millipoints. Ascent, descent and glyph-run widths are rounded to whole
 * points; a color emoji advances by at least one em (the font size, which may be fractional).
 */
internal class FontBook(
    regularBytes: ByteArray,
    boldBytes: ByteArray,
    emojiBytes: ByteArray? = null,
    /** The render's image table: emoji bitmaps are appended to it, in discovery order. */
    private val imageTable: RenderImageTable = RenderImageTable(emptyList()),
    mediumBytes: ByteArray? = null,
) : TextMetrics {

    val regular = TrueTypeFont(regularBytes)
    val bold = TrueTypeFont(boldBytes)
    private val medium: TrueTypeFont? = mediumBytes?.let { TrueTypeFont(it) }

    /** The emoji face, kept only if it parses AND actually carries color bitmaps we can extract. */
    private val emoji: TrueTypeFont? = emojiBytes
        ?.let { runCatching { TrueTypeFont(it) }.getOrNull() }
        ?.takeIf { it.hasColorBitmaps }

    val usedRegular = HashSet<Int>()
    val usedBold = HashSet<Int>()
    private val usedMedium = HashSet<Int>()
    val gidToCpRegular = HashMap<Int, Int>()
    val gidToCpBold = HashMap<Int, Int>()
    private val gidToCpMedium = HashMap<Int, Int>()

    private val emojiGlobalIndex = HashMap<Int, EmojiImg>() // emoji gid -> placed image (deduped per glyph)

    private class EmojiImg(val globalIndex: Int, val intrinsicW: Int, val intrinsicH: Int)

    /** The weight whose face actually sets [w]: Medium without a Medium face falls back to Normal. */
    fun resolve(w: FontWeight): FontWeight = if (w == FontWeight.Medium && medium == null) FontWeight.Normal else w

    fun fontFor(w: FontWeight): TrueTypeFont = when (resolve(w)) {
        FontWeight.Bold -> bold
        FontWeight.Medium -> medium!!
        FontWeight.Normal -> regular
    }

    private fun usedFor(w: FontWeight) = when (resolve(w)) {
        FontWeight.Bold -> usedBold
        FontWeight.Medium -> usedMedium
        FontWeight.Normal -> usedRegular
    }

    private fun gidMapFor(w: FontWeight) = when (resolve(w)) {
        FontWeight.Bold -> gidToCpBold
        FontWeight.Medium -> gidToCpMedium
        FontWeight.Normal -> gidToCpRegular
    }

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

    /**
     * Width of [text] at [fontSizeMpt] (does not record usage): each glyph run rounded to whole points,
     * plus each emoji's advance — at least one em, so fractional for a fractional font size.
     */
    override fun measureWidthMpt(text: String, w: FontWeight, fontSizeMpt: Int): Int {
        val font = fontFor(w); val upm = font.unitsPerEm
        if (emoji == null) {
            var sum = 0L
            for (cp in text.toCodePoints()) sum += font.advanceWidth(font.gidForCodePoint(cp))
            return fontUnitsToMpt(sum, upm, fontSizeMpt)
        }
        // Emoji-aware: mirror placeLine exactly. A maximal run of text glyphs is drawn as ONE TextOp and
        // advances by widthOfMpt(run) — i.e. font units summed, then rounded to points ONCE. Emoji consume
        // their bitmap advance; joiners/selectors consume nothing. Rounding each glyph separately here (the
        // old code) over-counts the run by up to ~0.5pt per glyph versus what is actually drawn, which then
        // shifts right-aligned text left by that error. Accumulate units and flush round-once per run.
        var widthMpt = 0
        var runUnits = 0L
        fun flushRun() {
            if (runUnits == 0L) return
            widthMpt += fontUnitsToMpt(runUnits, upm, fontSizeMpt)
            runUnits = 0L
        }
        for (cp in text.toCodePoints()) {
            when (val k = classify(cp, w)) {
                is Glyph -> runUnits += font.advanceWidth(font.gidForCodePoint(cp))
                is Emoji -> { flushRun(); widthMpt += emojiAdvanceMpt(k.gid, fontSizeMpt) }
                ZeroWidth -> {}
            }
        }
        flushRun()
        return widthMpt
    }

    /** Width of already-shaped [gids] at [fontSizeMpt], rounded to whole points. */
    override fun widthOfMpt(gids: IntArray, w: FontWeight, fontSizeMpt: Int): Int {
        val font = fontFor(w)
        var sum = 0L
        for (g in gids) sum += font.advanceWidth(g)
        return fontUnitsToMpt(sum, font.unitsPerEm, fontSizeMpt)
    }

    /** Ascent (positive) at [fontSizeMpt], rounded to whole points. */
    override fun ascentMpt(w: FontWeight, fontSizeMpt: Int): Int {
        val font = fontFor(w)
        return fontUnitsToMpt(font.ascender.toLong(), font.unitsPerEm, fontSizeMpt)
    }

    /** Descent magnitude (positive) at [fontSizeMpt], rounded to whole points. */
    fun descentMpt(w: FontWeight, fontSizeMpt: Int): Int {
        val font = fontFor(w)
        return fontUnitsToMpt(-font.descender.toLong(), font.unitsPerEm, fontSizeMpt)
    }

    override fun placeLine(
        line: String,
        w: FontWeight,
        fontSizeMpt: Int,
        color: PdfColor,
        xMpt: Int,
        baselineYMpt: Int,
        out: MutableList<DrawOp>,
    ) {
        if (line.isEmpty()) return
        // The op carries the weight of the face that actually sets it (Medium → Normal without a Medium face).
        val face = resolve(w)
        // Fast path: no emoji face → the whole line is one glyph run, exactly as before.
        if (emoji == null) {
            out.add(TextOp(xMpt, baselineYMpt, shape(line, w), face, fontSizeMpt, color))
            return
        }
        var penX = xMpt
        val run = StringBuilder()
        fun flushRun() {
            if (run.isEmpty()) return
            val text = run.toString()
            out.add(TextOp(penX, baselineYMpt, shape(text, w), face, fontSizeMpt, color))
            penX += widthOfMpt(shape(text, w), w, fontSizeMpt)
            run.setLength(0)
        }
        for (cp in line.toCodePoints()) when (val c = classify(cp, w)) {
            is Glyph -> appendCodePoint(run, cp)
            ZeroWidth -> {} // variation selector / ZWJ / skin-tone modifier: no glyph, no advance
            is Emoji -> {
                flushRun()
                penX += placeEmoji(c.gid, fontSizeMpt, penX, baselineYMpt, out)
            }
        }
        flushRun()
    }

    /** Places one emoji bitmap and returns the horizontal advance consumed (0 if it can't be drawn). */
    private fun placeEmoji(gid: Int, fontSizeMpt: Int, penX: Int, baselineYMpt: Int, out: MutableList<DrawOp>): Int {
        val img = emojiImage(gid) ?: return 0
        val advance = emojiAdvanceMpt(gid, fontSizeMpt)
        val side = fontSizeMpt
        val left = penX + halfTrunc(advance - side)
        val top = baselineYMpt - wholeTrunc((side.toLong() * 4 / 5).toInt()) // ≈0.8em above the baseline, ≈0.2em below
        out.add(ImageOp(left, top, side, side, img.globalIndex, img.intrinsicW, img.intrinsicH, PhotoFit.Contain))
        return advance
    }

    /** Decodes (once, cached) the emoji glyph's color bitmap into an embeddable image. */
    private fun emojiImage(gid: Int): EmojiImg? = emojiGlobalIndex.getOrPut(gid) {
        val png = emoji!!.colorPng(gid) ?: return null
        val decoded = runCatching { decodePng(png) }.getOrNull() ?: return null
        val index = imageTable.add(PngImageData(decoded.width, decoded.height, decoded.rgb, decoded.alpha))
        EmojiImg(index, decoded.width, decoded.height)
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

    /** The emoji's advance rounded to whole points, but at least [fontSizeMpt] (which may be fractional). */
    private fun emojiAdvanceMpt(gid: Int, fontSizeMpt: Int): Int {
        val ef = emoji!!
        val a = fontUnitsToMpt(ef.advanceWidth(gid).toLong(), ef.unitsPerEm, fontSizeMpt)
        return if (a < fontSizeMpt) fontSizeMpt else a // at least one em of horizontal space
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v ushr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    /** Faces that set at least one glyph, in embedding order: Regular, Bold, then Medium. */
    fun usedWeights(): List<FontWeight> = buildList {
        if (usedRegular.isNotEmpty()) add(FontWeight.Normal)
        if (usedBold.isNotEmpty()) add(FontWeight.Bold)
        if (usedMedium.isNotEmpty()) add(FontWeight.Medium)
    }

    fun usedGids(w: FontWeight): Set<Int> = usedFor(w)
    fun gidToCp(w: FontWeight): Map<Int, Int> = gidMapFor(w)
}
