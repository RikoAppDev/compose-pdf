package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.intrinsicWidth
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Box options: per-axis padding and the per-box keep-together override. */
class BoxOptionsTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val book by lazy { FontBook(regular(), bold()) }

    private val boxInk = Color(0xFFAA2222)
    private val style = TextStyle(fontSize = 10.sp)
    private val boxStyle = TextStyle(fontSize = 10.sp, color = boxInk)
    private val cfg = PageConfig(pageNumbers = false)

    private fun boxLinesOn(page: Page) = page.ops.filterIsInstance<TextOp>().count { it.color == boxInk }

    @Test
    fun paddingHorizontalAndVerticalPlaceContent() {
        val spec = pdfDocument(cfg) {
            box(paddingHorizontal = 20.dp, paddingVertical = 5.dp, border = 1.dp) { text("Line 1", style) }
        }
        val page = layout(spec, book).single()
        val margin = 36_000
        val lineH = lineHeightMpt(10_000, style.lineHeightMultiple)
        val ascent = book.ascentMpt(style.fontWeight, 10_000)
        val text = page.ops.filterIsInstance<TextOp>().single()
        assertEquals(margin + 20_000, text.xMpt)
        assertEquals(margin + 5_000 + ascent, text.baselineYMpt)
        val border = page.ops.filterIsInstance<RectOp>().single { it.stroke != null }
        assertEquals(margin, border.xMpt)
        assertEquals(margin, border.yMpt)
        assertEquals(lineH + 10_000, border.hMpt)

        val narrow = pdfDocument(cfg) {
            row { cell(0f) { box(paddingHorizontal = 20.dp, paddingVertical = 5.dp) { text("Line 1", style) } } }
        }
        val row = narrow.nodes.single()
        val textW = book.measureWidthMpt("Line 1", style.fontWeight, 10_000)
        assertEquals(textW + 40_000, intrinsicWidth((row as RowNode).children.single().node, book))
    }

    @Test
    fun unequalPaddingOnTheMeasurePath() {
        // Inside a row the box is measured and placed whole (not flowed): the same insets apply.
        val spec = pdfDocument(cfg) {
            row { cell(1f) { box(paddingHorizontal = 20.dp, paddingVertical = 5.dp, border = 1.dp) { text("Line 1", style) } } }
        }
        val page = layout(spec, book).single()
        val margin = 36_000
        val rowTop = margin
        val lineH = lineHeightMpt(10_000, style.lineHeightMultiple)
        val ascent = book.ascentMpt(style.fontWeight, 10_000)
        val text = page.ops.filterIsInstance<TextOp>().single()
        assertEquals(margin + 20_000, text.xMpt)
        assertEquals(rowTop + 5_000 + ascent, text.baselineYMpt, "content starts below the vertical padding")
        val border = page.ops.filterIsInstance<RectOp>().single { it.stroke != null }
        assertEquals(margin, border.xMpt)
        assertEquals(rowTop, border.yMpt)
        assertEquals(lineH + 2 * 5_000, border.hMpt, "height = content + 2 x vertical padding")
    }

    @Test
    fun verticalPaddingRepeatsOnContinuationPages() {
        // 100 lines x 13 pt is taller than a page: the box splits, and its next fragment re-pads.
        val spec = pdfDocument(cfg) {
            box(paddingHorizontal = 20.dp, paddingVertical = 5.dp, border = 1.dp) { repeat(100) { text("Line $it", boxStyle) } }
        }
        val pages = layout(spec, book)
        assertTrue(pages.size >= 2)
        val margin = 36_000
        val ascent = book.ascentMpt(style.fontWeight, 10_000)
        val first = pages[1].ops.filterIsInstance<TextOp>().first { it.color == boxInk }
        assertEquals(margin + 20_000, first.xMpt)
        assertEquals(margin + 5_000 + ascent, first.baselineYMpt, "first line on page 2 at frame top + vertical padding + ascent")
        val fragment = pages[1].ops.filterIsInstance<RectOp>().single { it.stroke != null }
        assertEquals(margin, fragment.yMpt, "the fragment starts at the frame top")
    }

    @Test
    fun equalPaddingsMatchPaddingBytes() {
        fun doc(build: ContainerScope.() -> Unit) = pdfDocument(cfg) { build() }.render(regular(), bold())
        val a = doc { box(padding = 8.dp, border = 1.dp, background = Color(0xFFEEEEEE)) { text("Line 1", style) } }
        val b = doc { box(paddingHorizontal = 8.dp, paddingVertical = 8.dp, border = 1.dp, background = Color(0xFFEEEEEE)) { text("Line 1", style) } }
        assertContentEquals(a, b)
    }

    private fun roomDoc(config: PageConfig, keepTogether: Boolean?, useParam: Boolean = true) = pdfDocument(config) {
        repeat(44) { text("Line $it", style) } // 44 x 13 pt = 572 pt of 770 pt, ~198 pt left
        if (useParam) {
            box(keepTogether = keepTogether) { repeat(30) { text("Line $it", boxStyle) } }
        } else {
            box { repeat(30) { text("Line $it", boxStyle) } }
        }
    }

    @Test
    fun keepTogetherFalseStartsAtThePen() {
        val off = layout(roomDoc(cfg, false), book)
        assertEquals(2, off.size)
        assertTrue(boxLinesOn(off[0]) > 0, "first box line stays on page 1")
        val dflt = layout(roomDoc(cfg, null), book)
        assertEquals(0, boxLinesOn(dflt[0]), "default moves the whole box to page 2")
        assertEquals(30, boxLinesOn(dflt[1]))
    }

    @Test
    fun keepTogetherTrueOverridesDocumentSetting() {
        val loose = PageConfig(pageNumbers = false, keepBlocksTogether = false)
        val docSetting = layout(roomDoc(loose, null), book)
        assertTrue(boxLinesOn(docSetting[0]) > 0, "document setting off: the box starts at the pen")
        val forced = layout(roomDoc(loose, true), book)
        assertEquals(0, boxLinesOn(forced[0]))
        assertEquals(30, boxLinesOn(forced[1]))
    }

    @Test
    fun keepTogetherNullIsTodaysBehaviour() {
        fun doc(build: ContainerScope.() -> Unit) = pdfDocument(cfg) { build() }.render(regular(), bold())
        val a = doc {
            repeat(44) { text("Line $it", style) }
            box(padding = 6.dp, border = 1.dp) { repeat(30) { text("Line $it", style) } }
        }
        val b = doc {
            repeat(44) { text("Line $it", style) }
            box(padding = 6.dp, border = 1.dp, keepTogether = null) { repeat(30) { text("Line $it", style) } }
        }
        assertContentEquals(a, b)
    }
}
