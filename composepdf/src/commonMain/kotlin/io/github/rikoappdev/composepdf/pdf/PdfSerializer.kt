package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.util.ByteBuf
import io.github.rikoappdev.composepdf.util.toHex4
import kotlin.math.roundToInt

/** A JPEG ready to embed via /DCTDecode (raw bytes, no re-encode). [components]: 1=gray, 3=RGB. */
internal class JpegImage(val width: Int, val height: Int, val components: Int, val bytes: ByteArray)

/**
 * Turns laid-out [pages] (top-left-origin draw ops) + the [book]'s used fonts + [images] into a
 * PDF byte stream. Flips y to PDF's bottom-left origin. Text/content streams, the subset font
 * program and the ToUnicode CMap are FlateDecode-compressed; JPEG images stay as raw /DCTDecode.
 */
internal fun serializePdf(pages: List<Page>, book: FontBook, images: List<JpegImage>): ByteArray {
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
        val colorSpace = if (img.components == 1) "/DeviceGray" else "/DeviceRGB"
        val num = doc.add(
            streamObject(
                "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode",
                img.bytes,
            )
        )
        imgRes.add("Im$i" to num)
    }

    val fontEntries = fontRes.values.joinToString(" ") { "/${it.first} ${it.second} 0 R" }
    val imgEntries = imgRes.joinToString(" ") { "/${it.first} ${it.second} 0 R" }
    val resources = "<< /Font << $fontEntries >> /XObject << $imgEntries >> >>"

    val pageNums = ArrayList<Int>()
    for (page in pages) {
        val content = doc.add(streamObject("", buildContent(page, fontRes, imgRes), compress = true))
        pageNums.add(
            doc.addDict(
                "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${page.widthPt} ${page.heightPt}] " +
                    "/Resources $resources /Contents $content 0 R >>"
            )
        )
    }
    doc.setDict(pagesObj, "<< /Type /Pages /Kids [${pageNums.joinToString(" ") { "$it 0 R" }}] /Count ${pageNums.size} >>")
    doc.setDict(catalog, "<< /Type /Catalog /Pages $pagesObj 0 R >>")
    return doc.build(rootObj = catalog)
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
            op.fill?.let { b.ascii("${fill(it)}${op.xPt} $yPdf ${op.wPt} ${op.hPt} re f\n") }
            op.stroke?.let {
                b.ascii("${stroke(it)}${op.strokeWidthPt} w\n${op.xPt} $yPdf ${op.wPt} ${op.hPt} re S\n")
            }
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
            val s = if (op.cover) maxOf(op.wPt.toDouble() / iw, op.hPt.toDouble() / ih)
            else minOf(op.wPt.toDouble() / iw, op.hPt.toDouble() / ih)
            val dw = (iw * s).roundToInt()
            val dh = (ih * s).roundToInt()
            val ox = op.xPt + (op.wPt - dw) / 2
            val oy = boxYPdf + (op.hPt - dh) / 2
            if (op.cover) {
                b.ascii("q\n${op.xPt} $boxYPdf ${op.wPt} ${op.hPt} re W n\n$dw 0 0 $dh $ox $oy cm\n/$name Do\nQ\n")
            } else {
                b.ascii("q\n$dw 0 0 $dh $ox $oy cm\n/$name Do\nQ\n")
            }
        }
    }
    return b.toByteArray()
}

/** PDF color component as a deterministic "0.ddd"/"1" decimal string (value/255). */
private fun comp(c: Int): String {
    val m = (c * 1000 + 127) / 255
    return if (m >= 1000) "1" else "0." + m.toString().padStart(3, '0')
}

private fun fill(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} rg\n"
private fun stroke(c: PdfColor) = "${comp(c.r)} ${comp(c.g)} ${comp(c.b)} RG\n"
