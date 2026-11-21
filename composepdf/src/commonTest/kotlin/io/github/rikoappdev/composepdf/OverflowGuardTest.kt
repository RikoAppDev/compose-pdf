package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.layout.BoxNode
import io.github.rikoappdev.composepdf.layout.ColumnNode
import io.github.rikoappdev.composepdf.layout.RowChild
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.SpacerNode
import io.github.rikoappdev.composepdf.layout.TextNode
import io.github.rikoappdev.composepdf.layout.measure
import io.github.rikoappdev.composepdf.render.RectOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Millipoints leave ~2.1e6 pt of `Int` headroom. Heights past it saturate at `Int.MAX_VALUE` — "taller
 * than any page" — instead of wrapping to a small or negative number that the flow would take for a
 * block that fits. Stub metrics and a handful of huge lines keep this fast on every platform.
 */
class OverflowGuardTest {

    /** [n] hard lines of a [sizePt] font whose line height is exactly the font size. */
    private fun tallText(n: Int, sizePt: Int) =
        TextNode(List(n) { "x" }.joinToString("\n"), TextStyle(fontSize = sizePt.sp, lineHeightMultiple = 1.0))

    @Test
    fun saturatingHelpersClampInsteadOfWrapping() {
        assertEquals(Int.MAX_VALUE, satAdd(Int.MAX_VALUE, 1))
        assertEquals(Int.MIN_VALUE, satAdd(Int.MIN_VALUE, -1))
        assertEquals(Int.MAX_VALUE, satMul(100_000, 100_000))
        assertEquals(Int.MIN_VALUE, satMul(-100_000, 100_000))
        assertEquals(1_234_000, satAdd(1_000_000, 234_000))
        assertEquals(42_000, satMul(6, 7_000))
    }

    @Test
    fun lineHeightOfAHugeFontSaturatesToWholePoints() {
        // 1,000,000 pt × 3 = 3,000,000 pt does not fit Int millipoints; it clamps to the largest whole point.
        assertEquals(2_147_483_000, lineHeightMpt(1_000_000_000, 3.0))
        assertEquals(-2_147_483_000, lineHeightMpt(1_000_000_000, -3.0))
    }

    @Test
    fun measuredHeightsSaturate() {
        val text = tallText(10, 429_573) // 4,295,730 pt: past Int millipoints
        assertEquals(Int.MAX_VALUE, measure(text, 523_000, StubMetrics).heightMpt)
        assertEquals(Int.MAX_VALUE, measure(ColumnNode(listOf(text, text), 1_000), 523_000, StubMetrics).heightMpt)
        assertEquals(Int.MAX_VALUE, measure(BoxNode(ColumnNode(listOf(text), 0), 4_000, 4_000, 1_000, PdfColor.Black, null), 523_000, StubMetrics).heightMpt)
        assertEquals(Int.MAX_VALUE, measure(RowNode(listOf(RowChild(text, 1f)), 0), 523_000, StubMetrics).heightMpt)
        // Many children and gaps, each well inside Int, sum past it.
        val spacers = ColumnNode(List(3_000) { SpacerNode(0, 1_000_000) }, 1_000_000)
        assertEquals(Int.MAX_VALUE, measure(spacers, 523_000, StubMetrics).heightMpt)
    }

    @Test
    fun aSaturatedBoxIsTallerThanAPageSoKeepTogetherDoesNotMoveIt() {
        // 10 × 429,573 pt wraps a 32-bit sum to 762.704 pt: between the room left below "Intro" (755 pt)
        // and the full frame (770 pt). A wrapped height would send the box whole to page 2; a saturated
        // one is taller than any page, so the box opens on page 1 and splits like any oversized box.
        val spec = pdfDocument(PageConfig(pageNumbers = false)) {
            text("Intro")
            box(border = 1.dp) { nodes.add(tallText(10, 429_573)) }
        }
        val pages = layout(spec, StubMetrics)
        val firstPageBorder = pages.first().ops.filterIsInstance<RectOp>().filter { it.stroke != null }
        assertEquals(1, firstPageBorder.size, "the box must open on page 1")
        assertEquals(51_000, firstPageBorder.single().yMpt, "the box starts right below the 15 pt intro line")
        assertTrue(pages.size > 10, "each huge line takes its own page, got ${pages.size} pages")
    }
}
