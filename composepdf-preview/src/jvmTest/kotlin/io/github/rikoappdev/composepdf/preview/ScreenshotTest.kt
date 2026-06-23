package io.github.rikoappdev.composepdf.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.rikoappdev.composepdf.examples.ExampleDocuments
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders [PdfPreview] headlessly (Compose Desktop) for a sample document to a PNG for visual
 * inspection — this is also proof that the @Preview path renders (same synchronous fonts, text,
 * tables, vector badge and raster images).
 */
class ScreenshotTest {

    @Test
    fun rendersPreviewToPng() {
        val photos = List(8) { i -> jpeg(240, 160, i) }
        val spec = ExampleDocuments.productCatalog(photos) // text + tables + SVG badge + images + multi-page

        val scene = ImageComposeScene(width = 760, height = 1060, density = Density(1f)) {
            PdfPreview(spec, previewFontRegular(), previewFontBold())
        }
        try {
            val image = scene.render()
            File("build").mkdirs()
            val out = File("build/preview-check.png")
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            assertTrue(out.length() > 2000, "preview PNG suspiciously small: ${out.length()} bytes")
        } finally {
            scene.close()
        }
    }

    private fun jpeg(w: Int, h: Int, seed: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val c1 = Color(Color.HSBtoRGB((seed * 0.16f) % 1f, 0.55f, 0.92f))
        val c2 = Color(Color.HSBtoRGB((seed * 0.16f + 0.12f) % 1f, 0.6f, 0.72f))
        g.paint = GradientPaint(0f, 0f, c1, w.toFloat(), h.toFloat(), c2)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", bos)
        return bos.toByteArray()
    }
}
