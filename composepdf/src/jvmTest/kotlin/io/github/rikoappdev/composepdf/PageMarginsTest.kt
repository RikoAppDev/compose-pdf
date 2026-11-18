package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Per-side page margins: the `PaddingValues`-style cascade, validation, the `margin` shorthand, and
 * where the body, header, footer and page-number line land for each side.
 */
class PageMarginsTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val book by lazy { FontBook(regular(), bold()) }

    private val headerRule = Color(0xFF3355BB)
    private val footerRule = Color(0xFFCC2222)
    private val bodyRule = Color(0xFF22AA44)

    private fun rects(page: io.github.rikoappdev.composepdf.render.Page, color: PdfColor) =
        page.ops.filterIsInstance<RectOp>().filter { it.fill === color }

    @Test
    fun sidesCascadeFromAllThroughHorizontalAndVertical() {
        fun sides(m: PageMargins) = listOf(m.start, m.top, m.end, m.bottom).map { it.value }
        assertEquals(listOf(36f, 36f, 36f, 36f), sides(PageMargins()))
        assertEquals(listOf(20f, 20f, 20f, 20f), sides(PageMargins(20.dp)))
        assertEquals(listOf(36f, 36f, 36f, 14.6f), sides(PageMargins(all = 36.dp, bottom = 14.6.dp)))
        assertEquals(listOf(40f, 28f, 40f, 28f), sides(PageMargins(horizontal = 40.dp, vertical = 28.dp)))
        assertEquals(listOf(5f, 10f, 20f, 10f), sides(PageMargins(all = 10.dp, horizontal = 20.dp, start = 5.dp)))
        assertEquals(listOf(30f, 24f, 31f, 20f), sides(PageMargins(start = 30.dp, top = 24.dp, end = 31.dp, bottom = 20.dp)))
    }

    @Test
    fun equalityIsBySide() {
        assertEquals(PageMargins(36.dp), PageMargins(horizontal = 36.dp, vertical = 36.dp))
        assertEquals(PageMargins(36.dp).hashCode(), PageMargins(horizontal = 36.dp, vertical = 36.dp).hashCode())
        assertNotEquals(PageMargins(36.dp), PageMargins(all = 36.dp, bottom = 14.6.dp))
        assertEquals(PageMargins(36.dp), PageConfig().margins)
        assertEquals(PageMargins(40.dp), PageConfig(margin = 40.dp).margins)
        // Sides compare by their millipoint value, the precision the layout honours.
        assertEquals(PageMargins(0.dp), PageMargins((-0f).dp))
        assertEquals(PageMargins(0.dp).hashCode(), PageMargins((-0f).dp).hashCode())
        assertEquals(PageMargins(14.6.dp), PageMargins(14.6f.dp))
        assertNotEquals(PageMargins(14.6.dp), PageMargins(14.601.dp))
    }

    @Test
    fun toStringPrintsWholeValuesWithoutADecimal() {
        assertEquals("PageMargins(start=36.dp, top=36.dp, end=36.dp, bottom=14.6.dp)", PageMargins(all = 36.dp, bottom = 14.6.dp).toString())
        assertEquals("PageMargins(start=0.75.dp, top=0.dp, end=0.dp, bottom=0.dp)", PageMargins(all = (-0f).dp, start = 0.75.dp).toString())
    }

    @Test
    fun negativeMarginsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PageMargins(bottom = (-1).dp) }
        assertFailsWith<IllegalArgumentException> { PageMargins(horizontal = (-0.5).dp) }
        assertFailsWith<IllegalArgumentException> { PageConfig(margins = PageMargins((-1).dp)) }
    }

    @Test
    fun aNegativeMarginShorthandStillLaysOutAsBefore() {
        // 0.10.0 accepted `PageConfig(margin = (-1).dp)` and pulled the content 1 pt past every edge;
        // only an explicitly built PageMargins is validated.
        val cfg = PageConfig(margin = (-1).dp, pageNumbers = false)
        assertEquals(-1f, cfg.margins.start.value)
        val spec = pdfDocument(cfg) {
            divider(bodyRule)
            text("Example Ltd.")
        }
        val page = layout(spec, book).single()
        val rule = rects(page, bodyRule).single()
        assertEquals(-1_000, rule.xMpt)
        assertEquals(-1_000, rule.yMpt)
        assertEquals(597_000, rule.wMpt)
        assertEquals(-1_000, page.ops.filterIsInstance<TextOp>().single().xMpt)
        val pdf = spec.render(regular(), bold())
        assertTrue(Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }.contains("Example Ltd."))
    }

    @Test
    fun marginShorthandIsByteIdenticalToMargins() {
        fun doc(cfg: PageConfig) = pdfDocument(cfg) {
            header { text("Example App") }
            footer { text("Page ${page.number} / ${page.total}") }
            repeat(120) { text("Body line $it") }
        }.render(regular(), bold())
        assertContentEquals(doc(PageConfig()), doc(PageConfig(margins = PageMargins())))
        assertContentEquals(doc(PageConfig(margin = 40.dp)), doc(PageConfig(margins = PageMargins(40.dp))))
        // An explicit `margins` wins over `margin`.
        assertContentEquals(doc(PageConfig(margin = 40.dp)), doc(PageConfig(margin = 10.dp, margins = PageMargins(40.dp))))
    }

    @Test
    fun bodySpansFromTheStartToTheEndMargin() {
        val spec = pdfDocument(PageConfig(pageNumbers = false, margins = PageMargins(start = 50.dp, top = 36.dp, end = 20.5.dp, bottom = 36.dp))) {
            divider(bodyRule)
            text("Right edge", TextStyle(fontSize = 10.sp, align = TextAlign.End))
        }
        val page = layout(spec, book).single()
        val rule = rects(page, bodyRule).single()
        assertEquals(50_000, rule.xMpt)
        assertEquals((595 - 50 - 20.5) * 1000, rule.wMpt.toDouble())
        val text = page.ops.filterIsInstance<TextOp>().single()
        assertEquals((595 - 20.5) * 1000, (text.xMpt + book.measureWidthMpt("Right edge", FontWeight.Normal, 10_000)).toDouble())
    }

    @Test
    fun headerRestsOnTheTopMarginAndFooterOnTheBottomMargin() {
        val spec = pdfDocument(PageConfig(pageNumbers = false, margins = PageMargins(all = 36.dp, top = 20.dp, bottom = 14.6.dp))) {
            header(gap = 12.dp) { divider(headerRule, thickness = 4.dp) }
            footer(gap = 12.dp) { divider(footerRule, thickness = 3.dp) }
            repeat(2000) { divider(bodyRule, thickness = 1.dp) }
        }
        val pages = layout(spec, book)
        assertTrue(pages.size > 1)
        for ((i, page) in pages.withIndex()) {
            val header = rects(page, headerRule).single()
            val footer = rects(page, footerRule).single()
            val body = rects(page, bodyRule)
            assertEquals(20_000, header.yMpt, "the header's top edge rests on the top margin")
            assertEquals(842_000 - 14_600, footer.yMpt + footer.hMpt, "the footer's bottom edge rests on the bottom margin")
            assertEquals(header.yMpt + header.hMpt + 12_000, body.minOf { it.yMpt }, "the body starts one gap below the header")
            if (i == pages.lastIndex) continue // the last page ends wherever the body runs out
            // 1-pt body rules fill the frame to within 1 pt of its bottom, one gap above the footer.
            val slack = footer.yMpt - 12_000 - body.maxOf { it.yMpt + it.hMpt }
            assertTrue(slack in 0 until 1_000, "page ${i + 1}: the body ends one gap above the footer, slack was $slack mpt")
        }
    }

    @Test
    fun pageNumberLineRestsOnTheBottomMarginAndCentresBetweenStartAndEnd() {
        val cfg = PageConfig(margins = PageMargins(start = 100.dp, top = 36.dp, end = 20.dp, bottom = 10.dp))
        val page = layout(pdfDocument(cfg) { text("Body") }, book).single()
        val number = page.ops.filterIsInstance<TextOp>().maxBy { it.baselineYMpt }
        val style = cfg.pageNumberStyle
        val lineH = (8 * 1.3).toInt() * 1000
        assertEquals(842_000 - 10_000 - lineH, number.baselineYMpt - book.ascentMpt(style.fontWeight, style.fontSize.mpt))
        val tw = book.measureWidthMpt("1 / 1", style.fontWeight, style.fontSize.mpt)
        val contentW = (595 - 100 - 20) * 1000
        assertEquals(100_000 + (contentW - tw) / 2 / 1000 * 1000, number.xMpt)
    }

    @Test
    fun pageAwareFooterReservesItsOwnHeightOnEveryPageWithAFractionalBottomMargin() {
        val seen = ArrayList<PageInfo>()
        val spec = pdfDocument(PageConfig(pageNumbers = false, margins = PageMargins(all = 36.dp, bottom = 14.6.dp))) {
            footer(gap = 9.dp) {
                seen += page
                divider(footerRule, thickness = 0.75.dp)
                text("Page ${page.number} / ${page.total}", TextStyle(fontSize = 7.5.sp))
                if (page.isLast) text("Signed for Example App", TextStyle(fontSize = 9.sp))
            }
            repeat(1700) { divider(bodyRule, thickness = 1.dp) }
        }
        val result = layoutDocument(spec, book, RenderImageTable(spec.images))
        val pages = result.pages
        val total = pages.size
        assertTrue(total > 1)
        for ((i, page) in pages.withIndex()) {
            val footerTop = rects(page, footerRule).single().yMpt
            val bodyBottom = rects(page, bodyRule).maxOf { it.yMpt + it.hMpt }
            // Body rules are 1 pt, so the body ends at most 1 pt short of its frame, which ends one gap
            // above this page's footer (a cycle fallback may only leave more room, never less).
            val clearance = footerTop - bodyBottom
            assertTrue(clearance >= 9_000, "page ${i + 1}: body overlaps the footer gap ($clearance mpt)")
            if (!result.usedFallback && i < total - 1) assertTrue(clearance < 10_000, "page ${i + 1}: body ends $clearance mpt above the footer")
            val texts = page.ops.filterIsInstance<TextOp>()
            assertEquals(if (i == total - 1) 2 else 1, texts.size, "page ${i + 1}: footer lines")
            val lastLine = texts.maxBy { it.baselineYMpt }
            val fs = lastLine.fontSizeMpt
            val lineTop = lastLine.baselineYMpt - book.ascentMpt(lastLine.weight, fs)
            assertEquals(842_000 - 14_600, lineTop + (fs / 1000.0 * 1.3).toInt() * 1000, "page ${i + 1}: the footer ends on the bottom margin")
        }
        assertTrue(seen.filter { it.total == total }.isNotEmpty())
    }

    @Test
    fun bandsAndBodyStartAtTheStartMarginAndEndAlignedFooterTextEndsAtTheEndMargin() {
        // Real fonts, read back from the PDF: start != end, a static header and a page-aware footer.
        val start = 50f
        val end = 25.5f
        val cfg = PageConfig(pageNumbers = false, margins = PageMargins(start = start.dp, top = 30.dp, end = end.dp, bottom = 20.dp))
        val spec = pdfDocument(cfg) {
            header { text("ExampleLtd", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)) }
            footer {
                text("Footer${page.number}", TextStyle(fontSize = 8.sp))
                text("example.com", TextStyle(fontSize = 8.sp, align = TextAlign.End))
            }
            repeat(60) { text("Body$it") }
        }
        val runs = textRuns(spec.render(regular(), bold()))
        val pages = runs.map { it.page }.distinct()
        assertEquals(2, pages.size, "the body should span two pages")
        val rightEdge = 595f - end
        for (p in pages) {
            val onPage = runs.filter { it.page == p }
            fun run(text: String) = onPage.single { it.text == text }
            assertEquals(start, run("ExampleLtd").x, 0.5f, "page ${p + 1}: header x")
            assertEquals(start, run("Footer${p + 1}").x, 0.5f, "page ${p + 1}: footer x")
            assertEquals(rightEdge, run("example.com").right, 0.5f, "page ${p + 1}: End-aligned footer right edge")
            val body = onPage.filter { it.text.startsWith("Body") }
            assertTrue(body.isNotEmpty(), "page ${p + 1}: body text")
            for (b in body) assertEquals(start, b.x, 0.5f, "page ${p + 1}: ${b.text} x")
        }
    }

    /** One extracted word: its page, text, left x and right edge (x + advance of its last glyph), in points. */
    private class Run(val page: Int, val text: String, val x: Float, val right: Float)

    private fun textRuns(pdf: ByteArray): List<Run> {
        val runs = ArrayList<Run>()
        Loader.loadPDF(pdf).use { d ->
            for (p in 0 until d.numberOfPages) {
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, positions: List<TextPosition>) {
                        val first = positions.firstOrNull() ?: return
                        val last = positions.last()
                        runs.add(Run(p, text, first.xDirAdj, last.xDirAdj + last.widthDirAdj))
                    }
                }
                stripper.startPage = p + 1
                stripper.endPage = p + 1
                stripper.getText(d)
            }
        }
        return runs
    }
}
