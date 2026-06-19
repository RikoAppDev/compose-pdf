package io.github.rikoappdev.composepdf.render

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor

/**
 * Low-level positioned draw operations produced by the layout engine and consumed by the
 * serializer. Coordinates use a **top-left origin in PDF points** (y grows downward); the
 * serializer flips to PDF's bottom-left origin. Keeping ops integer + absolute is what makes
 * output identical across platforms.
 */
internal sealed interface DrawOp

/** A run of already-shaped glyphs drawn with its baseline at (x, baselineY). */
internal class TextOp(
    val xPt: Int,
    val baselineYPt: Int,
    val gids: IntArray,
    val weight: FontWeight,
    val fontSizePt: Int,
    val color: PdfColor,
) : DrawOp

/** A filled and/or stroked rectangle. Top-left at (x, y). */
internal class RectOp(
    val xPt: Int,
    val yPt: Int,
    val wPt: Int,
    val hPt: Int,
    val fill: PdfColor?,
    val stroke: PdfColor?,
    val strokeWidthPt: Int,
) : DrawOp

/**
 * Draws image [imageIndex] into the box with top-left at (x, y). [intrinsicW]/[intrinsicH] are the
 * JPEG's pixel dimensions; [cover] = true scales to fill the box (cropping the overflow via a clip),
 * false fits inside preserving aspect (contain).
 */
internal class ImageOp(
    val xPt: Int,
    val yPt: Int,
    val wPt: Int,
    val hPt: Int,
    val imageIndex: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val cover: Boolean,
) : DrawOp

internal class Page(val widthPt: Int, val heightPt: Int) {
    val ops = ArrayList<DrawOp>()
}
