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
 * Verifies the PNG path end-to-end: a PNG with an alpha channel is decoded (inflate + un-filter),
 * embedded as a FlateDecode RGB image plus an 8-bit /SMask, and renders correctly — the opaque half
 * shows its color and the transparent half lets the white page show through (not black, which is what
 * a missing/broken SMask would produce).
 */
class PngImageTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /** Left half opaque red, right half fully transparent. */
    private fun halfTransparentPng(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = AwtColor(220, 30, 30, 255)
        g.fillRect(0, 0, w / 2, h) // right half stays fully transparent (0,0,0,0)
        g.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    @Test
    fun pngWithAlphaDecodesAndRespectsTransparency() {
        val png = halfTransparentPng(240, 120)
        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            image(png, width = 240.dp, height = 120.dp, fit = PhotoFit.Contain)
        }
        val pdf = doc.render(regular(), bold())
        File("build").mkdirs()
        File("build/png-test.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { d ->
            val img = PDFRenderer(d).renderImageWithDPI(0, 150f)
            ImageIO.write(img, "png", File("build/png-test.png"))

            var red = 0
            var black = 0
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    val c = AwtColor(img.getRGB(x, y))
                    if (c.red > 180 && c.green < 90 && c.blue < 90) red++
                    if (c.red < 40 && c.green < 40 && c.blue < 40) black++
                }
            }
            // The opaque-red half must render (proves PNG color decode + RGB embedding).
            assertTrue(red > 2000, "Expected a sizable red region from the PNG, got $red px")
            // The transparent half must show the white page, not a black block (proves the SMask).
            assertTrue(black < 500, "Transparent area rendered as black ($black px) — SMask not applied")
        }
    }
}
