package io.github.rikoappdev.composepdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color as AwtColor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trailing whitespace must not shift right-aligned text. Splitting on `' '` already drops ASCII
 * spaces, but a trailing tab or non-breaking space (U+00A0) carries an advance that would otherwise
 * push the visible glyphs left of the margin. All variants must land at the same right edge.
 */
class TrailingWhitespaceAlignTest {

    private fun reg() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /** Rightmost inked pixel x of a single right-aligned line rendered at 300 DPI. */
    private fun rightEdge(s: String): Int {
        val pdf = pdfDocument(PageConfig(margin = 36.dp)) {
            text(s, TextStyle(fontSize = 9.sp, color = Color(0xFF000000), align = TextAlign.End))
        }.render(reg(), bold())
        Loader.loadPDF(pdf).use { d ->
            val img = PDFRenderer(d).renderImageWithDPI(0, 300f)
            var maxX = -1
            for (y in 0 until img.height) for (x in img.width - 1 downTo 0) {
                val c = AwtColor(img.getRGB(x, y))
                if (c.red < 120 && c.green < 120 && c.blue < 120) { if (x > maxX) maxX = x; break }
            }
            return maxX
        }
    }

    @Test
    fun trailingWhitespaceDoesNotShiftRightAlignedText() {
        val base = "12345"
        val clean = rightEdge(base)
        val variants = mapOf(
            "space" to base + Char(0x20),
            "tab" to base + Char(0x09),
            "nbsp" to base + Char(0xA0),
        )
        // Within 2px (≈0.5pt) at 300 DPI counts as the same right edge.
        for ((label, s) in variants) {
            val edge = rightEdge(s)
            assertTrue(kotlin.math.abs(edge - clean) <= 2, "$label edge $edge should match clean edge $clean")
        }
    }
}
