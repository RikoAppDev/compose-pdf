package io.github.rikoappdev.composepdf.render

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.HorizontalAlignment

/**
 * Low-level positioned draw operations produced by the layout engine and consumed by the
 * serializer. Coordinates use a **top-left origin**, in **millipoints** (1 pt = 1000 mpt; y grows
 * downward); the serializer flips to PDF's bottom-left origin. Keeping ops integer (fixed point) +
 * absolute is what makes output identical across platforms.
 */
internal sealed interface DrawOp

/** A run of already-shaped glyphs drawn with its baseline at (x, baselineY). */
internal class TextOp(
    val xMpt: Int,
    val baselineYMpt: Int,
    val gids: IntArray,
    val weight: FontWeight,
    val fontSizeMpt: Int,
    val color: PdfColor,
) : DrawOp

/** A filled and/or stroked rectangle. Top-left at (x, y). [cornerRadiusMpt] > 0 rounds the corners. */
internal class RectOp(
    val xMpt: Int,
    val yMpt: Int,
    val wMpt: Int,
    val hMpt: Int,
    val fill: PdfColor?,
    val stroke: PdfColor?,
    val strokeWidthMpt: Int,
    val cornerRadiusMpt: Int = 0,
) : DrawOp

/**
 * Draws image [imageIndex] into the box with top-left at (x, y). [intrinsicW]/[intrinsicH] are the
 * JPEG's pixel dimensions; [fit] decides cover-crop, contain-fit, or smart (contain unless an
 * extreme aspect would shrink it to a sliver).
 */
internal class ImageOp(
    val xMpt: Int,
    val yMpt: Int,
    val wMpt: Int,
    val hMpt: Int,
    val imageIndex: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
    /** Horizontal placement of the fitted image inside its box (Start/Center/End). */
    val align: HorizontalAlignment = HorizontalAlignment.Center,
) : DrawOp {
    /** This op drawing image [index] instead; every other field is kept. */
    fun withImageIndex(index: Int) = ImageOp(xMpt, yMpt, wMpt, hMpt, index, intrinsicW, intrinsicH, fit, align)
}

/**
 * Draws vector form XObject [imageIndex] into the box at (x, y). [intrinsicW]/[intrinsicH] are the
 * vector's viewport; [fit] decides cover/contain/smart just like [ImageOp]. The serializer scales
 * the form's BBox (viewport) to the chosen draw size.
 */
internal class VectorOp(
    val xMpt: Int,
    val yMpt: Int,
    val wMpt: Int,
    val hMpt: Int,
    val imageIndex: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
    /** Horizontal placement of the fitted vector inside its box (Start/Center/End). */
    val align: HorizontalAlignment = HorizontalAlignment.Center,
) : DrawOp {
    /** This op drawing form [index] instead; every other field is kept. */
    fun withImageIndex(index: Int) = VectorOp(xMpt, yMpt, wMpt, hMpt, index, intrinsicW, intrinsicH, fit, align)
}

/** A page; its size stays in whole points (the MediaBox), its ops are in millipoints. */
internal class Page(val widthPt: Int, val heightPt: Int) {
    val ops = ArrayList<DrawOp>()
}
