package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.FontWeight

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
}
