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
 */
internal interface TextMetrics {
    /** Shapes [text] to glyph ids for [w]; implementations may also record usage for embedding. */
    fun shape(text: String, w: FontWeight): IntArray

    /** Width of [text] in PDF points at [fontSizePt]. */
    fun measureWidthPt(text: String, w: FontWeight, fontSizePt: Int): Int

    /** Width of already-shaped [gids] in PDF points at [fontSizePt]. */
    fun widthOfPt(gids: IntArray, w: FontWeight, fontSizePt: Int): Int

    /** Ascent (positive) in PDF points at [fontSizePt]. */
    fun ascentPt(w: FontWeight, fontSizePt: Int): Int

    /**
     * Emits the draw ops for one already-wrapped [line], drawn from left edge [xPt] with its baseline
     * at [baselineYPt]. The default emits a single [TextOp] (the whole line as one glyph run); the real
     * [FontBook] overrides this to split the line into text runs interleaved with inline color-emoji
     * image ops. Width measured by [measureWidthPt] is consistent with what this places, so callers can
     * align using [measureWidthPt] and then place here.
     */
    fun placeLine(
        line: String,
        w: FontWeight,
        fontSizePt: Int,
        color: PdfColor,
        xPt: Int,
        baselineYPt: Int,
        out: MutableList<DrawOp>,
    ) {
        if (line.isEmpty()) return
        out.add(TextOp(xPt, baselineYPt, shape(line, w), w, fontSizePt, color))
    }
}
