package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Horizontal [TextAlign] of a contained image inside its box. A narrow image in a full-width cell
 * must sit at the left for [TextAlign.Start], centered for [TextAlign.Center] (the default) and
 * flush right for [TextAlign.End] — the behaviour a logo in a wide header cell relies on.
 */
class ImageAlignTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /** A solid-red square JPEG — narrow once contained to a small height, so the box has slack. */
    private fun redSquare(): ByteArray {
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = AwtColor.RED; g.fillRect(0, 0, 100, 100)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    /** Leftmost pixel column that contains a clearly-red pixel, rendering at 72 DPI (1pt = 1px). */
    private fun leftmostRedColumn(align: HorizontalAlignment): Int {
        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            image(redSquare(), height = 40.dp, fit = PhotoFit.Contain, align = align)
        }
        val pdf = doc.render(regular(), bold())
        Loader.loadPDF(pdf).use { d ->
            val img = PDFRenderer(d).renderImageWithDPI(0, 72f)
            for (x in 0 until img.width) {
                for (y in 0 until img.height) {
                    val c = AwtColor(img.getRGB(x, y))
                    if (c.red > 200 && c.green < 100 && c.blue < 100) return x
                }
            }
        }
        return -1
    }

    @Test
    fun alignShiftsContainedImageHorizontally() {
        // A4 content runs x ∈ [36, 559] (523pt wide); the contained square is 40pt wide.
        val start = leftmostRedColumn(HorizontalAlignment.Start)
        val center = leftmostRedColumn(HorizontalAlignment.Center)
        val end = leftmostRedColumn(HorizontalAlignment.End)

        assertTrue(start in 1..120, "Start should hug the left margin (~36), was $start")
        assertTrue(center in 220..340, "Center should sit mid-page (~277), was $center")
        assertTrue(end in 460..560, "End should hug the right margin (~519), was $end")
        assertTrue(start < center && center < end, "expected start < center < end, was $start/$center/$end")
    }
}
