package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.examples.ExampleDocuments
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color as AwtColor
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the [ExampleDocuments] gallery to real `.pdf` files plus `.png` previews under
 * `samples/` at the repository root. These are the artifacts the README gallery links to.
 *
 * Run with: `./gradlew :composepdf:jvmTest --tests "*GalleryExportTest"`
 */
class GalleryExportTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    // The jvmTest working directory is the module dir (composepdf/), so the repo root is "..".
    private val outDir = File("../samples").apply { mkdirs() }

    @Test
    fun exportGallery() {
        val photos = samplePhotos()

        export("invoice", ExampleDocuments.invoice(), expectMin = 1, mustContain = listOf("INVOICE", "Total due"))
        export("business-letter", ExampleDocuments.businessLetter(), expectMin = 1, mustContain = listOf("Dear Ms. Doe", "Sincerely"))
        export("price-list", ExampleDocuments.priceList(), expectMin = 1, mustContain = listOf("Price List", "Power tools"))
        export("status-report", ExampleDocuments.statusReport(), expectMin = 1, mustContain = listOf("Status Report", "Milestones"))
        export("transaction-ledger", ExampleDocuments.transactionLedger(90), expectMin = 2, mustContain = listOf("Transaction Ledger", "Net total"), previewAllPages = false)
        export("photo-gallery", ExampleDocuments.photoGallery(photos), expectMin = 1, mustContain = listOf("Photo Gallery"))

        // Complex / multi-page samples.
        export("field-service-report", ExampleDocuments.fieldServiceReport(photos), expectMin = 2, mustContain = listOf("Facility Maintenance Report", "Tasks performed"))
        export("annual-report", ExampleDocuments.annualReport(), expectMin = 3, mustContain = listOf("Annual Financial Report", "General ledger"))
        export("product-catalog", ExampleDocuments.productCatalog(photos), expectMin = 2, mustContain = listOf("Product Catalogue", "Workstations"))
        export("service-agreement", ExampleDocuments.serviceAgreement(), expectMin = 2, mustContain = listOf("Master Services Agreement", "Signatures"))
        export("resume", ExampleDocuments.resume(), expectMin = 1, mustContain = listOf("Senior Software Engineer", "EXPERIENCE"))
        export("event-program", ExampleDocuments.eventProgram(), expectMin = 1, mustContain = listOf("Conference Programme", "Day 1"))

        println("Gallery exported to: ${outDir.absolutePath}")
    }

    /** Renders [doc] to `samples/<name>.pdf`, verifies it, and writes a first-page `samples/<name>.png` preview. */
    private fun export(
        name: String,
        doc: PdfDocumentSpec,
        expectMin: Int,
        mustContain: List<String>,
        previewAllPages: Boolean = false,
    ) {
        val pdf = doc.render(regular(), bold())
        File(outDir, "$name.pdf").writeBytes(pdf)

        var pageCount = 0
        Loader.loadPDF(pdf).use { d ->
            pageCount = d.numberOfPages
            assertTrue(d.numberOfPages >= expectMin, "$name: expected >= $expectMin pages, got ${d.numberOfPages}")
            val text = PDFTextStripper().getText(d)
            mustContain.forEach { needle ->
                assertTrue(needle in text, "$name: extracted text should contain \"$needle\"")
            }
            val renderer = PDFRenderer(d)
            val pages = if (previewAllPages) d.numberOfPages else 1
            for (p in 0 until pages) {
                val suffix = if (pages > 1) "-p${p + 1}" else ""
                ImageIO.write(renderer.renderImageWithDPI(p, 110f), "png", File(outDir, "$name$suffix.png"))
            }
        }
        println("  $name.pdf — ${pdf.size} bytes, $pageCount pages")
    }

    /** Generates baseline JPEGs of varied aspect ratios so the photo gallery shows real image layout. */
    private fun samplePhotos(): List<ByteArray> = listOf(
        jpeg(800, 600, 0x2563EB, 0x06B6D4),
        jpeg(600, 800, 0x16A34A, 0x84CC16),
        jpeg(1000, 500, 0xEA580C, 0xF59E0B),
        jpeg(640, 640, 0x7C3AED, 0xEC4899),
        jpeg(500, 900, 0x0EA5E9, 0x6366F1),
        jpeg(900, 600, 0xDC2626, 0xF97316),
    )

    private fun jpeg(w: Int, h: Int, rgb1: Int, rgb2: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, AwtColor(rgb1), w.toFloat(), h.toFloat(), AwtColor(rgb2))
        g.fillRect(0, 0, w, h)
        g.color = AwtColor(255, 255, 255, 70)
        g.fillOval(w / 6, h / 6, w / 3, h / 3)
        g.color = AwtColor(0, 0, 0, 35)
        g.fillRect(0, h * 3 / 4, w, h / 4)
        g.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", bos)
        return bos.toByteArray()
    }
}
