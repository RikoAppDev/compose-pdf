package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Capstone: a generic multi-section report combining a repeating header, bordered cards with a
 *  3-column summary, paragraphs, a diacritic line, and a photo grid — flowing across pages. */
class CapstoneReportTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private val ink = Color(0xFF212529)
    private val muted = Color(0xFF6C757D)
    private val dark = Color(0xFF343A40)

    private fun jpeg(w: Int, h: Int, c: AwtColor): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = c; g.fillRect(0, 0, w, h)
        g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }

    private val lorem =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt " +
            "ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation."

    @Test
    fun fullReport() {
        val logo = jpeg(360, 120, AwtColor(0x1F, 0x6F, 0xEB))
        fun photos(n: Int, base: Int) = (1..n).map {
            jpeg(600, 400 + it * 40, AwtColor((base + it * 30) % 256, 120, (60 + it * 25) % 256))
        }

        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            header {
                row(gap = 12.dp) {
                    cell(2f) { image(logo, width = 90.dp, height = 30.dp, fit = PhotoFit.Contain) }
                    cell(3f) {
                        text("ACME Inc.", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink))
                        text("123 Example Street, 00000 City", TextStyle(fontSize = 8.sp, color = ink))
                        text("info@example.com · +000 000 000", TextStyle(fontSize = 8.sp, color = ink))
                    }
                    cell(3f) {
                        text("Prepared for: Example Ltd.", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End))
                        text("Report 2026-06", TextStyle(fontSize = 8.sp, color = ink, align = TextAlign.End))
                    }
                }
                spacer(6.dp)
                divider(muted)
            }

            text("Sections", TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink))
            spacer(8.dp)

            repeat(3) { s ->
                box(border = 1.dp, borderColor = muted) {
                    box(padding = 8.dp, background = dark) {
                        row {
                            cell(3f) { text("Section ${s + 1}", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PdfColor.White)) }
                            cell(2f) { text("Ref: A-${s + 1}0${s + 1}", TextStyle(fontSize = 9.sp, color = PdfColor.White, align = TextAlign.End)) }
                        }
                    }
                    box(padding = 10.dp) {
                        row(gap = 10.dp) {
                            cell(1f) {
                                text("Column one", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                text("Item A — 1", TextStyle(fontSize = 8.sp, color = ink))
                                text("Item B — 2", TextStyle(fontSize = 8.sp, color = ink))
                                text("Total: 3", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ink))
                            }
                            cell(1f) {
                                text("Column two", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                text("Item C — 4", TextStyle(fontSize = 8.sp, color = ink))
                            }
                            cell(1f) {
                                text("Column three", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                text("Item D — 5", TextStyle(fontSize = 8.sp, color = ink))
                                text("Item E — 6", TextStyle(fontSize = 8.sp, color = ink))
                            }
                        }
                        spacer(8.dp)
                        text("Description", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        text(lorem, TextStyle(fontSize = 8.sp, color = ink))
                        if (s % 2 == 0) {
                            spacer(6.dp)
                            text("Notes", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                            text("Příliš žluťoučký kůň úpěl ďábelské ódy; zażółć gęślą jaźń.", TextStyle(fontSize = 8.sp, color = ink))
                        }
                        spacer(8.dp)
                        text("Images", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        spacer(4.dp)
                        photoGrid(photos(4 + s, 40 + s * 50), perRow = 3, cellHeight = 70.dp)
                        spacer(10.dp)
                        text("Signature: ______________________", TextStyle(fontSize = 8.sp, color = ink))
                    }
                }
                spacer(10.dp)
            }
        }

        val pdf = doc.render(regular(), bold())
        File("build").mkdirs()
        File("build/capstone-report.pdf").writeBytes(pdf)
        println("capstone-report.pdf size = ${pdf.size} bytes")

        Loader.loadPDF(pdf).use { d ->
            assertTrue(d.numberOfPages >= 2, "expected multi-page report, got ${d.numberOfPages}")
            val text = PDFTextStripper().getText(d)
            assertTrue("ACME Inc." in text, "company header missing")
            assertTrue("Description" in text && "Images" in text && "Column one" in text, "sections missing")
            // Header repeats on every page.
            assertTrue(Regex("ACME Inc\\.").findAll(text).count() >= d.numberOfPages, "header not on every page")
            val r = PDFRenderer(d)
            for (p in 0 until d.numberOfPages) {
                ImageIO.write(r.renderImageWithDPI(p, 120f), "png", File("build/capstone-report-p${p + 1}.png"))
            }
            println("pages = ${d.numberOfPages}")
        }
    }
}
