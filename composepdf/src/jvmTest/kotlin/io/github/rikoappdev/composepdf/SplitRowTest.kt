package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.rowCellWidths
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

/** Split rows: each cell of `row(split = true)` flows on its own across pages. */
class SplitRowTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val book by lazy { FontBook(regular(), bold()) }

    private val style = TextStyle(fontSize = 10.sp)
    private val cfg = PageConfig(pageNumbers = false)

    // A4 with 36 pt margins and no bands: content from 36 pt to 806 pt, 523 pt wide.
    private val frameTop = 36_000
    private val bottom = 806_000
    private val margin = 36_000
    private val contentW = 523_000
    private val lineH = 13_000 // floor(10 pt x 1.3)
    private val ascent by lazy { book.ascentMpt(FontWeight.Normal, 10_000) }

    /** One placed text line: its page index, label, x and baseline (millipoints). */
    private data class Line(val page: Int, val label: String, val x: Int, val baseline: Int)

    private fun labels(prefix: String, n: Int) = List(n) { "$prefix-$it" }

    /** The text ops of [pages] that spell one of [labels] in [weight], in page order then op order. */
    private fun lines(pages: List<Page>, labels: Iterable<String>, weight: FontWeight = FontWeight.Normal): List<Line> {
        val byGlyphs = labels.associateBy { book.shape(it, weight).toList() }
        return pages.flatMapIndexed { i, page ->
            page.ops.filterIsInstance<TextOp>().filter { it.weight == weight }.mapNotNull { op ->
                byGlyphs[op.gids.toList()]?.let { Line(i, it, op.xMpt, op.baselineYMpt) }
            }
        }
    }

    /** [lines] in reading order: by page, then top to bottom. */
    private fun reading(lines: List<Line>) = lines.sortedWith(compareBy({ it.page }, { it.baseline }))

    private fun ContainerScope.textLines(prefix: String, n: Int) = labels(prefix, n).forEach { text(it, style) }

    private fun jpeg(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = AwtColor(0x80, 0x80, 0x80); g.fillRect(0, 0, 64, 48); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }

    @Test
    fun unevenColumnsContinueIndependently() {
        // Tall vertical margins leave 422 pt (32 lines): 60 lines from mid-page 1 cross three pages.
        val tall = PageConfig(pageNumbers = false, margins = PageMargins(horizontal = 36.dp, vertical = 210.dp))
        val top = 210_000
        val tallBottom = 632_000
        val spec = pdfDocument(tall) {
            textLines("P", 12)
            row(gap = 16.dp, split = true, keepTogether = false) {
                cell(1f) { textLines("L", 60) }
                cell(1f) { textLines("R", 30) }
            }
        }
        val pages = layout(spec, book)
        assertEquals(3, pages.size)
        val left = reading(lines(pages, labels("L", 60)))
        val right = reading(lines(pages, labels("R", 30)))
        assertEquals(labels("L", 60), left.map { it.label }, "every left line once, in order")
        assertEquals(labels("R", 30), right.map { it.label }, "every right line once, in order")

        val rowTop = top + 12 * lineH
        assertEquals(0, left.first().page)
        assertEquals(0, right.first().page)
        assertEquals(rowTop + ascent, left.first().baseline)
        assertEquals(rowTop + ascent, right.first().baseline)
        var shared = 0
        for (p in 1 until pages.size) {
            val l = left.firstOrNull { it.page == p } ?: continue
            val r = right.firstOrNull { it.page == p } ?: continue
            assertEquals(top + ascent, l.baseline, "page ${p + 1}: the left cell resumes at the content top")
            assertEquals(l.baseline, r.baseline, "page ${p + 1}: both cells resume at the same y")
            shared++
        }
        assertEquals(1, shared, "page 2 holds both cells")
        assertEquals(2, left.last().page, "the longer cell reaches page 3")

        val widths = rowCellWidths(spec.nodes.last() as RowNode, contentW, book)
        assertTrue(left.all { it.x == margin })
        assertTrue(right.all { it.x == margin + widths[0] + 16_000 })
        for (line in left + right) {
            assertTrue(line.baseline - ascent + lineH <= tallBottom, "${line.label} crosses the content bottom")
        }
    }

    @Test
    fun splitRowThatFitsIsByteIdenticalToAtomicRow() {
        fun doc(split: Boolean, align: VerticalAlignment, before: Int) = pdfDocument(cfg) {
            textLines("P", before)
            row(gap = 8.dp, verticalAlignment = align, split = split) {
                cell(1f) { textLines("L", 20) }
                cell(0f) { text("R-0", style) }
                cell(2f) { box(padding = 4.dp, border = 1.dp, background = Color(0xFFEEEEEE)) { text("Row 7", style) } }
            }
            text("After", style)
        }.render(regular(), bold())
        for (align in listOf(VerticalAlignment.Top, VerticalAlignment.Center, VerticalAlignment.Bottom)) {
            // Fits where it stands.
            assertContentEquals(doc(false, align, 3), doc(true, align, 3), "fits: $align")
            // Fits only after moving whole to the next page.
            assertContentEquals(doc(false, align, 50), doc(true, align, 50), "fits after moving: $align")
        }
    }

    private fun roomDoc(config: PageConfig, keepTogether: Boolean?) = pdfDocument(config) {
        textLines("P", 44) // 572 pt of 770 pt: 198 pt left
        row(gap = 16.dp, split = true, keepTogether = keepTogether) {
            cell(1f) { textLines("L", 30) } // 390 pt: fits an empty page, not the room left
            cell(1f) { textLines("R", 20) }
        }
    }

    @Test
    fun keepTogetherMovesAFittingSplitRowWhole() {
        val loose = PageConfig(pageNumbers = false, keepBlocksTogether = false)
        for ((config, keep) in listOf(cfg to null, cfg to true, loose to true)) {
            val pages = layout(roomDoc(config, keep), book)
            val left = reading(lines(pages, labels("L", 30)))
            val right = reading(lines(pages, labels("R", 20)))
            assertEquals(2, pages.size)
            assertTrue(left.all { it.page == 1 } && right.all { it.page == 1 }, "keepTogether = $keep: whole on page 2")
            assertEquals(frameTop + ascent, left.first().baseline)
            assertEquals(frameTop + ascent, right.first().baseline)
        }
    }

    @Test
    fun keepTogetherFalseFillsTheCurrentPage() {
        val pages = layout(roomDoc(cfg, false), book)
        val left = reading(lines(pages, labels("L", 30)))
        assertEquals(labels("L", 30), left.map { it.label })
        assertEquals(0, left.first().page)
        assertEquals(frameTop + 44 * lineH + ascent, left.first().baseline)
        assertEquals(15, left.count { it.page == 0 }, "198 pt hold 15 lines")
        assertEquals(frameTop + ascent, left.first { it.page == 1 }.baseline)

        // null follows a document that does not keep blocks together.
        val loose = layout(roomDoc(PageConfig(pageNumbers = false, keepBlocksTogether = false), null), book)
        assertEquals(0, reading(lines(loose, labels("L", 30))).first().page)
    }

    @Test
    fun centerAlignmentFallsBackToTopOnlyWhenSplit() {
        val rowTop = frameTop + lineH
        val split = layout(pdfDocument(cfg) {
            text("Intro", style)
            row(verticalAlignment = VerticalAlignment.Center, split = true) {
                cell(1f) { textLines("L", 80) }
                cell(1f) { textLines("R", 3) }
            }
        }, book)
        val l0 = lines(split, listOf("L-0")).single()
        val r0 = lines(split, listOf("R-0")).single()
        assertEquals(0, l0.page)
        assertEquals(0, r0.page)
        assertEquals(rowTop + ascent, l0.baseline)
        assertEquals(rowTop + ascent, r0.baseline, "a row that splits is top-aligned")

        val fits = layout(pdfDocument(cfg) {
            text("Intro", style)
            row(verticalAlignment = VerticalAlignment.Center, split = true) {
                cell(1f) { textLines("L", 6) }
                cell(1f) { textLines("R", 2) }
            }
        }, book)
        val centred = lines(fits, listOf("R-0")).single()
        assertEquals(rowTop + halfTrunc(6 * lineH - 2 * lineH) + ascent, centred.baseline, "a split row that fits keeps its alignment")
    }

    @Test
    fun splitRowUnderMinimumRoomOpensOnNextPage() {
        val pages = layout(pdfDocument(cfg) {
            textLines("P", 58) // 754 pt: 16 pt left, room for a line but under 28 pt
            row(split = true, keepTogether = false) {
                cell(1f) { textLines("L", 80) }
                cell(1f) { textLines("R", 5) }
            }
        }, book)
        assertEquals(0, lines(pages, listOf("P-57")).single().page)
        val l0 = lines(pages, listOf("L-0")).single()
        val r0 = lines(pages, listOf("R-0")).single()
        assertEquals(1, l0.page)
        assertEquals(1, r0.page)
        assertEquals(frameTop + ascent, l0.baseline)
        assertEquals(frameTop + ascent, r0.baseline)
    }

    @Test
    fun nestedContentSplitsInsideACell() {
        val ink = Color(0xFFAA2222)
        val pages = layout(pdfDocument(cfg) {
            textLines("P", 20)
            row(gap = 16.dp, split = true, keepTogether = false) {
                cell(1f) {
                    text("Head", style)
                    box(border = 1.dp, borderColor = ink, padding = 4.dp, keepTogether = false) { textLines("B", 50) }
                    table(listOf(PdfColumn(1f, "Item"), PdfColumn(1f, "Qty"))) { labels("T", 40).forEach { row(it, "1") } }
                    row(gap = 8.dp, split = true, keepTogether = false) {
                        cell(1f) { textLines("N", 60) }
                        cell(1f) { textLines("M", 60) }
                    }
                }
                cell(1f) { textLines("R", 10) }
            }
        }, book)

        for ((prefix, n) in listOf("P" to 20, "B" to 50, "T" to 40, "N" to 60, "M" to 60, "R" to 10)) {
            assertEquals(labels(prefix, n), reading(lines(pages, labels(prefix, n))).map { it.label }, "$prefix lines once, in order")
        }
        assertEquals(lines(pages, listOf("Head")).single().baseline, lines(pages, listOf("R-0")).single().baseline)

        // The box splits inside the cell: one border fragment per page it spans.
        val boxPages = lines(pages, labels("B", 50)).map { it.page }.distinct()
        assertTrue(boxPages.size >= 2, "the box splits")
        for ((p, page) in pages.withIndex()) {
            val borders = page.ops.filterIsInstance<RectOp>().filter { it.stroke === ink }
            assertEquals(if (p in boxPages) 1 else 0, borders.size, "page ${p + 1}: border fragments")
            if (p in boxPages && p != boxPages.first()) {
                assertEquals(frameTop, borders.single().yMpt, "page ${p + 1}: the fragment starts at the content top")
                assertEquals(margin, borders.single().xMpt)
            }
        }

        // The table splits row by row and repeats its header at the top of each page.
        val rows = lines(pages, labels("T", 40))
        val headers = lines(pages, listOf("Item"), FontWeight.Bold)
        val tablePages = rows.map { it.page }.distinct()
        assertTrue(tablePages.size >= 2, "the table splits")
        assertEquals(tablePages, headers.map { it.page }, "one header on each page with table rows")
        for (p in tablePages) {
            assertTrue(headers.single { it.page == p }.baseline < rows.filter { it.page == p }.minOf { it.baseline })
        }

        // The nested split row: both inner cells start together and resume together.
        val n = reading(lines(pages, labels("N", 60)))
        val m = reading(lines(pages, labels("M", 60)))
        assertEquals(n.first().page, m.first().page)
        assertEquals(n.first().baseline, m.first().baseline)
        var resumed = 0
        for (p in (n.first().page + 1) until pages.size) {
            val fn = n.firstOrNull { it.page == p } ?: continue
            val fm = m.firstOrNull { it.page == p } ?: continue
            assertEquals(frameTop + ascent, fn.baseline, "page ${p + 1}: nested lanes resume at the content top")
            assertEquals(fn.baseline, fm.baseline, "page ${p + 1}: nested lanes resume at the same y")
            resumed++
        }
        assertTrue(resumed >= 1, "the nested row splits")
    }

    @Test
    fun enclosingBoxesGetOneFragmentPerSpannedPage() {
        val outer = Color(0xFF2222AA)
        val inner = Color(0xFF22AA22)
        val fill = Color(0xFFF1F3F5)
        val pages = layout(pdfDocument(cfg) {
            box(border = 1.dp, borderColor = outer, padding = 6.dp) {
                box(border = 1.dp, borderColor = inner, padding = 10.dp, background = fill) {
                    row(gap = 16.dp, split = true) {
                        cell(1f) { textLines("L", 150) }
                        cell(1f) { textLines("R", 70) }
                    }
                }
            }
            text("After", style)
        }, book)
        assertEquals(3, pages.size)
        val entry = frameTop + 16_000 // below both boxes' top padding
        val left = reading(lines(pages, labels("L", 150)))
        val right = reading(lines(pages, labels("R", 70)))
        assertEquals(labels("L", 150), left.map { it.label })
        assertEquals(labels("R", 70), right.map { it.label })
        assertEquals(entry + ascent, left.first().baseline)
        for (p in 1 until pages.size) assertEquals(entry + ascent, left.first { it.page == p }.baseline, "page ${p + 1}")
        assertEquals(entry + ascent, right.first { it.page == 1 }.baseline)

        for ((p, page) in pages.withIndex()) {
            val rects = page.ops.filterIsInstance<RectOp>()
            val o = rects.single { it.stroke === outer }
            val i = rects.single { it.stroke === inner }
            assertEquals(1, rects.count { it.fill === fill }, "page ${p + 1}: one background fragment")
            assertEquals(frameTop, o.yMpt)
            assertEquals(frameTop + 6_000, i.yMpt)
            if (p < pages.lastIndex) {
                assertEquals(bottom, o.yMpt + o.hMpt, "page ${p + 1}: outer fragment runs to the content bottom")
                assertEquals(bottom, i.yMpt + i.hMpt, "page ${p + 1}: inner fragment runs to the content bottom")
            }
        }
        // The row ends below its longest cell; the boxes close there and the flow continues below.
        val rowEnd = left.last().baseline - ascent + lineH
        val lastInner = pages.last().ops.filterIsInstance<RectOp>().single { it.stroke === inner }
        assertEquals(rowEnd + 10_000, lastInner.yMpt + lastInner.hMpt)
        val after = lines(pages, listOf("After")).single()
        assertEquals(pages.lastIndex, after.page)
        assertEquals(rowEnd + 16_000 + ascent, after.baseline)
    }

    @Test
    fun contentSizedCellKeepsItsWidth() {
        val spec = pdfDocument(cfg) {
            row(gap = 8.dp, split = true) {
                cell(0f) { text("Label", style) }
                cell(1f) { textLines("V", 80) }
            }
        }
        val pages = layout(spec, book)
        val widths = rowCellWidths(spec.nodes.single() as RowNode, contentW, book)
        assertEquals(book.measureWidthMpt("Label", FontWeight.Normal, 10_000), widths[0])
        assertEquals(margin, lines(pages, listOf("Label")).single().x)
        val values = reading(lines(pages, labels("V", 80)))
        assertEquals(labels("V", 80), values.map { it.label })
        assertEquals(2, values.map { it.page }.distinct().size)
        assertTrue(values.all { it.x == margin + widths[0] + 8_000 }, "the weighted cell keeps its x on every page")
    }

    @Test
    fun cellWithHugeSpacerEndsOnItsPage() {
        val plain = layout(pdfDocument(cfg) {
            row(split = true) {
                cell(1f) { text("L-0", style); spacer(2000.dp) }
                cell(1f) { text("R-0", style) }
            }
        }, book)
        assertEquals(1, plain.size)

        val boxed = layout(pdfDocument(cfg) {
            row(split = true) {
                cell(1f) { box(padding = 6.dp, border = 1.dp) { text("L-0", style); spacer(2000.dp) } }
                cell(1f) { text("R-0", style) }
            }
        }, book)
        assertEquals(1, boxed.size)
    }

    @Test
    fun splitRowWithEmptyCells() {
        fun doc(split: Boolean, cells: Boolean) = pdfDocument(cfg) {
            text("Intro", style)
            row(split = split) { if (cells) { cell {}; cell {} } }
            text("After", style)
        }
        for (cells in listOf(true, false)) {
            assertEquals(1, layout(doc(true, cells), book).size)
            assertContentEquals(doc(false, cells).render(regular(), bold()), doc(true, cells).render(regular(), bold()), "cells = $cells")
        }
    }

    @Test
    fun imageTallerThanLaneIsClampedToLaneArea() {
        val pages = layout(pdfDocument(cfg) {
            box(padding = 20.dp) {
                row(split = true, keepTogether = false) {
                    cell(1f) { image(jpeg(), height = 2000.dp) }
                    cell(1f) { textLines("L", 80) }
                }
            }
        }, book)
        val images = pages.flatMap { it.ops.filterIsInstance<ImageOp>() }
        val img = images.single()
        val laneTop = frameTop + 20_000 // the lane's area starts below the box's top padding
        assertEquals(bottom - laneTop, img.hMpt)
        assertEquals(laneTop, img.yMpt)
        assertTrue(img.yMpt + img.hMpt <= bottom)
        assertEquals(80, lines(pages, labels("L", 80)).size)
    }

    @Test
    fun keepTogetherInsideALaneUsesTheLaneArea() {
        // The lane's area is 750 pt (below the outer box's 20 pt padding); the inner box is 754 pt tall.
        // It fits the frame's 770 pt but not the lane's area, so it must split from the lane's top
        // instead of moving to page 2 and leaving the cell empty on page 1.
        val ink = Color(0xFFAA2222)
        val pages = layout(pdfDocument(cfg) {
            box(padding = 20.dp) {
                row(split = true, keepTogether = false) {
                    cell(1f) { box(border = 1.dp, borderColor = ink) { textLines("B", 58) } }
                    cell(1f) { textLines("R", 80) }
                }
            }
        }, book)
        val b0 = lines(pages, listOf("B-0")).single()
        assertEquals(0, b0.page)
        assertEquals(frameTop + 20_000 + ascent, b0.baseline)
        assertEquals(labels("B", 58), reading(lines(pages, labels("B", 58))).map { it.label })
        assertEquals(frameTop + 20_000, pages[1].ops.filterIsInstance<RectOp>().single { it.stroke === ink }.yMpt)
    }

    @Test
    fun boxOpenedInALaneWrapsANestedSplitRow() {
        val ink = Color(0xFFAA2222)
        val pages = layout(pdfDocument(cfg) {
            row(gap = 16.dp, split = true) {
                cell(1f) {
                    box(border = 1.dp, borderColor = ink, padding = 8.dp) {
                        row(gap = 8.dp, split = true) {
                            cell(1f) { textLines("N", 150) }
                            cell(1f) { textLines("M", 100) }
                        }
                    }
                }
                cell(1f) { textLines("R", 10) }
            }
            text("After", style)
        }, book)
        assertEquals(3, pages.size)
        val n = reading(lines(pages, labels("N", 150)))
        val m = reading(lines(pages, labels("M", 100)))
        assertEquals(labels("N", 150), n.map { it.label })
        assertEquals(labels("M", 100), m.map { it.label })
        assertEquals(labels("R", 10), reading(lines(pages, labels("R", 10))).map { it.label })

        val entry = frameTop + 8_000 // below the lane's box padding, on every page
        assertEquals(entry + ascent, n.first().baseline)
        assertEquals(entry + ascent, m.first().baseline)
        for (p in 1 until pages.size) {
            assertEquals(entry + ascent, n.first { it.page == p }.baseline, "page ${p + 1}: nested left lane")
        }
        assertEquals(entry + ascent, m.first { it.page == 1 }.baseline, "page 2: nested right lane")

        val rowEnd = n.last().baseline - ascent + lineH
        for ((p, page) in pages.withIndex()) {
            val border = page.ops.filterIsInstance<RectOp>().filter { it.stroke === ink }
            assertEquals(1, border.size, "page ${p + 1}: one fragment of the lane's box")
            assertEquals(frameTop, border.single().yMpt)
            assertEquals(margin, border.single().xMpt)
            val fragBottom = border.single().yMpt + border.single().hMpt
            assertEquals(if (p < pages.lastIndex) bottom else rowEnd + 8_000, fragBottom, "page ${p + 1}: fragment bottom")
        }
        val after = lines(pages, listOf("After")).single()
        assertEquals(pages.lastIndex, after.page)
        assertEquals(rowEnd + 8_000 + ascent, after.baseline)
    }

    @Test
    fun lanesFollowEachPagesFrameUnderPageAwareBands() {
        // Header 60 pt on odd pages, 20 pt on even ones (+ 12 pt gap); footer 13 pt (+ 12 pt gap).
        val spec = pdfDocument(cfg) {
            header {
                if (page.number % 2 == 1) { text("Odd", style); spacer(47.dp) } else { text("Even", style); spacer(7.dp) }
            }
            footer { text("Page ${page.number} / ${page.total}", style) }
            row(gap = 16.dp, split = true) {
                cell(1f) { textLines("L", 150) }
                cell(1f) { textLines("R", 100) }
            }
        }
        val pages = layout(spec, book)
        assertEquals(3, pages.size)
        fun topOf(p: Int) = if (p % 2 == 0) 36_000 + 60_000 + 12_000 else 36_000 + 20_000 + 12_000
        val usable = 842_000 - 36_000 - 13_000 - 12_000
        val left = reading(lines(pages, labels("L", 150)))
        val right = reading(lines(pages, labels("R", 100)))
        assertEquals(labels("L", 150), left.map { it.label })
        assertEquals(labels("R", 100), right.map { it.label })
        for (p in pages.indices) {
            assertEquals(topOf(p) + ascent, left.first { it.page == p }.baseline, "page ${p + 1}: left lane at this page's content top")
            right.firstOrNull { it.page == p }?.let { assertEquals(topOf(p) + ascent, it.baseline, "page ${p + 1}: right lane") }
        }
        assertEquals(2, right.map { it.page }.distinct().size)
        for (line in left + right) {
            assertTrue(line.baseline - ascent >= topOf(line.page), "${line.label} above its page's content top")
            assertTrue(line.baseline - ascent + lineH <= usable, "${line.label} crosses its page's content bottom")
        }
        // Fill: each page holds as many lines as its own frame allows (673 pt -> 51, 713 pt -> 54).
        assertEquals(listOf(51, 54, 45), pages.indices.map { p -> left.count { it.page == p } })
        val footers = lines(pages, pages.indices.map { "Page ${it + 1} / ${pages.size}" })
        assertEquals(pages.indices.toList(), footers.map { it.page }, "every footer shows the true total")
    }

    @Test
    fun spacerMidCellCrossingTheBottomBreaksLikeTheEagerBreak() {
        // 55 lines end at 761 pt; the 100 pt spacer crosses the 806 pt bottom; text follows it.
        fun ContainerScope.content() { textLines("A", 55); spacer(100.dp); textLines("B", 20) }
        val split = layout(pdfDocument(cfg) {
            box(padding = 10.dp, border = 1.dp) {
                row(split = true) {
                    cell(1f) { content() }
                    cell(1f) { textLines("R", 5) }
                }
            }
        }, book)
        assertEquals(2, split.size, "no extra page")
        val b = reading(lines(split, labels("B", 20)))
        assertEquals(labels("B", 20), b.map { it.label })
        assertTrue(b.all { it.page == 1 })
        assertEquals(frameTop + 10_000 + ascent, b.first().baseline, "the next page's entry y")

        // The same content flowed eagerly (no row) breaks to the same place.
        val eager = layout(pdfDocument(cfg) { box(padding = 10.dp, border = 1.dp) { content() } }, book)
        assertEquals(eager.size, split.size)
        val place = { pages: List<Page> -> reading(lines(pages, labels("A", 55) + labels("B", 20))).map { it.page to it.baseline } }
        assertEquals(place(eager), place(split))

        // A spacer taller than a page mid-cell drops at the break: no blank page.
        val huge = layout(pdfDocument(cfg) {
            row(split = true) {
                cell(1f) { textLines("A", 3); spacer(2000.dp); textLines("B", 2) }
                cell(1f) { textLines("R", 2) }
            }
        }, book)
        assertEquals(2, huge.size)
        assertEquals(1, lines(huge, listOf("B-0")).single().page)
        assertEquals(frameTop + ascent, lines(huge, listOf("B-0")).single().baseline)
    }

    private fun ContainerScope.fullRow(n: Int) = row(split = true) {
        cell(1f) { textLines("L", n); spacer(30.dp) }
        cell(1f) { textLines("R", n); spacer(30.dp) }
    }

    @Test
    fun trailingSpacingDoesNotContinueCells() {
        // 59 lines end 3 pt above the content bottom; the trailing 30 pt spacer crosses it.
        assertEquals(1, layout(pdfDocument(cfg) { fullRow(59) }, book).size, "the row ends on its page")

        val after = layout(pdfDocument(cfg) { fullRow(59); text("After", style) }, book)
        assertEquals(2, after.size)
        val first = after[1].ops.first() as TextOp
        assertContentEquals(book.shape("After", FontWeight.Normal), first.gids)
        assertEquals(frameTop + ascent, first.baselineYMpt)

        // Inside a padded, bordered box (58 lines below its 6 pt padding): no fragment on page 2.
        val boxed = layout(pdfDocument(cfg) {
            box(padding = 6.dp, border = 1.dp) { fullRow(58) }
            text("After", style)
        }, book)
        assertEquals(2, boxed.size)
        assertTrue(boxed[1].ops.none { it is RectOp }, "no box fragment on page 2")
        val firstBoxed = boxed[1].ops.first() as TextOp
        assertContentEquals(book.shape("After", FontWeight.Normal), firstBoxed.gids)
        assertEquals(frameTop + ascent, firstBoxed.baselineYMpt)
    }

    @Test
    fun splitRowDeterministic() {
        fun render() = pdfDocument(cfg) {
            textLines("P", 12)
            row(gap = 16.dp, split = true) {
                cell(1f) { textLines("L", 60) }
                cell(1f) { box(padding = 4.dp, border = 1.dp, background = Color(0xFFEEEEEE)) { textLines("R", 90) } }
            }
            text("After", style)
        }.render(regular(), bold())
        assertContentEquals(render(), render())
    }
}
