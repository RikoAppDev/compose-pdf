package io.github.rikoappdev.composepdf.preview

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.HorizontalAlignment
import io.github.rikoappdev.composepdf.MPT_PER_PT
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PdfDocumentSpec
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.RenderImageTable
import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.fontUnitsToMpt
import io.github.rikoappdev.composepdf.layoutDocument
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.JpegImage
import io.github.rikoappdev.composepdf.pdf.PngImageData
import io.github.rikoappdev.composepdf.pdf.VectorForm
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.vector.CubicSeg
import io.github.rikoappdev.composepdf.vector.LineSeg
import io.github.rikoappdev.composepdf.vector.alphaOf
import io.github.rikoappdev.composepdf.vector.blueOf
import io.github.rikoappdev.composepdf.vector.greenOf
import io.github.rikoappdev.composepdf.vector.redOf
import kotlin.math.roundToInt

/**
 * A resolved, UI-free description of one laid-out page, ready to be painted on any 2-D canvas.
 *
 * It is produced by [previewPages], which runs the **same** layout/place pass as `render()` and then
 * converts the engine's internal draw ops into these public data ops. Coordinates use a **top-left
 * origin in PDF points** (y grows downward), exactly like the PDF before its final y-flip — so the
 * preview and the exported PDF share identical block/line/image placement (the PDF stays the source
 * of truth). This type carries no Compose dependency; the `compose-pdf-preview` artifact turns it
 * into an on-screen `@Composable`.
 */
class PreviewPage internal constructor(
    val widthPt: Int,
    val heightPt: Int,
    val ops: List<PreviewOp>,
)

sealed interface PreviewOp

/** A line of text with its baseline at ([xPt], [baselineYPt]). [colorArgb] is opaque (0xFF alpha).
 *  [glyphXPt] holds the absolute x (page points) of each code point in [text], computed from the
 *  engine's own glyph advances — so the on-screen preview can place each glyph exactly where the PDF
 *  puts it, instead of drifting on the platform font's slightly different advance widths.
 *  [fontWeight] is the face the PDF sets the run in: [FontWeight.Medium] only when a Medium face was
 *  supplied (otherwise such text is [FontWeight.Normal], exactly as in the PDF). */
class PreviewText internal constructor(
    val text: String,
    val xPt: Float,
    val baselineYPt: Float,
    val fontSizePt: Float,
    val fontWeight: FontWeight,
    val colorArgb: Int,
    val glyphXPt: FloatArray,
) : PreviewOp {
    /** `true` when the run is set in the Bold face. */
    val bold: Boolean get() = fontWeight == FontWeight.Bold
}

/** A filled and/or stroked rectangle (top-left origin); [cornerRadiusPt] > 0 rounds the corners. */
class PreviewRect internal constructor(
    val xPt: Float,
    val yPt: Float,
    val wPt: Float,
    val hPt: Float,
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val strokeWidthPt: Float,
    val cornerRadiusPt: Float,
) : PreviewOp

/** Where a [PreviewImage]'s pixels come from. */
sealed interface PreviewImageSource

/** Original encoded bytes (JPEG) — the consumer decodes them with the platform image decoder. */
class PreviewEncodedImage internal constructor(val bytes: ByteArray) : PreviewImageSource

/** Already-decoded 8-bit pixels in packed ARGB (PNG path) — the consumer wraps them in a bitmap. */
class PreviewRasterImage internal constructor(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val argb: IntArray,
) : PreviewImageSource

/**
 * An image drawn into the destination rect ([dstXPt], [dstYPt], [dstWPt], [dstHPt]) — already
 * resolved for the chosen [PhotoFit]. When [clip] is true (cover-crop) the consumer must clip to the
 * cell box ([clipXPt], [clipYPt], [clipWPt], [clipHPt]); otherwise the dst rect lies inside the box.
 */
class PreviewImage internal constructor(
    val dstXPt: Float,
    val dstYPt: Float,
    val dstWPt: Float,
    val dstHPt: Float,
    val clip: Boolean,
    val clipXPt: Float,
    val clipYPt: Float,
    val clipWPt: Float,
    val clipHPt: Float,
    val source: PreviewImageSource,
) : PreviewOp

/** One segment of a flattened vector sub-path, in absolute page points (top-left origin). */
sealed interface PreviewSeg
class PreviewMoveTo internal constructor(val xPt: Float, val yPt: Float) : PreviewSeg
class PreviewLineTo internal constructor(val xPt: Float, val yPt: Float) : PreviewSeg
class PreviewCubicTo internal constructor(
    val c1xPt: Float, val c1yPt: Float,
    val c2xPt: Float, val c2yPt: Float,
    val xPt: Float, val yPt: Float,
) : PreviewSeg
object PreviewClose : PreviewSeg

