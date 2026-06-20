package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Builds a multi-record report structure (nested boxes with backgrounds, a 3-column summary row,
 * an item list, a note, a photo grid and a sign-off line, repeated across several records) to catch a
 * structural bug that the slice tests don't exercise. If the engine produces an invalid PDF for this
 * shape, PDFBox loading or the raw structure check below will fail here on the JVM.
 */
class SplittableCardTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = AwtColor(0x80, 0x80, 0x80); g.fillRect(0, 0, w, h); g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    @Test
    fun nestedCardStructureIsValid() {
        val ink = Color(0xFF212529)
        val muted = Color(0xFF6C757D)
        val dark = Color(0xFF343A40)
        val photo = jpeg(640, 480)

        val pdf = pdfDocument(PageConfig(margin = 36.dp)) {
            header {
                row(gap = 12.dp) {
                    cell(3f) {
                        text("ACME Inc.", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink))
                        text("123 Example Street", TextStyle(fontSize = 8.sp, color = ink))
                    }
                    cell(3f) {
                        text("Client: Example Ltd.", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End))
                        text("Location: Warehouse A", TextStyle(fontSize = 8.sp, color = ink, align = TextAlign.End))
                    }
                }
                spacer(6.dp)
                divider(muted)
            }
            text("Records", TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink))
            spacer(8.dp)

            repeat(4) { s ->
                box(border = 1.dp, borderColor = muted) {
                    box(padding = 8.dp, background = dark) {
                        row {
                            cell(3f) { text("Section ${s + 1}", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PdfColor.White)) }
                            cell(2f) { text("Total: 8.0", TextStyle(fontSize = 9.sp, color = PdfColor.White, align = TextAlign.End)) }
                        }
                    }
                    box(padding = 10.dp) {
                        row(gap = 10.dp) {
                            cell(1f) {
                                text("Team", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                repeat(3) { text("Member $it — 8", TextStyle(fontSize = 8.sp, color = ink)) }
                                text("Total: 24", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ink))
                            }
                            cell(1f) {
                                text("Tools", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                repeat(2) { text("Tool $it — 4", TextStyle(fontSize = 8.sp, color = ink)) }
                            }
                            cell(1f) {
                                text("Supplies", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                                spacer(3.dp)
                                repeat(2) { text("Supply $it — 10", TextStyle(fontSize = 8.sp, color = ink)) }
                                text("Total: 100.00", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = ink))
                            }
                        }
                        spacer(8.dp)
                        text("Items", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        repeat(2) { text("• Item $it with a longer description to exercise wrapping across the content width of the page", TextStyle(fontSize = 8.sp, color = ink)) }
                        spacer(6.dp)
                        text("Note", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        text("Some note text in English.", TextStyle(fontSize = 8.sp, color = ink))
                        spacer(8.dp)
                        text("Photos", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
                        spacer(4.dp)
                        photoGrid(listOf(photo, photo, photo, photo, photo), perRow = 3, cellHeight = 70.dp)
                        spacer(10.dp)
                        text("Approved by: ______________________", TextStyle(fontSize = 8.sp, color = ink))
                    }
                }
                spacer(10.dp)
            }
        }.render(regular(), bold())

        File("build").mkdirs()
        File("build/splittable-card.pdf").writeBytes(pdf)

        // Raw structure sanity: starts with %PDF, ends with %%EOF, has an xref + trailer.
        val head = pdf.copyOf(8).decodeToString()
        assertTrue(head.startsWith("%PDF-"), "missing PDF header: $head")
        val tail = pdf.copyOfRange(maxOf(0, pdf.size - 32), pdf.size).decodeToString()
        assertTrue(tail.contains("%%EOF"), "missing %%EOF trailer")

        Loader.loadPDF(pdf).use { doc ->
            assertTrue(doc.numberOfPages >= 1, "no pages")
            val text = PDFTextStripper().getText(doc)
            assertTrue("ACME Inc." in text && "Section 1" in text, "expected content missing")
        }
    }
}
