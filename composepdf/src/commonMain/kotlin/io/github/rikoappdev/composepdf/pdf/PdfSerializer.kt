package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.HorizontalAlignment
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.font.FontBook
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

/** A JPEG ready to embed via /DCTDecode (raw bytes, no re-encode). [components]: 1=gray, 3=RGB. */
internal class JpegImage(
    override val width: Int,
    override val height: Int,
    val components: Int,
    val bytes: ByteArray,
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
    val doc = PdfDoc()
    val catalog = doc.reserve()
    val pagesObj = doc.reserve()

    // Embed each used face once; map weight -> (resourceName, objNum).
    val fontRes = LinkedHashMap<FontWeight, Pair<String, Int>>()
    var fi = 0
    for (w in book.usedWeights()) {
        val tag = if (w == FontWeight.Bold) "CPDFBD" else "CPDFRG"
        val num = emitType0Font(doc, book.fontFor(w), book.usedGids(w), book.gidToCp(w), "$tag+NotoSans")
        fontRes[w] = "F$fi" to num
        fi++
    }

    val imgRes = ArrayList<Pair<String, Int>>()
    for ((i, img) in images.withIndex()) {
        val num = when (img) {
            is JpegImage -> {
                val colorSpace = if (img.components == 1) "/DeviceGray" else "/DeviceRGB"
                doc.add(
                    streamObject(
                        "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                            "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode",
                        img.bytes,
                    )
                )
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
    val bytes = doc.build(rootObj = catalog)
    onProgress?.invoke(1f)
    return bytes
}

private fun buildContent(
    page: Page,
    fontRes: Map<FontWeight, Pair<String, Int>>,
    imgRes: List<Pair<String, Int>>,
): ByteArray {
    val b = ByteBuf(1024)
    val h = page.heightPt
    for (op in page.ops) when (op) {
        is RectOp -> {
            val yPdf = h - (op.yPt + op.hPt)
            val path = rectPath(op.xPt, yPdf, op.wPt, op.hPt, op.cornerRadiusPt)
            op.fill?.let { b.ascii("${fill(it)}$path f\n") }
            op.stroke?.let { b.ascii("${stroke(it)}${op.strokeWidthPt} w\n$path S\n") }
        }
        is TextOp -> {
            val resName = fontRes.getValue(op.weight).first
            b.ascii(fill(op.color))
            b.ascii("BT\n/$resName ${op.fontSizePt} Tf\n${op.xPt} ${h - op.baselineYPt} Td\n<")
            for (g in op.gids) b.ascii(g.toHex4())
            b.ascii("> Tj\nET\n")
        }
        is ImageOp -> {
            val name = imgRes[op.imageIndex].first
            val boxYPdf = h - (op.yPt + op.hPt)
            val iw = if (op.intrinsicW > 0) op.intrinsicW else op.wPt
            val ih = if (op.intrinsicH > 0) op.intrinsicH else op.hPt
            val coverS = maxOf(op.wPt.toDouble() / iw, op.hPt.toDouble() / ih)
            val containS = minOf(op.wPt.toDouble() / iw, op.hPt.toDouble() / ih)
            val useCover = when (op.fit) {
                PhotoFit.Cover -> true
                PhotoFit.Contain -> false
                // Smart: contain, unless the contained image would fill < 25% of a cell axis (an
                // extreme aspect ratio) — then crop-to-fill so it isn't a thin sliver.
                PhotoFit.Smart -> minOf(iw * containS / op.wPt, ih * containS / op.hPt) < 0.25
            }
            val s = if (useCover) coverS else containS
            val dw = (iw * s).roundToInt()
            val dh = (ih * s).roundToInt()
            val ox = op.xPt + when (op.align) {
                HorizontalAlignment.Start -> 0
                HorizontalAlignment.Center -> (op.wPt - dw) / 2
                HorizontalAlignment.End -> op.wPt - dw
            }
            val oy = boxYPdf + (op.hPt - dh) / 2
            if (useCover) {
                b.ascii("q\n${op.xPt} $boxYPdf ${op.wPt} ${op.hPt} re W n\n$dw 0 0 $dh $ox $oy cm\n/$name Do\nQ\n")
            } else {
                b.ascii("q\n$dw 0 0 $dh $ox $oy cm\n/$name Do\nQ\n")
            }
        }
        is VectorOp -> {
            val name = imgRes[op.imageIndex].first
            val boxYPdf = h - (op.yPt + op.hPt)
            val vw = if (op.intrinsicW > 0) op.intrinsicW else op.wPt
            val vh = if (op.intrinsicH > 0) op.intrinsicH else op.hPt
            val coverS = maxOf(op.wPt.toDouble() / vw, op.hPt.toDouble() / vh)
            val containS = minOf(op.wPt.toDouble() / vw, op.hPt.toDouble() / vh)
            val useCover = when (op.fit) {
                PhotoFit.Cover -> true
                PhotoFit.Contain -> false
                PhotoFit.Smart -> minOf(vw * containS / op.wPt, vh * containS / op.hPt) < 0.25
            }
            val s = if (useCover) coverS else containS
            val dw = vw * s
            val dh = vh * s
            val ox = op.xPt + when (op.align) {
                HorizontalAlignment.Start -> 0.0
                HorizontalAlignment.Center -> (op.wPt - dw) / 2
                HorizontalAlignment.End -> op.wPt - dw
            }
            val oy = boxYPdf + (op.hPt - dh) / 2
            // The form lives in its own BBox [0 0 vw vh]; scale by dw/vw, dh/vh to the target box.
            val sx = dw / vw
            val sy = dh / vh
            if (useCover) {
                b.ascii("q\n${op.xPt} $boxYPdf ${op.wPt} ${op.hPt} re W n\n${fmtNum(sx)} 0 0 ${fmtNum(sy)} ${fmtNum(ox)} ${fmtNum(oy)} cm\n/$name Do\nQ\n")
            } else {
                b.ascii("q\n${fmtNum(sx)} 0 0 ${fmtNum(sy)} ${fmtNum(ox)} ${fmtNum(oy)} cm\n/$name Do\nQ\n")
            }
        }
    }
    return b.toByteArray()
}

/** A rectangle path in PDF user space; rounds the corners when [r] > 0 (Bézier quarter-circles). */
private fun rectPath(x: Int, y: Int, w: Int, h: Int, r: Int): String {
    if (r <= 0) return "$x $y $w $h re"
    val rr = minOf(r, w / 2, h / 2)
    val ck = (rr * 0.5523).roundToInt()
    val xr = x + rr; val xw = x + w; val xwr = x + w - rr
    val yr = y + rr; val yh = y + h; val yhr = y + h - rr
    return "$xr $y m $xwr $y l ${xwr + ck} $y $xw ${yr - ck} $xw $yr c " +
        "$xw $yhr l $xw ${yhr + ck} ${xwr + ck} $yh $xwr $yh c " +
        "$xr $yh l ${xr - ck} $yh $x ${yhr + ck} $x $yhr c " +
        "$x $yr l $x ${yr - ck} ${xr - ck} $y $xr $y c h"
}

/** PDF color component as a deterministic "0.ddd"/"1" decimal string (value/255). */
private fun comp(c: Int): String {
    val m = (c * 1000 + 127) / 255
    return if (m >= 1000) "1" else "0." + m.toString().padStart(3, '0')
}

private fun fill(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} rg\n"
private fun stroke(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} RG\n"
