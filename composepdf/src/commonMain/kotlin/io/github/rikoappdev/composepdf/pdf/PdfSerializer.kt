package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.HorizontalAlignment
import io.github.rikoappdev.composepdf.MPT_PER_PT
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PdfImageSource
import io.github.rikoappdev.composepdf.PdfSink
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.fmtMpt
import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.halfTrunc
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.util.ByteBuf
import io.github.rikoappdev.composepdf.util.fmtNum
import io.github.rikoappdev.composepdf.util.toHex4
import kotlin.math.roundToInt

/** An image to embed in the PDF: either a JPEG (raw /DCTDecode) or a decoded PNG (/FlateDecode). */
internal sealed interface EmbeddedImage {
    val width: Int
    val height: Int
}

/**
 * A JPEG ready to embed via /DCTDecode (raw bytes, no re-encode). [components]: 1=gray, 3=RGB.
 *
 * The payload is **not** held here — only its measured [sizeBytes] and the [source] it can be pulled
 * from. That is what makes a photo-heavy document bounded in memory: the DSL reads the file once to
 * learn `(width, height, components, size)`, drops the bytes, and the serializer pulls each photo back
 * exactly when its object is written. A `ByteArray` handed to `image(bytes, …)` simply becomes a
 * source that returns it, so there is one path, not two.
 */
internal class JpegImage(
    override val width: Int,
    override val height: Int,
    val components: Int,
    val sizeBytes: Int,
    val source: PdfImageSource,
) : EmbeddedImage

/**
 * A decoded PNG: 8-bit RGB pixels (deflated into a /DeviceRGB image) plus an optional 8-bit alpha
 * plane (deflated into a /DeviceGray /SMask). Both planes are FlateDecode-compressed via the same
 * pure-Kotlin [zlibCompress] used for content/font streams, so the bytes are identical per platform.
 */
internal class PngImageData(
    override val width: Int,
    override val height: Int,
    val rgb: ByteArray,
    val alpha: ByteArray?,
) : EmbeddedImage

/**
 * A vector image (imported VectorDrawable/SVG) compiled to a Form XObject: a content stream of PDF
 * path operators plus its own resource dictionary. [width]/[height] are the viewport (= the form's
 * BBox and the intrinsic size used for aspect-fit). The content is FlateDecode-compressed like other
 * streams. Drawn with `Do`, scaled by the place matrix — defined once, reused on every page.
 */
internal class VectorForm(
    override val width: Int,
    override val height: Int,
    val content: ByteArray,
    val resources: String,
    /** Kept so the on-screen preview can flatten the same paths to a Compose Canvas. */
    val model: io.github.rikoappdev.composepdf.vector.VectorModel,
) : EmbeddedImage

/**
 * Turns laid-out [pages] (top-left-origin draw ops) + the [book]'s used fonts + [images] into a
 * PDF byte stream. Flips y to PDF's bottom-left origin. Text/content streams, the subset font
 * program and the ToUnicode CMap are FlateDecode-compressed; JPEG images stay as raw /DCTDecode.
 */
internal fun serializePdf(
    pages: List<Page>,
    book: FontBook,
    images: List<EmbeddedImage>,
    onProgress: ((Float) -> Unit)? = null,
): ByteArray {
    val (doc, catalog) = buildDoc(pages, book, images, onProgress)
    val bytes = doc.build(rootObj = catalog)
    onProgress?.invoke(1f)
    return bytes
}

/** Streaming twin of [serializePdf]: identical bytes, written straight into [sink]. */
internal fun serializePdfTo(
    sink: PdfSink,
    pages: List<Page>,
    book: FontBook,
    images: List<EmbeddedImage>,
    onProgress: ((Float) -> Unit)? = null,
) {
    val (doc, catalog) = buildDoc(pages, book, images, onProgress)
    doc.writeTo(rootObj = catalog, sink = sink)
    onProgress?.invoke(1f)
}

