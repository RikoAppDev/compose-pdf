package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Report-oriented layout helpers: keyValue rows, TextStyle.copy and zebra-striped tables. */
class ReportLayoutsTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    @Test
    fun keyValueAndZebraTableRender() {
        val doc = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Detail Report", TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            spacer(8.dp)
            keyValue("Reference", "INV-2026-001")
            keyValue("Status", "Approved")
            keyValue("Total", "1234.00")
            spacer(10.dp)
            table(
                columns = listOf(
                    PdfColumn(3f, "Item"),
                    PdfColumn(1f, "Qty", TextAlign.End),
                    PdfColumn(1f, "Price", TextAlign.End),
                ),
                zebra = Color(0xFFF1F3F5),
            ) {
                for (i in 1..12) row("Line item $i", "$i", "${i * 5}.00")
                totalRow("Total", "", "390.00")
            }
        }
        val pdf = doc.render(regular(), bold())
        File("build").mkdirs()
        File("build/report-layouts.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { d ->
            assertTrue(d.numberOfPages >= 1)
            val text = PDFTextStripper().getText(d)
            assertTrue("Reference" in text && "INV-2026-001" in text, "keyValue label/value missing")
            assertTrue("Line item 1" in text && "Line item 12" in text, "table rows missing")
            assertTrue("Approved" in text, "value missing")
        }
    }

    @Test
    fun textStyleCopyOverridesOnlyGivenFields() {
        val base = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF112233))
        val aligned = base.copy(align = TextAlign.End)
        assertTrue(aligned.align == TextAlign.End)
        assertTrue(aligned.fontSize == base.fontSize && aligned.fontWeight == base.fontWeight)
        assertTrue(aligned.color === base.color)
    }
}
