package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Multi-line text flow, alignment, bold, and automatic pagination. */
class Slice1FlowTest {

    private fun regular() = File("src/commonMain/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/commonMain/resources/font/NotoSans-Bold.ttf").readBytes()

    private val lorem =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
            "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud " +
            "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure " +
            "dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur."

    private fun sampleDoc() = pdfDocument(PageConfig(margin = 40.dp)) {
        text("Sample document", TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, align = TextAlign.Center))
        spacer(8.dp)
        text(
            "Příliš žluťoučký kůň úpěl ďábelské ódy; zażółć gęślą jaźń.",
            TextStyle(fontSize = 11.sp, color = Color(0xFF212529), align = TextAlign.Center),
        )
        spacer(12.dp)
        divider()
        spacer(12.dp)
        repeat(12) { i ->
            text("Section ${i + 1}", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            spacer(2.dp)
            text(lorem, TextStyle(fontSize = 10.sp, color = Color(0xFF333333)))
            spacer(10.dp)
        }
    }

    @Test
    fun rendersPreviewImage() {
        val pdf = sampleDoc().render(regular(), bold())
        File("build").mkdirs()
        Loader.loadPDF(pdf).use { d ->
            ImageIO.write(PDFRenderer(d).renderImageWithDPI(0, 110f), "png", File("build/slice1-flow.png"))
        }
    }

    @Test
    fun flowsAndPaginates() {
        val pdf = sampleDoc().render(regular(), bold())
        File("build").mkdirs()
        File("build/slice1-flow.pdf").writeBytes(pdf)
        println("slice1-flow.pdf size = ${pdf.size} bytes")

        Loader.loadPDF(pdf).use { d ->
            assertTrue(d.numberOfPages >= 2, "expected multi-page output, got ${d.numberOfPages}")
            val text = PDFTextStripper().getText(d)
            assertTrue("Lorem ipsum" in text, "body text missing")
            assertTrue("Sample document" in text, "bold title missing")
            assertTrue("žluťoučký" in text, "diacritics missing")
            assertTrue("zażółć gęślą jaźń" in text, "Polish diacritics missing")
        }
    }
}
