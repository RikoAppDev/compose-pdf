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
 * Content-sized cells (`cell(0f)`) and row [VerticalAlignment] — the primitives a letterhead needs:
 * a logo that hugs the left, an info block pushed flush right by a weighted spacer, and the short
 * logo vertically centred against the taller text block.
 */
class RowLayoutTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private fun square(color: AwtColor): ByteArray {
        val img = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color; g.fillRect(0, 0, 40, 40)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun render(build: io.github.rikoappdev.composepdf.PdfContentScope.() -> Unit): BufferedImage {
        val pdf = pdfDocument(PageConfig(margin = 36.dp), build).render(regular(), bold())
        Loader.loadPDF(pdf).use { d -> return PDFRenderer(d).renderImageWithDPI(0, 72f) }
    }

    private fun isRed(c: AwtColor) = c.red > 200 && c.green < 100 && c.blue < 100
    private fun isBlue(c: AwtColor) = c.blue > 200 && c.red < 100 && c.green < 100

    @Test
    fun contentSizedCellsWithSpacerPushBlocksToTheEdges() {
        val img = render {
            row {
                cell(0f) { image(square(AwtColor.RED), height = 20.dp, fit = PhotoFit.Contain) }
                cell(1f) { }
                cell(0f) { image(square(AwtColor.BLUE), height = 20.dp, fit = PhotoFit.Contain) }
            }
        }
        var redLeft = Int.MAX_VALUE
        var blueRight = -1
        for (x in 0 until img.width) for (y in 0 until img.height) {
            val c = AwtColor(img.getRGB(x, y))
            if (isRed(c)) redLeft = minOf(redLeft, x)
            if (isBlue(c)) blueRight = maxOf(blueRight, x)
        }
        // A4 content runs x ∈ [36, 559]: red hugs the left margin, blue the right.
        assertTrue(redLeft in 1..80, "red should hug the left margin (~36), was $redLeft")
        assertTrue(blueRight in 510..575, "blue should hug the right margin (~559), was $blueRight")
    }

    /** Topmost pixel row containing a red pixel for a 20pt square next to a 5-line block. */
    private fun redTop(valign: VerticalAlignment): Int {
        val img = render {
            row(verticalAlignment = valign) {
                cell(0f) { image(square(AwtColor.RED), height = 20.dp, fit = PhotoFit.Contain) }
                cell(1f) {
                    repeat(5) { text("line of header text", TextStyle(fontSize = 10.sp)) }
                }
            }
        }
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (isRed(AwtColor(img.getRGB(x, y)))) return y
        }
        return -1
    }

    @Test
    fun verticalAlignmentCentersTheShortCell() {
        val top = redTop(VerticalAlignment.Top)
        val center = redTop(VerticalAlignment.Center)
        // Top pins the square at the row's top (~margin 36); Center pushes it down by ~half the slack.
        assertTrue(top in 30..45, "Top should pin the square at the row top (~36), was $top")
        assertTrue(center > top + 10, "Center should push the square down vs Top, was $center vs $top")
    }
}