/** Builds the complete object list; the caller picks the transport ([PdfDoc.build] or [PdfDoc.writeTo]). */
private fun buildDoc(
    pages: List<Page>,
    book: FontBook,
    images: List<EmbeddedImage>,
    onProgress: ((Float) -> Unit)?,
): Pair<PdfDoc, Int> {
    val doc = PdfDoc()
    val catalog = doc.reserve()
    val pagesObj = doc.reserve()

    // Embed each used face once (Regular, Bold, then Medium); map weight -> (resourceName, objNum).
    val fontRes = LinkedHashMap<FontWeight, Pair<String, Int>>()
    var fi = 0
    for (w in book.usedWeights()) {
        val tag = when (w) {
            FontWeight.Bold -> "CPDFBD"
            FontWeight.Medium -> "CPDFMD"
            FontWeight.Normal -> "CPDFRG"
        }
        val num = emitType0Font(doc, book.fontFor(w), book.usedGids(w), book.gidToCp(w), "$tag+NotoSans")
        fontRes[w] = "F$fi" to num
        fi++
    }

    val imgRes = ArrayList<Pair<String, Int>>()
    for ((i, img) in images.withIndex()) {
        val num = when (img) {
            is JpegImage -> {
                val colorSpace = if (img.components == 1) "/DeviceGray" else "/DeviceRGB"
                // Pull the JPEG only while its object is being written, then let it go. Copying the
                // payload into the object list doubled peak memory; holding every photo for the whole
                // serialization made the peak proportional to the photo count. Neither happens now —
                // the emitted bytes are identical either way, /Length comes from the measured size.
                doc.addStreamLazy(
                    "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode",
                    img.sizeBytes,
                ) { img.source.readBytes() }
            }
            is PngImageData -> {
                // Optional soft mask: a single-channel /DeviceGray image deflated and referenced via /SMask.
                val smaskRef = img.alpha?.let { alpha ->
                    val maskNum = doc.add(
                        streamObject(
                            "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                                "/ColorSpace /DeviceGray /BitsPerComponent 8",
                            alpha,
                            compress = true,
                        )
                    )
                    " /SMask $maskNum 0 R"
                } ?: ""
                doc.add(
                    streamObject(
                        "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8$smaskRef",
                        img.rgb,
                        compress = true,
                    )
                )
            }
            is VectorForm -> {
                doc.add(
                    streamObject(
                        "/Type /XObject /Subtype /Form /FormType 1 /BBox [0 0 ${img.width} ${img.height}] " +
                            "/Matrix [1 0 0 1 0 0] /Resources ${img.resources}",
                        img.content,
                        compress = true,
                    )
                )
            }
        }
        imgRes.add("Im$i" to num)
    }

    val fontEntries = fontRes.values.joinToString(" ") { "/${it.first} ${it.second} 0 R" }
    val imgEntries = imgRes.joinToString(" ") { "/${it.first} ${it.second} 0 R" }
    val resources = "<< /Font << $fontEntries >> /XObject << $imgEntries >> >>"

    val pageNums = ArrayList<Int>()
    val total = pages.size
    for ((index, page) in pages.withIndex()) {
        val content = doc.add(streamObject("", buildContent(page, fontRes, imgRes), compress = true))
        pageNums.add(
            doc.addDict(
                "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${page.widthPt} ${page.heightPt}] " +
                    "/Resources $resources /Contents $content 0 R >>"
            )
        )
        // Per-page progress, scaled to ≤ 0.99 so the final assembly keeps a visible tail.
        onProgress?.invoke((index + 1).toFloat() / total * 0.99f)
    }
    doc.setDict(pagesObj, "<< /Type /Pages /Kids [${pageNums.joinToString(" ") { "$it 0 R" }}] /Count ${pageNums.size} >>")
    doc.setDict(catalog, "<< /Type /Catalog /Pages $pagesObj 0 R >>")
    return doc to catalog
}

/**
 * Draw size, in millipoints, of an image fitted to [sizePt] points: whole points for a box whose
 * sides are whole points (exactly the 0.10.0 rounding), 0.001 pt for a fractional box — so a cover
 * image still reaches every edge of a box like 186.4 pt instead of stopping 0.4 pt short.
 */
