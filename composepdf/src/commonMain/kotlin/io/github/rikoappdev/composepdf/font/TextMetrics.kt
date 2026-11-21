package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.TextOp

/**
 * The metrics the layout engine needs from a font: glyph shaping and integer width/ascent
 * measurement. [FontBook] implements this over real TrueType faces; decoupling it lets the layout
 * math be exercised by a deterministic stub in `commonTest`, which proves cross-platform identity
 * (the integer positions must come out the same on JVM, Android and iOS) without bundling fonts.
 *
 * Font sizes and results are in millipoints (1 pt = 1000 mpt). Ascent and the width of a glyph run
 * are rounded to whole points (multiples of 1000), as the engine always has. The one exception is a
 * color-emoji glyph, which advances by at least one em — the font size itself, fractional for a
 * fractional size — so a line containing emoji may measure a fractional width.
 */
internal interface TextMetrics {
    /** Shapes [text] to glyph ids for [w]; implementations may also record usage for embedding. */
    fun shape(text: String, w: FontWeight): IntArray

    /**
     * Width of [text] at [fontSizeMpt], in millipoints: each glyph run rounded to whole points, plus
     * the advance of any color emoji (at least one em, so fractional for a fractional font size).
     */
    fun measureWidthMpt(text: String, w: FontWeight, fontSizeMpt: Int): Int

    /** Width of already-shaped [gids] at [fontSizeMpt], rounded to whole points, in millipoints. */
    fun widthOfMpt(gids: IntArray, w: FontWeight, fontSizeMpt: Int): Int

    /** Ascent (positive) at [fontSizeMpt], rounded to whole points, in millipoints. */
    fun ascentMpt(w: FontWeight, fontSizeMpt: Int): Int

    /**
     * Emits the draw ops for one already-wrapped [line], drawn from left edge [xMpt] with its baseline
     * at [baselineYMpt]. The default emits a single [TextOp] (the whole line as one glyph run); the real
     * [FontBook] overrides this to split the line into text runs interleaved with inline color-emoji
     * image ops. Width measured by [measureWidthMpt] is consistent with what this places, so callers can
     * align using [measureWidthMpt] and then place here.
     */
    fun placeLine(
        line: String,
        w: FontWeight,
        fontSizeMpt: Int,
        color: PdfColor,
        xMpt: Int,
        baselineYMpt: Int,
        out: MutableList<DrawOp>,
    ) {
        if (line.isEmpty()) return
        out.add(TextOp(xMpt, baselineYMpt, shape(line, w), w, fontSizeMpt, color))
    }

    /**
     * Captures what this instance has recorded for embedding so far (used glyphs, their code points,
     * decoded emoji); calling the returned function puts exactly that back. A layout attempt that is
     * thrown away uses it so the output carries nothing of it. The default records nothing.
     */
    fun checkpoint(): () -> Unit = {}
}
