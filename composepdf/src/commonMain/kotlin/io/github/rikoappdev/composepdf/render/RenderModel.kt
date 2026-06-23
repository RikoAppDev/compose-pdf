package io.github.rikoappdev.composepdf.render

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit

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

/** A filled and/or stroked rectangle. Top-left at (x, y). [cornerRadiusPt] > 0 rounds the corners. */
internal class RectOp(
    val xPt: Int,
    val yPt: Int,
    val wPt: Int,
    val hPt: Int,
    val fill: PdfColor?,
    val stroke: PdfColor?,
    val strokeWidthPt: Int,
    val cornerRadiusPt: Int = 0,
) : DrawOp

/**
 * Draws image [imageIndex] into the box with top-left at (x, y). [intrinsicW]/[intrinsicH] are the
 * JPEG's pixel dimensions; [fit] decides cover-crop, contain-fit, or smart (contain unless an
 * extreme aspect would shrink it to a sliver).
 */
internal class ImageOp(
    val xPt: Int,
    val yPt: Int,
    val wPt: Int,
    val hPt: Int,
    val imageIndex: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
) : DrawOp

/**
 * Draws vector form XObject [imageIndex] into the box at (x, y). [intrinsicW]/[intrinsicH] are the
 * vector's viewport; [fit] decides cover/contain/smart just like [ImageOp]. The serializer scales
 * the form's BBox (viewport) to the chosen draw size.
 */
internal class VectorOp(
    val xPt: Int,
    val yPt: Int,
    val wPt: Int,
    val hPt: Int,
    val imageIndex: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
) : DrawOp

internal class Page(val widthPt: Int, val heightPt: Int) {
    val ops = ArrayList<DrawOp>()
}
