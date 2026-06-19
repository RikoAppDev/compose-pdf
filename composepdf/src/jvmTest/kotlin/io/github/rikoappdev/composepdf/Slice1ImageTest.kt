package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.image.parseJpeg
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 1: JPEG embedding (/DCTDecode pass-through) + photo grid with cover-crop. */
class Slice1ImageTest {

    private fun regular() = File("src/commonMain/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/commonMain/resources/font/NotoSans-Bold.ttf").readBytes()

    private fun jpeg(w: Int, h: Int, color: AwtColor, label: String): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color; g.fillRect(0, 0, w, h)
        g.color = AwtColor.WHITE; g.drawString(label, 10, 24)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    @Test
    fun parsesJpegDimensions() {
        val info = parseJpeg(jpeg(640, 360, AwtColor.RED, "x"))
        assertEquals(640, info.width)
        assertEquals(360, info.height)
        assertEquals(3, info.components)
    }

    @Test
    fun photoGridRenders() {
        val photos = listOf(
            jpeg(600, 400, AwtColor(0xC0, 0x39, 0x2B), "1 wide"),
            jpeg(400, 600, AwtColor(0x27, 0xAE, 0x60), "2 tall"),
            jpeg(500, 500, AwtColor(0x29, 0x80, 0xB9), "3 square"),
            jpeg(800, 300, AwtColor(0x8E, 0x44, 0xAD), "4 pano"),
            jpeg(640, 480, AwtColor(0xF3, 0x9C, 0x12), "5 4:3"),
        )
        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Fotodokumentácia — 18.06.2026", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold))
            spacer(8.dp)
            photoGrid(photos, perRow = 3, cellHeight = 90.dp, gap = 6.dp)
        }
        val pdf = doc.render(regular(), bold())
        File("build").mkdirs()
        File("build/slice1-photos.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { d ->
            assertTrue(d.numberOfPages >= 1)
            ImageIO.write(PDFRenderer(d).renderImageWithDPI(0, 130f), "png", File("build/slice1-photos.png"))
        }
        println("slice1-photos.pdf size = ${pdf.size} bytes")
    }
}