private fun imageDrawMpt(sizePt: Double, wholeBox: Boolean): Int =
    if (wholeBox) sizePt.roundToInt() * MPT_PER_PT else (sizePt * MPT_PER_PT).roundToInt()

private fun buildContent(
    page: Page,
    fontRes: Map<FontWeight, Pair<String, Int>>,
    imgRes: List<Pair<String, Int>>,
): ByteArray {
    val b = ByteBuf(1024)
    val h = page.heightPt * MPT_PER_PT
    for (op in page.ops) when (op) {
        is RectOp -> {
            val yPdf = h - (op.yMpt + op.hMpt)
            val path = rectPath(op.xMpt, yPdf, op.wMpt, op.hMpt, op.cornerRadiusMpt)
            op.fill?.let { b.ascii("${fill(it)}$path f\n") }
            op.stroke?.let { b.ascii("${stroke(it)}${fmtMpt(op.strokeWidthMpt)} w\n$path S\n") }
        }
        is TextOp -> {
            val resName = fontRes.getValue(op.weight).first
            b.ascii(fill(op.color))
            b.ascii("BT\n/$resName ${fmtMpt(op.fontSizeMpt)} Tf\n${fmtMpt(op.xMpt)} ${fmtMpt(h - op.baselineYMpt)} Td\n<")
            for (g in op.gids) b.ascii(g.toHex4())
            b.ascii("> Tj\nET\n")
        }
        is ImageOp -> {
            val name = imgRes[op.imageIndex].first
            val boxYPdf = h - (op.yMpt + op.hMpt)
            val wPt = op.wMpt / MPT_PER_PT.toDouble()
            val hPt = op.hMpt / MPT_PER_PT.toDouble()
            val iw = if (op.intrinsicW > 0) op.intrinsicW.toDouble() else wPt
            val ih = if (op.intrinsicH > 0) op.intrinsicH.toDouble() else hPt
            val coverS = maxOf(wPt / iw, hPt / ih)
            val containS = minOf(wPt / iw, hPt / ih)
            val useCover = when (op.fit) {
                PhotoFit.Cover -> true
                PhotoFit.Contain -> false
                // Smart: contain, unless the contained image would fill < 25% of a cell axis (an
                // extreme aspect ratio) — then crop-to-fill so it isn't a thin sliver.
                PhotoFit.Smart -> minOf(iw * containS / wPt, ih * containS / hPt) < 0.25
            }
            val s = if (useCover) coverS else containS
            val wholeBox = op.wMpt % MPT_PER_PT == 0 && op.hMpt % MPT_PER_PT == 0
            val dw = imageDrawMpt(iw * s, wholeBox)
            val dh = imageDrawMpt(ih * s, wholeBox)
            // Centring: truncated to whole points when the box and the draw size are whole (the 0.10.0
            // offsets), else to 0.001 pt, so a fractional box centres its image exactly.
            val wholeDraw = wholeBox && dw % MPT_PER_PT == 0 && dh % MPT_PER_PT == 0
            fun half(free: Int) = if (wholeDraw) halfTrunc(free) else free / 2
            val ox = op.xMpt + when (op.align) {
                HorizontalAlignment.Start -> 0
                HorizontalAlignment.Center -> half(op.wMpt - dw)
                HorizontalAlignment.End -> op.wMpt - dw
            }
            val oy = boxYPdf + half(op.hMpt - dh)
            val place = "${fmtMpt(dw)} 0 0 ${fmtMpt(dh)} ${fmtMpt(ox)} ${fmtMpt(oy)} cm\n/$name Do\nQ\n"
            if (useCover) {
                b.ascii("q\n${fmtMpt(op.xMpt)} ${fmtMpt(boxYPdf)} ${fmtMpt(op.wMpt)} ${fmtMpt(op.hMpt)} re W n\n$place")
            } else {
                b.ascii("q\n$place")
            }
        }
        is VectorOp -> {
            val name = imgRes[op.imageIndex].first
            val boxYPdf = h - (op.yMpt + op.hMpt)
            val xPt = op.xMpt / MPT_PER_PT.toDouble()
            val wPt = op.wMpt / MPT_PER_PT.toDouble()
            val hPt = op.hMpt / MPT_PER_PT.toDouble()
            val vw = if (op.intrinsicW > 0) op.intrinsicW.toDouble() else wPt
            val vh = if (op.intrinsicH > 0) op.intrinsicH.toDouble() else hPt
            val coverS = maxOf(wPt / vw, hPt / vh)
            val containS = minOf(wPt / vw, hPt / vh)
            val useCover = when (op.fit) {
                PhotoFit.Cover -> true
                PhotoFit.Contain -> false
                PhotoFit.Smart -> minOf(vw * containS / wPt, vh * containS / hPt) < 0.25
            }
            val s = if (useCover) coverS else containS
            val dw = vw * s
            val dh = vh * s
            val ox = xPt + when (op.align) {
                HorizontalAlignment.Start -> 0.0
                HorizontalAlignment.Center -> (wPt - dw) / 2
                HorizontalAlignment.End -> wPt - dw
            }
            val oy = boxYPdf / MPT_PER_PT.toDouble() + (hPt - dh) / 2
            // The form lives in its own BBox [0 0 vw vh]; scale by dw/vw, dh/vh to the target box.
            val sx = dw / vw
            val sy = dh / vh
            val place = "${fmtNum(sx)} 0 0 ${fmtNum(sy)} ${fmtNum(ox)} ${fmtNum(oy)} cm\n/$name Do\nQ\n"
            if (useCover) {
                b.ascii("q\n${fmtMpt(op.xMpt)} ${fmtMpt(boxYPdf)} ${fmtMpt(op.wMpt)} ${fmtMpt(op.hMpt)} re W n\n$place")
            } else {
                b.ascii("q\n$place")
            }
        }
    }
    return b.toByteArray()
}

