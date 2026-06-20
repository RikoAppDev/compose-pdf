package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Container layout — weighted rows/columns, boxes with border + background. */
class Slice1LayoutTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private val ink = Color(0xFF212529)
    private val muted = Color(0xFF6C757D)

    private fun card() = pdfDocument(PageConfig(margin = 36.dp)) {
        text("Layout sample", TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink))
        spacer(10.dp)

        box(border = 1.dp, borderColor = muted) {
            // Dark title bar with a left/right weighted row.
            box(padding = 8.dp, background = Color(0xFF343A40)) {
                row {
                    cell(2f) { text("Summary card", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PdfColor.White)) }
                    cell(1f) { text("2026-06", TextStyle(fontSize = 9.sp, color = PdfColor.White, align = TextAlign.End)) }
                }
            }
            // Three weighted columns.
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
                        text("Příliš žluťoučký — ďábelské ódy", TextStyle(fontSize = 8.sp, color = ink))
                        text("zażółć gęślą jaźń", TextStyle(fontSize = 8.sp, color = ink))
                    }
                }
            }
        }
        spacer(10.dp)
        text("Notes", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
        text(
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
                "incididunt ut labore et dolore magna aliqua.",
            TextStyle(fontSize = 9.sp, color = ink),
        )
    }

    @Test
    fun cardLayoutRendersAndExtracts() {
        val pdf = card().render(regular(), bold())
        File("build").mkdirs()
        File("build/slice1-card.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { d ->
            ImageIO.write(PDFRenderer(d).renderImageWithDPI(0, 130f), "png", File("build/slice1-card.png"))
            val text = PDFTextStripper().getText(d)
            assertTrue("Column one" in text && "Column two" in text && "Column three" in text, "summary columns missing")
            assertTrue("Total: 3" in text, "total row missing")
            assertTrue("Summary card" in text && "2026-06" in text, "title bar missing")
        }
        println("slice1-card.pdf size = ${pdf.size} bytes")
    }
}
