package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Right-alignment must stay flush when a **color-emoji face is supplied**. The alignment offset is
 * `cellRight - measureWidthPt(line)`, while the glyphs are actually drawn by `placeLine`, which lays a
 * contiguous text run as ONE run advancing by `widthOfPt(run)` (font units summed, rounded to points
 * ONCE). The emoji-aware `measureWidthPt` used to round EACH glyph to points and sum those, over-counting
 * a run by up to ~0.5pt per glyph; that surplus shifted right-aligned text left (a tabular 15-digit number
 * drifted ~3pt — the "number indented from the right" the app saw). The no-emoji path always rounded once,
 * which is why the bug only showed in the app (it passes an emoji font) and never in emoji-less tests.
 *
 * These tests activate the emoji path with the in-memory [SyntheticEmojiFont] and use only synthetic data.
 */
class EmojiAlignWidthTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    @Test
    fun measuredWidthEqualsDrawnWidthWhenEmojiFacePresent() {
        val withEmoji = FontBook(regular(), bold(), SyntheticEmojiFont.build())
        val roundOnce = FontBook(regular(), bold()) // no emoji face → reference round-once path

        // Pure-text lines (no emoji code points). Digits exercise tabular figures, where the old
        // per-glyph rounding drifted most.
        for (s in listOf("000000000000000", "0123456789012345", "12345", "Subtotal Example")) {
            val measured = withEmoji.measureWidthPt(s, FontWeight.Normal, 8)
            val drawn = withEmoji.widthOfPt(withEmoji.shape(s, FontWeight.Normal), FontWeight.Normal, 8)
            assertEquals(
                drawn, measured,
                "emoji-aware measureWidthPt must equal the width placeLine actually draws for \"$s\"",
            )
            assertEquals(
                roundOnce.measureWidthPt(s, FontWeight.Normal, 8), measured,
                "emoji-aware measurement must match the round-once path for pure text \"$s\"",
            )
        }
    }

    @Test
    fun rightAlignedDigitBlockIsFlushInRenderedPdf() {
        val emojiFont = SyntheticEmojiFont.build()
        // Three right-aligned digit lines of different lengths in the same full-width column. With the
        // bug each shifted left by a different amount (longer = more drift) → a ragged right edge.
        val lines = listOf("000000000000000", "0000000000", "00000")
        val doc = pdfDocument(PageConfig(margin = 36.dp, pageNumbers = false)) {
            lines.forEach { text(it, TextStyle(fontSize = 8.sp, align = TextAlign.End)) }
        }
        val pdf = doc.render(regular(), bold(), emojiFont)
        File("build").mkdirs()
        File("build/emoji-align-test.pdf").writeBytes(pdf)

        val rightEdges = lineRightEdges(pdf)
        assertEquals(lines.size, rightEdges.size, "expected one right edge per line, got $rightEdges")
        val spread = rightEdges.max() - rightEdges.min()
        // Flush within sub-pixel rounding (≤1pt). Pre-fix the spread was ~2–3pt and clearly visible.
        assertTrue(spread <= 1.0f, "right edges must be flush; spread was ${spread}pt over $rightEdges")
    }

    /** Right edge (x + width of the last glyph) of each text-showing line, top-to-bottom. */
    private fun lineRightEdges(pdf: ByteArray): List<Float> {
        val edges = ArrayList<Pair<Float, Float>>() // (y, rightEdge)
        Loader.loadPDF(pdf).use { d ->
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, positions: List<TextPosition>) {
                    val last = positions.lastOrNull() ?: return
                    edges.add(last.yDirAdj to (last.xDirAdj + last.widthDirAdj))
                }
            }
            stripper.getText(d)
        }
        return edges.sortedBy { it.first }.map { it.second }
    }
}