/** One painted path of a vector: its segments plus the resolved fill/stroke (alpha folded into ARGB). */
class PreviewPath internal constructor(
    val segs: List<PreviewSeg>,
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val strokeWidthPt: Float,
    val evenOdd: Boolean,
)

/** A vector image flattened (viewport → destination box) into absolute Compose-ready paths. */
class PreviewVector internal constructor(val paths: List<PreviewPath>) : PreviewOp

/**
 * Lays the document out exactly as `render()` would and returns each page as a [PreviewPage] of
 * resolved, UI-free draw ops — for an on-screen preview. The Regular + Bold (and optional Medium)
 * fonts are the same bytes passed to `render()`; without [mediumFontBytes], Medium text is laid out in
 * the Regular face, as in the PDF. Pure data; no Compose dependency.
 */
fun PdfDocumentSpec.previewPages(
    regularFontBytes: ByteArray,
    boldFontBytes: ByteArray,
    mediumFontBytes: ByteArray? = null,
): List<PreviewPage> {
    val table = RenderImageTable(this.images)
    val book = FontBook(regularFontBytes, boldFontBytes, imageTable = table, mediumBytes = mediumFontBytes)
    val result = layoutDocument(this, book, table)
    val images = result.images
    return result.pages.map { page ->
        val ops = ArrayList<PreviewOp>(page.ops.size)
        for (op in page.ops) when (op) {
            is TextOp -> ops.add(op.toPreview(book))
            is RectOp -> ops.add(op.toPreview())
            is ImageOp -> op.toPreview(images)?.let { ops.add(it) }
            is VectorOp -> op.toPreview(images)?.let { ops.add(it) }
        }
        PreviewPage(page.widthPt, page.heightPt, ops)
    }
}

private fun PdfColor.argb(): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

private fun StringBuilder.appendCp(cp: Int) {
    if (cp <= 0xFFFF) append(cp.toChar())
    else {
        val c = cp - 0x10000
        append((0xD800 + (c ushr 10)).toChar())
        append((0xDC00 + (c and 0x3FF)).toChar())
    }
}

/** A millipoint length as preview points. */
private fun pt(mpt: Int): Float = mpt / MPT_PER_PT.toFloat()

private fun TextOp.toPreview(book: FontBook): PreviewText {
    val map = book.gidToCp(weight)
    val font = book.fontFor(weight)
    val upm = font.unitsPerEm
    val sb = StringBuilder(gids.size)
    val xs = ArrayList<Float>(gids.size)
    var cumUnits = 0L
    for (g in gids) {
        // Place each glyph at the rounded CUMULATIVE advance (font units → points), NOT a running sum
        // of per-glyph rounded deltas. This matches the engine's round-once measureWidthMpt, so the run
        // ends at exactly x + measureWidthMpt; the old per-glyph rounding accumulated ~0.4pt per
        // tabular digit and pushed digit-heavy right-aligned text past its edge (preview only).
        map[g]?.let { sb.appendCp(it); xs.add(pt(xMpt + fontUnitsToMpt(cumUnits, upm, fontSizeMpt))) }
        cumUnits += font.advanceWidth(g)
    }
    return PreviewText(sb.toString(), pt(xMpt), pt(baselineYMpt), pt(fontSizeMpt), weight, color.argb(), xs.toFloatArray())
}

private fun RectOp.toPreview() =
    PreviewRect(pt(xMpt), pt(yMpt), pt(wMpt), pt(hMpt), fill?.argb(), stroke?.argb(), pt(strokeWidthMpt), pt(cornerRadiusMpt))

/** Result of fitting intrinsic [iw]×[ih] into a box, mirroring the serializer's cover/contain/smart. */
private class Placed(val ox: Double, val oy: Double, val dw: Double, val dh: Double, val cover: Boolean)

private fun place(boxX: Double, boxY: Double, boxW: Double, boxH: Double, iw: Double, ih: Double, fit: PhotoFit, align: HorizontalAlignment): Placed {
    val coverS = maxOf(boxW / iw, boxH / ih)
    val containS = minOf(boxW / iw, boxH / ih)
    val useCover = when (fit) {
        PhotoFit.Cover -> true
        PhotoFit.Contain -> false
        PhotoFit.Smart -> minOf(iw * containS / boxW, ih * containS / boxH) < 0.25
    }
    val s = if (useCover) coverS else containS
    val dw = iw * s
    val ox = boxX + when (align) {
        HorizontalAlignment.Start -> 0.0
        HorizontalAlignment.Center -> (boxW - dw) / 2
        HorizontalAlignment.End -> boxW - dw
    }
    return Placed(ox, boxY + (boxH - ih * s) / 2, dw, ih * s, useCover)
}