/**
 * A rectangle path in PDF user space (millipoints in, PDF numbers out); rounds the corners when
 * [r] > 0 (Bézier quarter-circles). The radius is clamped to half the (whole-point truncated) width
 * and height. The control-point offset is rounded to whole points when that radius is a whole point
 * (the 0.10.0 path), else to 0.001 pt — so a small fractional radius (under 0.905 pt) still curves
 * instead of collapsing to a chamfer.
 */
private fun rectPath(x: Int, y: Int, w: Int, h: Int, r: Int): String {
    if (r <= 0) return "${fmtMpt(x)} ${fmtMpt(y)} ${fmtMpt(w)} ${fmtMpt(h)} re"
    val rr = minOf(r, halfTrunc(w), halfTrunc(h))
    val ck = if (rr % MPT_PER_PT == 0) ((rr / MPT_PER_PT.toDouble()) * 0.5523).roundToInt() * MPT_PER_PT else (rr * 0.5523).roundToInt()
    val xr = x + rr; val xw = x + w; val xwr = x + w - rr
    val yr = y + rr; val yh = y + h; val yhr = y + h - rr
    fun f(v: Int) = fmtMpt(v)
    return "${f(xr)} ${f(y)} m ${f(xwr)} ${f(y)} l ${f(xwr + ck)} ${f(y)} ${f(xw)} ${f(yr - ck)} ${f(xw)} ${f(yr)} c " +
        "${f(xw)} ${f(yhr)} l ${f(xw)} ${f(yhr + ck)} ${f(xwr + ck)} ${f(yh)} ${f(xwr)} ${f(yh)} c " +
        "${f(xr)} ${f(yh)} l ${f(xr - ck)} ${f(yh)} ${f(x)} ${f(yhr + ck)} ${f(x)} ${f(yhr)} c " +
        "${f(x)} ${f(yr)} l ${f(x)} ${f(yr - ck)} ${f(xr - ck)} ${f(y)} ${f(xr)} ${f(y)} c h"
}

/** PDF color component as a deterministic "0.ddd"/"1" decimal string (value/255). */
private fun comp(c: Int): String {
    val m = (c * 1000 + 127) / 255
    return if (m >= 1000) "1" else "0." + m.toString().padStart(3, '0')
}

private fun fill(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} rg\n"
private fun stroke(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} RG\n"
