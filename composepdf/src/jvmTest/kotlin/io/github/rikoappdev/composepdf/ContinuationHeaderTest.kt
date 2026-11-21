package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Continuation headers: `box(continuationHeader = { ... })` repeats content at the top of every continuation fragment. */
class ContinuationHeaderTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val book by lazy { FontBook(regular(), bold()) }

    private val style = TextStyle(fontSize = 10.sp)
    private val cfg = PageConfig(pageNumbers = false)

    // A4 with 36 pt margins and no bands: content from 36 pt to 806 pt, 523 pt wide.
    private val frameTop = 36_000
    private val bottom = 806_000
    private val margin = 36_000
    private val lineH = 13_000 // floor(10 pt x 1.3)
    private val ascent by lazy { book.ascentMpt(FontWeight.Normal, 10_000) }

    private val ink = Color(0xFFAA2222)

    /** One placed text line: its page index, label, x and baseline (millipoints). */
    private data class Line(val page: Int, val label: String, val x: Int, val baseline: Int)

    private fun labels(prefix: String, n: Int) = List(n) { "$prefix-$it" }

    /** The text ops of [pages] that spell one of [labels], in page order then op order. */
    private fun lines(pages: List<Page>, labels: Iterable<String>): List<Line> {
        val byGlyphs = labels.associateBy { book.shape(it, FontWeight.Normal).toList() }
        return pages.flatMapIndexed { i, page ->
            page.ops.filterIsInstance<TextOp>().mapNotNull { op ->
                byGlyphs[op.gids.toList()]?.let { Line(i, it, op.xMpt, op.baselineYMpt) }
            }
        }
    }

    /** [lines] in reading order: by page, then top to bottom. */
    private fun reading(lines: List<Line>) = lines.sortedWith(compareBy({ it.page }, { it.baseline }))

    private fun ContainerScope.textLines(prefix: String, n: Int) = labels(prefix, n).forEach { text(it, style) }

    private fun borders(page: Page, color: PdfColor) = page.ops.filterIsInstance<RectOp>().filter { it.stroke === color }

    /** Index of the text op spelling [label] on [page]. */
    private fun opIndex(page: Page, label: String): Int {
        val gids = book.shape(label, FontWeight.Normal).toList()
        return page.ops.indexOfFirst { it is TextOp && it.gids.toList() == gids }
    }

    private fun ContainerScope.titledBox(lines: Int) =
        box(border = 1.dp, borderColor = ink, padding = 6.dp, continuationHeader = { text("Title (continued)", style) }) {
            text("Title", style)
            textLines("L", lines)
        }

    @Test
    fun headerOnContinuationFragmentsOnly() {
        val pages = layout(pdfDocument(cfg) { titledBox(120) }, book)
        assertEquals(3, pages.size)
        assertEquals(0, lines(pages, listOf("Title")).single().page)
        val headers = lines(pages, listOf("Title (continued)"))
        assertEquals(listOf(1, 2), headers.map { it.page }, "once on every page after the first, never on page 1")
        val body = reading(lines(pages, labels("L", 120)))
        assertEquals(labels("L", 120), body.map { it.label })
        for (h in headers) {
            assertEquals(margin + 6_000, h.x, "page ${h.page + 1}: inside the horizontal padding")
            assertEquals(frameTop + 6_000 + ascent, h.baseline, "page ${h.page + 1}: inside the top padding")
            assertEquals(h.baseline + lineH, body.first { it.page == h.page }.baseline, "page ${h.page + 1}: the body starts directly below the header")
        }
        // The header's height is reserved: 751 pt below it hold 57 lines (58 without it).
        assertEquals(listOf(57, 57, 6), pages.indices.map { p -> body.count { it.page == p } })
        for ((p, page) in pages.withIndex()) {
            assertEquals(frameTop, borders(page, ink).single().yMpt, "page ${p + 1}: one fragment from the frame top")
        }
    }

    // A card: a bordered box whose continuation header is a grey title bar, a grey title
    // bar, then an inner padded box with a split row of two cells, each two sections of small rows.
    private val cardInk = Color(0xFF6C757D)
    private val headerFill = Color(0xFFE9ECEF)
    private val titleFill = Color(0xFFF1F3F5)

    private fun items(prefix: String, n: Int) = List(n) { "Item $prefix$it" }

    private fun ContainerScope.section(heading: String, prefix: String, rows: Int) {
        text(heading, style)
        for (label in items(prefix, rows)) {
            row {
                cell(1f) { text(label, style) }
                cell(0f) { text("1.00", style) }
            }
        }
        divider()
    }

    private fun ContainerScope.card(leftRows: Int, rightRows: Int) {
        box(border = 1.dp, borderColor = cardInk, cornerRadius = 5.dp, continuationHeader = {
            box(padding = 8.dp, background = headerFill, cornerRadius = 5.dp) {
                row {
                    cell(1f) { text("Date (continued)", style) }
                    cell(0f) { text("Total", style) }
                }
            }
        }) {
            box(padding = 8.dp, background = titleFill) {
                row {
                    cell(1f) { text("Date", style) }
                    cell(0f) { text("Total", style) }
                }
            }
            box(padding = 12.dp, keepTogether = false) {
                row(gap = 16.dp, split = true, keepTogether = false) {
                    cell(1f) { section("Section A", "A", leftRows); section("Section B", "B", leftRows) }
                    cell(1f) { section("Section C", "C", rightRows); section("Section D", "D", rightRows) }
                }
            }
        }
    }

    @Test
    fun cardWithTitleBarAndTwoColumns() {
        // Tall vertical margins leave 602 pt (120 pt to 722 pt), so the card crosses three pages.
        val tall = PageConfig(pageNumbers = false, margins = PageMargins(horizontal = 36.dp, vertical = 120.dp))
        val top = 120_000
        val tallBottom = 722_000
        val barH = 8_000 + lineH + 8_000 // the header and the title bar: 8 pt padding around one line
        val entry = top + barH + 12_000 // below the header and the inner box's top padding

        val pages = layout(pdfDocument(tall) { textLines("P", 12); card(40, 20) }, book)
        assertEquals(3, pages.size)
        val leftLabels = listOf("Section A") + items("A", 40) + listOf("Section B") + items("B", 40)
        val rightLabels = listOf("Section C") + items("C", 20) + listOf("Section D") + items("D", 20)
        val left = reading(lines(pages, leftLabels))
        val right = reading(lines(pages, rightLabels))
        assertEquals(leftLabels, left.map { it.label }, "every left row once, in order")
        assertEquals(rightLabels, right.map { it.label }, "every right row once, in order")

        // Page 1: the card starts at the pen, with its title and the first lines of both cells.
        val cardTop = top + 12 * lineH
        val title = lines(pages, listOf("Date")).single()
        assertEquals(0, title.page)
        assertEquals(cardTop + 8_000 + ascent, title.baseline)
        val cellTop = cardTop + barH + 12_000
        assertEquals(Line(0, "Section A", margin + 12_000, cellTop + ascent), left.first())
        assertEquals(0, right.first().page)
        assertEquals(cellTop + ascent, right.first().baseline)
        assertTrue(left.count { it.page == 0 } > 1 && right.count { it.page == 0 } > 1, "both cells hold rows on page 1")
        assertEquals(cardTop, borders(pages[0], cardInk).single().yMpt)

        val headers = lines(pages, listOf("Date (continued)"))
        assertEquals(listOf(1, 2), headers.map { it.page }, "a header on each continuation page only")
        var shared = 0
        for (p in 1 until pages.size) {
            val page = pages[p]
            assertEquals(top, borders(page, cardInk).single().yMpt, "page ${p + 1}: the card's fragment starts at the frame top")
            val bar = page.ops.filterIsInstance<RectOp>().single { it.fill === headerFill }
            assertEquals(top, bar.yMpt, "page ${p + 1}: the header sits directly inside the card")
            assertEquals(margin, bar.xMpt)
            assertEquals(barH, bar.hMpt)
            assertTrue(page.ops.filterIsInstance<RectOp>().none { it.fill === titleFill }, "page ${p + 1}: the title bar is not repeated")
            val h = headers.single { it.page == p }
            assertEquals(margin + 8_000, h.x)
            assertEquals(top + 8_000 + ascent, h.baseline)
            val l = left.firstOrNull { it.page == p }
            val r = right.firstOrNull { it.page == p }
            l?.let { assertEquals(entry + ascent, it.baseline, "page ${p + 1}: the left cell resumes below the header and the padding") }
            r?.let { assertEquals(entry + ascent, it.baseline, "page ${p + 1}: the right cell resumes below the header and the padding") }
            if (l != null && r != null) shared++
        }
        assertEquals(1, shared, "page 2 holds both cells")
        for (line in left + right) {
            assertTrue(line.baseline - ascent + lineH <= tallBottom, "${line.label} crosses the content bottom")
        }

        // The same card with 5 rows per section fits an empty page: with too little room it moves
        // whole to page 2 and shows no header.
        val small = layout(pdfDocument(tall) { textLines("P", 33); card(5, 5) }, book)
        assertEquals(2, small.size)
        assertTrue(lines(small, listOf("Date (continued)")).isEmpty(), "no header")
        assertTrue(small[0].ops.none { it is RectOp }, "nothing of the card on page 1")
        assertEquals(Line(1, "Date", margin + 8_000, top + 8_000 + ascent), lines(small, listOf("Date")).single())
        val smallRows = lines(small, listOf("Section A") + items("A", 5) + listOf("Section B") + items("B", 5) + listOf("Section C") + items("C", 5) + listOf("Section D") + items("D", 5))
        assertEquals(24, smallRows.size)
        assertTrue(smallRows.all { it.page == 1 })
        assertEquals(top, borders(small[1], cardInk).single().yMpt)
    }

    @Test
    fun headerWithPageAwareBands() {
        // Header 60 pt on odd pages, 20 pt on even ones (+ 12 pt gap); footer 13 pt (+ 12 pt gap).
        val spec = pdfDocument(cfg) {
            header {
                if (page.number % 2 == 1) { text("Odd", style); spacer(47.dp) } else { text("Even", style); spacer(7.dp) }
            }
            footer { text("Page ${page.number} / ${page.total}", style) }
            titledBox(150)
        }
        val pages = layout(spec, book)
        assertEquals(3, pages.size)
        fun topOf(p: Int) = if (p % 2 == 0) 36_000 + 60_000 + 12_000 else 36_000 + 20_000 + 12_000
        val usable = 842_000 - 36_000 - 13_000 - 12_000
        val body = reading(lines(pages, labels("L", 150)))
        assertEquals(labels("L", 150), body.map { it.label })
        val headers = lines(pages, listOf("Title (continued)"))
        assertEquals(listOf(1, 2), headers.map { it.page })
        for (h in headers) {
            assertEquals(topOf(h.page) + 6_000 + ascent, h.baseline, "page ${h.page + 1}: the header follows this page's frame")
            assertEquals(h.baseline + lineH, body.first { it.page == h.page }.baseline, "page ${h.page + 1}: the body starts below the header")
        }
        for ((p, page) in pages.withIndex()) {
            assertEquals(topOf(p), borders(page, ink).single().yMpt, "page ${p + 1}: fragment from this page's frame top")
        }
        for (line in body) {
            assertTrue(line.baseline - ascent + lineH <= usable, "${line.label} crosses its page's content bottom")
        }
        // Page 1: 654 pt below the title; page 2: 694 pt below the header; page 3: the rest.
        assertEquals(listOf(50, 53, 47), pages.indices.map { p -> body.count { it.page == p } })
        val footers = lines(pages, pages.indices.map { "Page ${it + 1} / ${pages.size}" })
        assertEquals(pages.indices.toList(), footers.map { it.page }, "every footer shows the true total")
    }

    @Test
    fun headerTallerThanPageIsSkipped() {
        val huge: ContainerScope.() -> Unit = { text("Huge header", style); spacer(2000.dp) }
        fun doc(header: (ContainerScope.() -> Unit)?) = pdfDocument(cfg) {
            text("Intro", style)
            box(border = 1.dp, borderColor = ink, padding = 6.dp, continuationHeader = header) { textLines("L", 150) }
            text("After", style)
        }
        val withHeader = layout(doc(huge), book)
        val without = layout(doc(null), book)
        assertEquals(3, withHeader.size)
        assertTrue(lines(withHeader, listOf("Huge header")).isEmpty(), "never drawn")
        val place = { pages: List<Page> -> reading(lines(pages, labels("L", 150) + listOf("Intro", "After"))).map { Triple(it.label, it.page, it.baseline) } }
        assertEquals(place(without), place(withHeader), "content flows as without a header")
    }

    @Test
    fun breakBeforeClosingPaddingOpensNoFragment() {
        // 16 pt top padding + 58 lines = 770 pt: the content ends exactly at the content bottom.
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = ink, padding = 16.dp, continuationHeader = { text("Title (continued)", style) }) { textLines("L", 58) }
            text("After", style)
        }, book)
        assertEquals(2, pages.size)
        val last = lines(pages, listOf("L-57")).single()
        assertEquals(0, last.page)
        assertEquals(bottom, last.baseline - ascent + lineH)
        assertTrue(lines(pages, listOf("Title (continued)")).isEmpty(), "no header")
        assertTrue(pages[1].ops.none { it is RectOp }, "no box fragment on page 2")
        val first = pages[1].ops.first() as TextOp
        assertContentEquals(book.shape("After", FontWeight.Normal), first.gids)
        assertEquals(margin, first.xMpt)
        assertEquals(frameTop + ascent, first.baselineYMpt)
    }

    @Test
    fun trailingSpacerInHeaderBoxOpensNothing() {
        // 58 lines end at 796 pt, 10 pt above the content bottom; the trailing 40 pt spacer crosses it.
        fun ContainerScope.spaced(after: ContainerScope.() -> Unit = {}) =
            box(border = 1.dp, borderColor = ink, padding = 6.dp, continuationHeader = { text("Title (continued)", style) }) {
                textLines("L", 58)
                spacer(40.dp)
                after()
            }
        assertEquals(1, layout(pdfDocument(cfg) { spaced() }, book).size, "nothing is continued")

        val pages = layout(pdfDocument(cfg) { spaced(); text("After", style) }, book)
        assertEquals(2, pages.size)
        assertTrue(lines(pages, listOf("Title (continued)")).isEmpty(), "no header")
        assertTrue(pages[1].ops.none { it is RectOp }, "no box fragment on page 2")
        val first = pages[1].ops.first() as TextOp
        assertContentEquals(book.shape("After", FontWeight.Normal), first.gids)
        assertEquals(frameTop + ascent, first.baselineYMpt)
        // The spacer is dropped at the break; the closing padding runs below the content bottom, as always.
        val fragment = borders(pages[0], ink).single()
        assertEquals(bottom + 6_000, fragment.yMpt + fragment.hMpt)

        // A spacer that crosses the bottom mid-box is dropped too: the next line starts right below the header.
        val mid = layout(pdfDocument(cfg) { spaced { textLines("M", 3) } }, book)
        assertEquals(2, mid.size)
        val m0 = lines(mid, listOf("M-0")).single()
        assertEquals(1, m0.page)
        assertEquals(frameTop + 6_000 + lineH + ascent, m0.baseline)
    }

    @Test
    fun nestedHeaderBoxes() {
        val outerInk = Color(0xFF2222AA)
        val innerInk = Color(0xFF22AA22)
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = outerInk, padding = 6.dp, continuationHeader = { text("Outer (continued)", style) }) {
                text("Outer", style)
                box(border = 1.dp, borderColor = innerInk, padding = 10.dp, continuationHeader = { text("Inner (continued)", style) }) {
                    text("Inner", style)
                    textLines("L", 150)
                }
            }
        }, book)
        assertEquals(3, pages.size)
        val body = reading(lines(pages, labels("L", 150)))
        assertEquals(labels("L", 150), body.map { it.label })
        val outer = lines(pages, listOf("Outer (continued)"))
        val inner = lines(pages, listOf("Inner (continued)"))
        assertEquals(listOf(1, 2), outer.map { it.page })
        assertEquals(listOf(1, 2), inner.map { it.page })
        val innerTop = frameTop + 6_000 + lineH // the inner fragment starts below the outer header
        for (p in 1 until pages.size) {
            val o = outer.single { it.page == p }
            val i = inner.single { it.page == p }
            assertEquals(margin + 6_000, o.x)
            assertEquals(frameTop + 6_000 + ascent, o.baseline, "page ${p + 1}: the outer header first")
            assertEquals(margin + 16_000, i.x, "page ${p + 1}: inside the inner box's padding")
            assertEquals(innerTop + 10_000 + ascent, i.baseline, "page ${p + 1}: the inner header below it")
            assertTrue(opIndex(pages[p], "Outer (continued)") < opIndex(pages[p], "Inner (continued)"))
            assertEquals(frameTop, borders(pages[p], outerInk).single().yMpt)
            assertEquals(innerTop, borders(pages[p], innerInk).single().yMpt)
            assertEquals(innerTop + 10_000 + lineH + ascent, body.first { it.page == p }.baseline, "page ${p + 1}: the body below both headers")
        }
        assertEquals(listOf(56, 56, 38), pages.indices.map { p -> body.count { it.page == p } })
    }

    @Test
    fun headerBoxInsideALane() {
        val pages = layout(pdfDocument(cfg) {
            textLines("P", 20)
            row(gap = 16.dp, split = true, keepTogether = false) {
                cell(1f) {
                    box(border = 1.dp, borderColor = ink, padding = 6.dp, keepTogether = false, continuationHeader = { text("Cell (continued)", style) }) {
                        textLines("N", 100)
                    }
                }
                cell(1f) { textLines("R", 80) }
            }
        }, book)
        assertEquals(3, pages.size)
        val n = reading(lines(pages, labels("N", 100)))
        val r = reading(lines(pages, labels("R", 80)))
        assertEquals(labels("N", 100), n.map { it.label })
        assertEquals(labels("R", 80), r.map { it.label })
        val headers = lines(pages, listOf("Cell (continued)"))
        assertEquals(listOf(1, 2), headers.map { it.page })
        for (h in headers) {
            assertEquals(margin + 6_000, h.x)
            assertEquals(frameTop + 6_000 + ascent, h.baseline)
            assertEquals(h.baseline + lineH, n.first { it.page == h.page }.baseline, "page ${h.page + 1}: the cell's box content below its header")
            assertEquals(frameTop, borders(pages[h.page], ink).single().yMpt)
        }
        assertEquals(frameTop + ascent, r.first { it.page == 1 }.baseline, "the other cell has no header: it resumes at the frame top")
        assertEquals(listOf(38, 57, 5), pages.indices.map { p -> n.count { it.page == p } })
    }

    private fun jpeg(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = AwtColor(0x80, 0x80, 0x80); g.fillRect(0, 0, 64, 48); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }

    @Test
    fun keepTogetherAndClampUseTheContinuationArea() {
        // Below the 6 pt padding and the 13 pt header a continuation page has 751 pt; the frame has
        // 770 pt. A 754 pt box fits the frame but not a continuation page, so it splits where it stands.
        val inner = Color(0xFF22AA22)
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = ink, padding = 6.dp, continuationHeader = { text("Title (continued)", style) }) {
                textLines("L", 20)
                box(border = 1.dp, borderColor = inner) { textLines("B", 58) }
            }
        }, book)
        val b0 = lines(pages, listOf("B-0")).single()
        assertEquals(0, b0.page, "the inner box starts where it stands")
        assertEquals(frameTop + 6_000 + 20 * lineH + ascent, b0.baseline)
        assertEquals(labels("B", 58), reading(lines(pages, labels("B", 58))).map { it.label })
        assertEquals(frameTop + 6_000 + lineH, borders(pages[1], inner).single().yMpt, "page 2: the inner fragment starts below the header")

        // An image taller than a page is clamped to the continuation area: it ends at the content bottom.
        val clamped = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = ink, padding = 6.dp, continuationHeader = { text("Title (continued)", style) }) {
                textLines("L", 10)
                image(jpeg(), height = 2000.dp)
            }
        }, book)
        val img = clamped[1].ops.filterIsInstance<ImageOp>().single()
        assertEquals(frameTop + 6_000 + lineH, img.yMpt, "below the header")
        assertEquals(bottom, img.yMpt + img.hMpt)
        assertEquals(1, lines(clamped, listOf("Title (continued)")).single().page)
    }

    @Test
    fun headerFitFollowsThePageEntered() {
        // Footer 400 pt on odd pages, 13 pt on even ones (+ 12 pt gap): content bottoms at 394 / 781 pt.
        // Below the 20 pt top padding a continuation page has 338 pt (odd) or 725 pt (even), so the
        // 348 pt header fits even pages only. Measured from above the padding (358 pt) it would also
        // fit odd ones; measured against the page being left, odd and even would swap.
        val tallHeader: ContainerScope.() -> Unit = { text("Title (continued)", style); spacer(335.dp) }
        val spec = pdfDocument(cfg) {
            footer {
                text("Page ${page.number} / ${page.total}", style)
                if (page.number % 2 == 1) spacer(387.dp)
            }
            box(border = 1.dp, borderColor = ink, padding = 20.dp, continuationHeader = tallHeader) { textLines("L", 100) }
        }
        val pages = layout(spec, book)
        assertEquals(4, pages.size)
        fun bottomOf(p: Int) = if (p % 2 == 0) 394_000 else 781_000
        val headers = lines(pages, listOf("Title (continued)"))
        assertEquals(listOf(1, 3), headers.map { it.page }, "drawn on the even continuation pages, skipped on the odd one")
        for (h in headers) assertEquals(frameTop + 20_000 + ascent, h.baseline)
        val body = reading(lines(pages, labels("L", 100)))
        assertEquals(labels("L", 100), body.map { it.label })
        for (p in 1 until pages.size) {
            val entry = frameTop + 20_000 + (if (p % 2 == 1) 348_000 else 0)
            assertEquals(entry + ascent, body.first { it.page == p }.baseline, "page ${p + 1}: content starts below what this page reserves")
            assertEquals(frameTop, borders(pages[p], ink).single().yMpt)
        }
        for (line in body) {
            assertTrue(line.baseline - ascent + lineH <= bottomOf(line.page), "${line.label} crosses its page's content bottom")
        }
        assertEquals(listOf(26, 29, 26, 19), pages.indices.map { p -> body.count { it.page == p } })
        val footers = lines(pages, pages.indices.map { "Page ${it + 1} / ${pages.size}" })
        assertEquals(pages.indices.toList(), footers.map { it.page }, "every footer shows the true total")
    }

    @Test
    fun headerFitIsMeasuredBelowTheTopPadding() {
        // Below the 20 pt top padding a continuation page has 750 pt. The 760 pt header would fit the
        // 770 pt from the fragment's top, but not the room it would actually get: it is skipped.
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = ink, padding = 20.dp, continuationHeader = { text("Title (continued)", style); spacer(747.dp) }) {
                text("Title", style)
                textLines("L", 80)
            }
        }, book)
        assertEquals(2, pages.size)
        assertTrue(lines(pages, listOf("Title (continued)")).isEmpty(), "skipped")
        val body = reading(lines(pages, labels("L", 80)))
        assertEquals(labels("L", 80), body.map { it.label })
        assertEquals(frameTop + 20_000 + ascent, body.first { it.page == 1 }.baseline, "page 2: content right below the padding")
        assertEquals(listOf(56, 24), pages.indices.map { p -> body.count { it.page == p } })
    }

    @Test
    fun headerLeavingNoRoomKeepsAFinitePageCount() {
        // Page 1 holds 58 lines below the 16 pt padding, ending exactly at the bottom. The 750 pt header
        // fits the 754 pt below the padding but leaves 4 pt, less than a line: each continuation page
        // holds the header and one line (running past the bottom); the flow still ends.
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = ink, padding = 16.dp, continuationHeader = { text("Title (continued)", style); spacer(737.dp) }) {
                textLines("L", 62)
            }
            text("After", style)
        }, book)
        assertEquals(6, pages.size)
        assertEquals(listOf(1, 2, 3, 4), lines(pages, listOf("Title (continued)")).map { it.page })
        val body = reading(lines(pages, labels("L", 62)))
        assertEquals(labels("L", 62), body.map { it.label })
        assertEquals(58, body.count { it.page == 0 })
        for (p in 1..4) {
            val onPage = body.filter { it.page == p }
            assertEquals(listOf("L-${57 + p}"), onPage.map { it.label }, "page ${p + 1}: one line")
            assertEquals(frameTop + 16_000 + 750_000 + ascent, onPage.single().baseline)
        }
        val after = lines(pages, listOf("After")).single()
        assertEquals(5, after.page)
        assertEquals(frameTop + ascent, after.baseline)
    }

    @Test
    fun emptyHeaderIsNoHeader() {
        // 116 lines end page 2 at 796 pt and the trailing 40 pt spacer crosses the bottom: a plain box
        // breaks eagerly (a padding-only fragment on page 3), a header box would drop the spacer.
        fun ContainerScope.content() { textLines("L", 116); spacer(40.dp) }
        fun doc(empty: Boolean) = pdfDocument(cfg) {
            if (empty) {
                box(padding = 6.dp, border = 1.dp, borderColor = ink, continuationHeader = {}) { content() }
            } else {
                box(padding = 6.dp, border = 1.dp, borderColor = ink) { content() }
            }
            text("After", style)
        }
        val plain = layout(doc(empty = false), book)
        assertEquals(1, borders(plain[2], ink).size, "the case tells eager from deferred spacing")
        assertContentEquals(doc(empty = false).render(regular(), bold()), doc(empty = true).render(regular(), bold()))
    }

    @Test
    fun noHeaderIsByteIdentical() {
        fun doc(explicit: Boolean) = pdfDocument(cfg) {
            textLines("P", 20)
            if (explicit) {
                box(padding = 6.dp, border = 1.dp, background = Color(0xFFEEEEEE), continuationHeader = null) { textLines("L", 120); spacer(40.dp) }
            } else {
                box(padding = 6.dp, border = 1.dp, background = Color(0xFFEEEEEE)) { textLines("L", 120); spacer(40.dp) }
            }
            text("After", style)
        }.render(regular(), bold())
        assertContentEquals(doc(false), doc(true))
    }
}