private fun ImageOp.toPreview(images: List<EmbeddedImage>): PreviewImage? {
    val img = images.getOrNull(imageIndex) ?: return null
    val source: PreviewImageSource = when (img) {
        // Pulled from the source, like the serializer does: the spec keeps a JPEG's dimensions, not
        // its bytes. Preview is a design-time tool over one screenful of pages, so a per-page re-read
        // is the right trade for keeping documents unbounded in size.
        is JpegImage -> PreviewEncodedImage(img.source.readBytes())
        is PngImageData -> PreviewRasterImage(img.width, img.height, pngArgb(img))
        else -> return null
    }
    val x = xMpt / MPT_PER_PT.toDouble()
    val y = yMpt / MPT_PER_PT.toDouble()
    val w = wMpt / MPT_PER_PT.toDouble()
    val h = hMpt / MPT_PER_PT.toDouble()
    val iw = if (intrinsicW > 0) intrinsicW.toDouble() else w
    val ih = if (intrinsicH > 0) intrinsicH.toDouble() else h
    val p = place(x, y, w, h, iw, ih, fit, align)
    return PreviewImage(
        p.ox.toFloat(), p.oy.toFloat(), p.dw.toFloat(), p.dh.toFloat(),
        p.cover, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), source,
    )
}

private fun pngArgb(png: PngImageData): IntArray {
    val n = png.width * png.height
    val out = IntArray(n)
    val rgb = png.rgb
    val a = png.alpha
    for (i in 0 until n) {
        val r = rgb[i * 3].toInt() and 0xFF
        val g = rgb[i * 3 + 1].toInt() and 0xFF
        val b = rgb[i * 3 + 2].toInt() and 0xFF
        val al = if (a != null) a[i].toInt() and 0xFF else 0xFF
        out[i] = (al shl 24) or (r shl 16) or (g shl 8) or b
    }
    return out
}

private fun VectorOp.toPreview(images: List<EmbeddedImage>): PreviewVector? {
    val form = images.getOrNull(imageIndex) as? VectorForm ?: return null
    val model = form.model
    val vw = model.viewportW
    val vh = model.viewportH
    if (vw <= 0.0 || vh <= 0.0) return null
    val p = place(xMpt / MPT_PER_PT.toDouble(), yMpt / MPT_PER_PT.toDouble(), wMpt / MPT_PER_PT.toDouble(), hMpt / MPT_PER_PT.toDouble(), vw, vh, fit, align)
    val s = p.dw / vw
    fun px(x: Double) = (p.ox + x * s).toFloat()
    fun py(y: Double) = (p.oy + y * s).toFloat()

    val out = ArrayList<PreviewPath>(model.paths.size)
    for (path in model.paths) {
        val fillEff = if (path.fillArgb != null) (alphaOf(path.fillArgb) / 255.0) * path.fillAlpha else 0.0
        val strokeEff = if (path.strokeArgb != null) (alphaOf(path.strokeArgb) / 255.0) * path.strokeAlpha else 0.0
        val doFill = path.fillArgb != null && fillEff > 0.0
        val doStroke = path.strokeArgb != null && strokeEff > 0.0 && path.strokeWidth > 0.0
        if ((!doFill && !doStroke) || path.subpaths.isEmpty()) continue
        val segs = ArrayList<PreviewSeg>()
        for (sub in path.subpaths) {
            segs.add(PreviewMoveTo(px(sub.start.x), py(sub.start.y)))
            for (seg in sub.segs) when (seg) {
                is LineSeg -> segs.add(PreviewLineTo(px(seg.to.x), py(seg.to.y)))
                is CubicSeg -> segs.add(
                    PreviewCubicTo(px(seg.c1.x), py(seg.c1.y), px(seg.c2.x), py(seg.c2.y), px(seg.to.x), py(seg.to.y))
                )
            }
            if (sub.closed) segs.add(PreviewClose)
        }
        out.add(
            PreviewPath(
                segs,
                if (doFill) effArgb(path.fillArgb, fillEff) else null,
                if (doStroke) effArgb(path.strokeArgb, strokeEff) else null,
                (path.strokeWidth * s).toFloat(),
                path.evenOdd,
            )
        )
    }
    return PreviewVector(out)
}

/** Folds the effective alpha (0..1) into the ARGB color's alpha byte. */
private fun effArgb(argb: Int, eff: Double): Int {
    val a = (eff * 255.0).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (redOf(argb) shl 16) or (greenOf(argb) shl 8) or blueOf(argb)
}
