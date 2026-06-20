package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Tables — weighted columns, dark header, total row, multi-page with a repeating header. */
class Slice2TableTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    @Test
    fun multiPageTableRepeatsHeader() {
        val descriptions = listOf("Lorem ipsum dolor", "Consectetur adipiscing", "Sed do eiusmod", "Ut labore et dolore")
        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Item table", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF212529)))
            spacer(8.dp)
            table(
                columns = listOf(
                    PdfColumn(2.5f, "Code"),
                    PdfColumn(5f, "Description"),
                    PdfColumn(2f, "Amount", TextAlign.End),
                ),
                headerStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PdfColor.White, align = TextAlign.Start),
                headerBackground = Color(0xFF343A40),
                cellPadding = 5.dp,
            ) {
                var total = 0
                repeat(58) { i ->
                    val amount = 10 + (i % 5)
                    total += amount
                    row("A-${(i + 1).toString().padStart(3, '0')}", descriptions[i % descriptions.size], "$amount.00")
                }
                totalRow("", "Total", "$total.00")
            }
        }

        val pdf = doc.render(regular(), bold())
        File("build").mkdirs()
        File("build/slice2-table.pdf").writeBytes(pdf)
        println("slice2-table.pdf size = ${pdf.size} bytes")

        Loader.loadPDF(pdf).use { d ->
            assertTrue(d.numberOfPages >= 2, "expected multi-page table, got ${d.numberOfPages}")
            val text = PDFTextStripper().getText(d)
            assertTrue("Item table" in text && "Total" in text, "title/total missing")
            assertTrue("Lorem ipsum dolor" in text, "description cell missing")
            // Header text must appear on every page (repeating header).
            assertTrue(Regex("Description").findAll(text).count() >= d.numberOfPages, "header not repeated on each page")
            val r = PDFRenderer(d)
            ImageIO.write(r.renderImageWithDPI(0, 120f), "png", File("build/slice2-table-p1.png"))
            ImageIO.write(r.renderImageWithDPI(d.numberOfPages - 1, 120f), "png", File("build/slice2-table-plast.png"))
        }
    }
}
