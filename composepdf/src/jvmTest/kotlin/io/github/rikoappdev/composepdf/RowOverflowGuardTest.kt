package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.layout.ColumnNode
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.rowCellWidths
import io.github.rikoappdev.composepdf.pdf.serializePdf
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.text.LineBreaker
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The row safety net: a non-split row that would still cross the content bottom after today's move
 * decision is laid out like a split row from there, and a document whose accepted layout has no such
 * row keeps its exact bytes, page-aware bands included.
 */
class RowOverflowGuardTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val book by lazy { FontBook(regular(), bold()) }

    private val style = TextStyle(fontSize = 10.sp)
    private val cfg = PageConfig(pageNumbers = false)

    // A4 with 36 pt margins and no bands: content from 36 pt to 806 pt, 523 pt wide.
    private val frameTop = 36_000
    private val margin = 36_000
    private val bottom = 806_000
    private val contentW = 523_000
    private val lineH = 13_000 // floor(10 pt x 1.3)
    private val ascent by lazy { book.ascentMpt(FontWeight.Normal, 10_000) }

    /** One placed text line: its page index, label, baseline and x (millipoints). */
    private data class Line(val page: Int, val label: String, val baseline: Int, val x: Int)

    private fun labels(prefix: String, n: Int) = List(n) { "$prefix-$it" }

    /** The text ops of [pages] that spell one of [labels], in page order then op order. */
    private fun lines(pages: List<Page>, labels: Iterable<String>, weight: FontWeight = FontWeight.Normal): List<Line> {
        val byGlyphs = labels.associateBy { book.shape(it, weight).toList() }
        return pages.flatMapIndexed { i, page ->
            page.ops.filterIsInstance<TextOp>().filter { it.weight == weight }.mapNotNull { op ->
                byGlyphs[op.gids.toList()]?.let { Line(i, it, op.baselineYMpt, op.xMpt) }
            }
        }
    }

    private fun reading(lines: List<Line>) = lines.sortedWith(compareBy({ it.page }, { it.baseline }))

    private fun ContainerScope.textLines(prefix: String, n: Int) = labels(prefix, n).forEach { text(it, style) }

    private fun jpeg(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = AwtColor(0x80, 0x80, 0x80); g.fillRect(0, 0, 64, 48); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }

    private fun layoutOf(spec: PdfDocumentSpec, overflowGuard: Boolean = true, maxSettlePasses: Int = 50) =
        layoutDocument(spec, FontBook(regular(), bold()), RenderImageTable(spec.images), maxSettlePasses, overflowGuard)

    /** What [PdfDocumentSpec.render] does, with the row safety net forced off when [overflowGuard] is false. */
    private fun renderBytes(spec: PdfDocumentSpec, overflowGuard: Boolean, emoji: ByteArray? = null, maxSettlePasses: Int = 50): ByteArray {
        val table = RenderImageTable(spec.images)
        val book = FontBook(regular(), bold(), emoji, imageTable = table)
        val result = layoutDocument(spec, book, table, maxSettlePasses, overflowGuard)
        return serializePdf(result.pages, book, result.images)
    }

    private fun streamed(spec: PdfDocumentSpec, emoji: ByteArray? = null): ByteArray =
        ByteArrayOutputStream().also { out -> spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold(), emoji) }.toByteArray()

    private fun png(w: Int, h: Int, rgb: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rgb)
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    /** Pixel sizes of every distinct image XObject the pages of [pdf] can draw. */
    private fun imageXObjects(pdf: ByteArray): List<Pair<Int, Int>> = Loader.loadPDF(pdf).use { d ->
        val byKey = LinkedHashMap<Any, Pair<Int, Int>>()
        for (page in d.pages) {
            val res = page.resources
            val dict = res.cosObject.getCOSDictionary(COSName.XOBJECT) ?: continue
            for (name in res.xObjectNames) {
                val img = res.getXObject(name) as? PDImageXObject ?: continue
                byKey.getOrPut((dict.getItem(name) as? COSObject)?.key ?: name) { img.width to img.height }
            }
        }
        byKey.values.toList()
    }

    /**
     * Fails if any op on [pages] ends below the content bottom [bottomOf] its page: a text line's box,
     * a rectangle (allowed [rectSlack] more — a box's closing padding), an image or a vector.
     */
    private fun assertNothingBelow(pages: List<Page>, bottomOf: (Int) -> Int = { bottom }, rectSlack: Int = 0) {
        for ((p, page) in pages.withIndex()) {
            val limit = bottomOf(p)
            for (op in page.ops) {
                val (what, end) = when (op) {
                    is TextOp -> "text" to op.baselineYMpt - book.ascentMpt(op.weight, op.fontSizeMpt) + lineHeightMpt(op.fontSizeMpt, 1.3)
                    is RectOp -> "rect" to op.yMpt + op.hMpt - rectSlack
                    is ImageOp -> "image" to op.yMpt + op.hMpt
                    is VectorOp -> "vector" to op.yMpt + op.hMpt
                    else -> continue
                }
                assertTrue(end <= limit, "page ${p + 1}: a $what op ends at $end, below the content bottom $limit")
            }
        }
    }

    private fun ContainerScope.tallRow() = row(gap = 12.dp) {
        cell(1f) { textLines("L", 120) } // 1560 pt: taller than any page
        cell(1f) { textLines("R", 40) }
    }

    @Test
    fun rowTallerThanPageNeverCrossesTheBottom() {
        for (boxed in listOf(false, true)) {
            val spec = pdfDocument(cfg) {
                text("Before", style)
                if (boxed) box(padding = 10.dp, border = 1.dp) { tallRow() } else tallRow()
                text("After", style)
            }
            val pages = layout(spec, book)
            val where = if (boxed) "in a box" else "top level"
            assertNothingBelow(pages, rectSlack = if (boxed) 10_000 else 0)

            val left = reading(lines(pages, labels("L", 120)))
            val right = reading(lines(pages, labels("R", 40)))
            assertEquals(labels("L", 120), left.map { it.label }, "$where: every left line once, in order")
            assertEquals(labels("R", 40), right.map { it.label }, "$where: every right line once, in order")
            // Today's move decision is kept: the row starts at the top of page 2, both cells side by side.
            val rowTop = frameTop + (if (boxed) 10_000 else 0)
            assertEquals(1, left.first().page, "$where: the row moved to the next page first")
            assertEquals(1, right.first().page)
            assertEquals(rowTop + ascent, left.first().baseline, "$where: the row starts at the top")
            assertEquals(left.first().baseline, right.first().baseline, "$where: both cells start at the same y")
            assertTrue(left.map { it.page }.distinct().size >= 3, "$where: the long cell spans several pages")
            val after = lines(pages, listOf("After")).single()
            assertEquals(left.last().page, after.page, "$where: the flow continues below the deepest cell")
            assertTrue(after.baseline > left.last().baseline)
        }
    }

    @Test
    fun validRowOnTallerNextPageIsUntouched() {
        fun ContainerScope.body() {
            text("Intro", style)
            row(gap = 6.dp) {
                cell(1f) { textLines("R", 50) } // 650 pt: not page 1's 536 pt area, but page 2's
                cell(1f) { text("Beside the tall cell", style) }
            }
            textLines("F", 20)
        }
        val title = TextStyle(fontSize = 20.sp)
        val docs = listOf(
            // A first-page title block: later pages reclaim its space.
            "repeatHeader = false" to pdfDocument(PageConfig(repeatHeader = false)) {
                header { text("Title block", title); spacer(174.dp) }
                body()
            },
            // A page-aware header that is tall on page 1 only.
            "page-aware header" to pdfDocument(PageConfig()) {
                header { if (page.number == 1) { text("Title block", title); spacer(174.dp) } else text("Running head", style) }
                body()
            },
        )
        for ((name, spec) in docs) {
            val result = layoutOf(spec)
            assertTrue(result.rowCrossings.none { it }, "$name: no pass draws a row past a content bottom")
            assertFalse(result.relaidWithGuard, "$name: laid out once")
            val rows = reading(lines(result.pages, labels("R", 50)))
            assertEquals(labels("R", 50), rows.map { it.label })
            assertTrue(rows.all { it.page == 1 }, "$name: the row moved whole to page 2")
            assertContentEquals(renderBytes(spec, overflowGuard = false), spec.render(regular(), bold()), name)
        }
    }

    @Test
    fun provisionalCrossingDoesNotChangeSettledLayout() {
        // Footer 13 pt on every page; the last page adds 123 pt, so content ends at 781 pt, or 658 pt on
        // the last page. The 702 pt row fits a 745 pt page but not the last page's 622 pt.
        val spec = pdfDocument(cfg) {
            footer {
                text("Footer ${page.number}/${page.total}", style)
                if (page.isLast) { spacer(110.dp); text("Closing block", style) }
            }
            textLines("I", 8)
            row(gap = 6.dp) {
                cell(1f) { textLines("R", 54) }
                cell(1f) { text("Almost a full page", style) }
            }
            // Long enough that, had pass 1 split the row, its count (4) would differ from the settled 3.
            textLines("T", 45)
        }
        val guarded = layoutOf(spec)
        val off = layoutOf(spec, overflowGuard = false)
        assertEquals(listOf(true, false), guarded.rowCrossings, "pass 1 (each page built as the last) crosses; the accepted pass does not")
        assertEquals(off.flowPasses, guarded.flowPasses, "same passes as without the safety net")
        assertEquals(2, guarded.flowPasses)
        assertContentEquals(renderBytes(spec, overflowGuard = false), spec.render(regular(), bold()), "same bytes as without the safety net")
        assertFalse(guarded.relaidWithGuard, "the accepted pass has no crossing: no second layout")
        assertEquals(3, guarded.pages.size)
        val rows = reading(lines(guarded.pages, labels("R", 54)))
        assertTrue(rows.all { it.page == 1 }, "the row is whole on page 2")
    }

    @Test
    fun splitRowUnderPageAwareFooterSettles() {
        val spec = pdfDocument(cfg) {
            footer { text("Page ${page.number} / ${page.total}", style) }
            row(gap = 12.dp, split = true) {
                cell(1f) { textLines("L", 200) }
                cell(1f) { textLines("R", 120) }
            }
            text("After", style)
        }
        val result = layoutOf(spec)
        val pages = result.pages
        val n = pages.size
        assertTrue(n >= 3)
        val candidates = (1..n + 2).flatMap { a -> (1..n + 2).map { b -> "Page $a / $b" } }
        val footers = lines(pages, candidates)
        assertEquals((1..n).map { "Page $it / $n" }, footers.map { it.label }, "every page's label has the true total")
        assertEquals(pages.indices.toList(), footers.map { it.page })
        assertEquals(labels("L", 200), reading(lines(pages, labels("L", 200))).map { it.label })
        assertEquals(labels("R", 120), reading(lines(pages, labels("R", 120))).map { it.label })
        val usable = bottom - 13_000 - 12_000 // the footer line and its gap
        for (line in lines(pages, labels("L", 200) + labels("R", 120))) {
            assertTrue(line.baseline - ascent + lineH <= usable, "${line.label} crosses the content bottom")
        }
        assertContentEquals(spec.render(regular(), bold()), spec.render(regular(), bold()), "two renders are identical")
    }

    @Test
    fun keyValueAndPhotoGridAreGuarded() {
        val longValue = List(1200) { "value$it" }.joinToString(" ")
        val spec = pdfDocument(cfg) {
            text("Before", style)
            photoGrid(listOf(jpeg(), jpeg(), jpeg()), perRow = 2, cellHeight = 1000.dp)
            keyValue("Label", longValue)
            text("After", style)
        }
        val pages = layout(spec, book)
        assertNothingBelow(pages)

        val images = pages.flatMap { it.ops.filterIsInstance<ImageOp>() }
        assertEquals(3, images.size, "every photo is drawn")
        assertTrue(images.all { it.hMpt == bottom - frameTop }, "each photo is clamped to a full page")

        // The value wraps to more lines than a page holds; every one is drawn, none past the bottom.
        val valueRow = spec.nodes.filterIsInstance<RowNode>().last()
        val valueW = rowCellWidths(valueRow, contentW, book)[1]
        val wrapped = LineBreaker.wrap(longValue, FontWeight.Bold, 9_000, valueW, book)
        assertTrue(wrapped.size * 11_000 > bottom - frameTop, "the value is taller than a page")
        val valueOps = pages.map { page -> page.ops.filterIsInstance<TextOp>().count { it.weight == FontWeight.Bold } }
        assertEquals(wrapped.size, valueOps.sum(), "every value line is drawn")
        assertTrue(valueOps.count { it > 0 } >= 2, "the value continues on the next page")
        assertEquals(1, lines(pages, listOf("After")).size)
    }

    @Test
    fun imagesOfTheDiscardedLayoutAreDropped() {
        val emojiFont = SyntheticEmojiFont.build()
        val emoji = String(Character.toChars(SyntheticEmojiFont.EMOJI_CP))
        val marker = png(12, 8, 0xCC2222)
        var markedTotal = -1
        val spec = pdfDocument(cfg) {
            footer {
                // 13 pt tall whether or not the marker is drawn, so it never changes the page count.
                row {
                    cell(1f) { text("Page ${page.number} / ${page.total}", style) }
                    cell(0f) { if (page.total == markedTotal) image(marker, width = 12.dp, height = 8.dp) }
                }
            }
            text("Before", style)
            row(gap = 12.dp) {
                cell(1f) { image(jpeg(), height = 100.dp); textLines("L", 120) }
                cell(1f) { text("Mood $emoji", style) }
            }
        }
        // The marker is drawn only in a document as long as the layout without the safety net.
        markedTotal = layoutOf(spec, overflowGuard = false).pages.size
        val off = renderBytes(spec, overflowGuard = false, emoji = emojiFont)
        val offImages = imageXObjects(off)
        assertEquals(setOf(64 to 48, 128 to 128, 12 to 8), offImages.toSet(), "without the safety net: photo, emoji and marker")

        val result = layoutOf(spec)
        assertTrue(result.relaidWithGuard)
        assertNotEquals(markedTotal, result.pages.size, "the accepted layout has another page count")
        val pdf = spec.render(regular(), bold(), emojiFont)
        val images = imageXObjects(pdf)
        assertEquals(2, images.size)
        assertEquals(setOf(64 to 48, 128 to 128), images.toSet(), "photo and emoji kept, the discarded layout's marker dropped")
        assertContentEquals(renderBytes(spec, overflowGuard = true, emoji = emojiFont), pdf)
        assertContentEquals(pdf, spec.render(regular(), bold(), emojiFont), "two renders are identical")
        assertContentEquals(pdf, streamed(spec, emojiFont), "render == renderTo")
    }

    @Test
    fun guardedLayoutWithoutRoomKeepsTheFirstLayout() {
        val spec = pdfDocument(cfg) {
            footer {
                text("Page ${page.number} / ${page.total}", style)
                // Page 3 exists only once the tall row is split; there the footer leaves no room.
                if (page.number == 3) spacer(800.dp)
            }
            text("Before", style)
            // The box splits only once the row does, so only the failed layout places its continuation
            // header; its glyphs (Q, x) appear nowhere else and would show in the font subset if kept.
            box(continuationHeader = { text("Qx", style) }) { tallRow() }
        }
        val off = layoutOf(spec, overflowGuard = false)
        assertEquals(1, off.pages.size, "without the safety net: 1 page, the row drawn past the bottom")
        assertTrue(off.rowCrossings.last())

        val result = layoutOf(spec) // does not throw
        assertTrue(result.guardedLayoutFailed, "the layout with the safety net reaches page 3 and fails its room check")
        assertFalse(result.relaidWithGuard)
        assertEquals(off.flowPasses, result.flowPasses)
        assertEquals(1, result.pages.size)
        val pdf = spec.render(regular(), bold())
        assertContentEquals(renderBytes(spec, overflowGuard = false), pdf, "exactly the first layout's bytes")
        assertContentEquals(pdf, streamed(spec), "render == renderTo")
    }

    @Test
    fun guardedLayoutThatDoesNotSettleKeepsTheFirstLayout() {
        // The last page's footer adds 123 pt (content ends at 658 pt there, 781 pt elsewhere). Drawn
        // past the bottom, the row settles in 2 passes; split, it gives 4 pages in pass 1 and 3 in
        // pass 2, so under a 2-pass cap the second layout runs out of passes.
        val spec = pdfDocument(cfg) {
            footer {
                text("Footer ${page.number}/${page.total}", style)
                if (page.isLast) { spacer(110.dp); text("Closing block", style) }
            }
            text("Before", style)
            row(gap = 12.dp) {
                cell(1f) { textLines("L", 100) }
                cell(1f) { textLines("R", 20) }
            }
        }
        val off = layoutOf(spec, overflowGuard = false, maxSettlePasses = 2)
        assertEquals(2, off.flowPasses)
        assertTrue(off.rowCrossings.last())

        val capped = layoutOf(spec, maxSettlePasses = 2) // does not throw
        assertTrue(capped.guardedLayoutFailed, "the second layout does not settle within 2 passes")
        assertFalse(capped.relaidWithGuard)
        assertContentEquals(renderBytes(spec, overflowGuard = false, maxSettlePasses = 2), renderBytes(spec, overflowGuard = true, maxSettlePasses = 2))

        // With the usual cap the second layout settles (3 passes) and splits the row.
        val full = layoutOf(spec)
        assertTrue(full.relaidWithGuard)
        assertFalse(full.guardedLayoutFailed)
        assertEquals(3, full.flowPasses)
        assertEquals(3, full.pages.size)
    }

    @Test
    fun bandErrorsInTheGuardedLayoutStillPropagate() {
        val spec = pdfDocument(cfg) {
            footer {
                text("Page ${page.number} / ${page.total}", style)
                check(page.number < 3) { "band code failed on page ${page.number}" }
            }
            text("Before", style)
            tallRow()
        }
        assertEquals(2, layoutOf(spec, overflowGuard = false).pages.size, "the first layout never builds page 3")
        val e = assertFailsWith<IllegalStateException> { spec.render(regular(), bold()) }
        assertEquals("band code failed on page 3", e.message, "the band's own error, not swallowed")
    }

    @Test
    fun discardedLayoutLeavesNoGlyphsInTheFont() {
        fun doc(withHeader: Boolean) = pdfDocument(cfg) {
            footer {
                text("Page ${page.number} / ${page.total}", style)
                // Page 1 is short only in a 2- or 3-page document: there the box below splits.
                if (page.number == 1 && page.total in 2..3) spacer(500.dp)
            }
            if (withHeader) box(continuationHeader = { text("Qx", style) }) { textLines("B", 40) } else box { textLines("B", 40) }
            row(gap = 12.dp) {
                cell(1f) { textLines("L", 120) }
                cell(1f) { textLines("R", 40) }
            }
        }
        val spec = doc(withHeader = true)
        // Without the safety net: 3 pages; the box splits on the short page 1, its header ("Qx", glyphs
        // used nowhere else) drawn on page 2.
        val off = layoutOf(spec, overflowGuard = false)
        assertEquals(3, off.pages.size)
        assertEquals(listOf(1), lines(off.pages, listOf("Qx")).map { it.page })

        // With it: 4 pages, page 1 is never short, the box stays whole and its header is never drawn.
        val fontBook = FontBook(regular(), bold())
        val result = layoutDocument(spec, fontBook, RenderImageTable(spec.images))
        assertTrue(result.relaidWithGuard)
        assertEquals(4, result.pages.size)
        assertTrue(lines(result.pages, listOf("Qx")).isEmpty())
        for (ch in "Qx") {
            assertFalse(fontBook.regular.gidForCodePoint(ch.code) in fontBook.usedGids(FontWeight.Normal), "'$ch' left in the font subset")
            assertFalse(ch.code in fontBook.gidToCp(FontWeight.Normal).values, "'$ch' left in ToUnicode")
        }
        // The same document without that header lays out identically, so the bytes match.
        assertContentEquals(doc(withHeader = false).render(regular(), bold()), spec.render(regular(), bold()))
    }

    @Test
    fun rowTallerThanPageInsideALaneSplitsFromTheLane() {
        val spec = pdfDocument(cfg) {
            text("Before", style)
            row(gap = 16.dp, split = true) {
                cell(1f) {
                    textLines("I", 3)
                    row(gap = 8.dp) { // not split, taller than a page: split from the lane
                        cell(1f) { textLines("L", 120) }
                        cell(1f) { textLines("R", 40) }
                    }
                }
                cell(1f) { textLines("S", 30) }
            }
            text("After", style)
        }
        val result = layoutOf(spec)
        assertTrue(result.relaidWithGuard)
        val pages = result.pages
        assertNothingBelow(pages)

        val outer = spec.nodes[1] as RowNode
        val outerW = rowCellWidths(outer, contentW, book)
        val inner = (outer.children[0].node as ColumnNode).children.filterIsInstance<RowNode>().single()
        val innerW = rowCellWidths(inner, outerW[0], book)
        val i = reading(lines(pages, labels("I", 3)))
        val s = reading(lines(pages, labels("S", 30)))
        val l = reading(lines(pages, labels("L", 120)))
        val r = reading(lines(pages, labels("R", 40)))
        assertEquals(labels("L", 120), l.map { it.label })
        assertEquals(labels("R", 40), r.map { it.label })
        assertEquals(labels("S", 30), s.map { it.label })

        // The outer lanes start side by side at the outer row's top on page 1.
        val rowTop = frameTop + lineH
        assertEquals(0, i.first().page)
        assertEquals(rowTop + ascent, i.first().baseline)
        assertEquals(0, s.first().page)
        assertEquals(rowTop + ascent, s.first().baseline)
        assertTrue(s.all { it.x == margin + outerW[0] + 16_000 })
        // The inner row moves with its lane to page 2 (today's move), then splits there: both inner
        // lanes start at the lane's entry y, at the inner cells' x inside the outer lane.
        assertEquals(1, l.first().page)
        assertEquals(frameTop + ascent, l.first().baseline)
        assertEquals(1, r.first().page)
        assertEquals(l.first().baseline, r.first().baseline)
        assertTrue(l.all { it.x == margin })
        assertTrue(r.all { it.x == margin + innerW[0] + 8_000 })
        for (p in 2..l.last().page) assertEquals(frameTop + ascent, l.first { it.page == p }.baseline, "page ${p + 1}: entry y")
        assertTrue(l.map { it.page }.distinct().size >= 3)
        val after = lines(pages, listOf("After")).single()
        assertEquals(l.last().page, after.page, "the flow continues below the deepest inner lane")
        assertEquals(l.last().baseline + lineH, after.baseline)
    }

    @Test
    fun rowTallerThanPageInsideAContinuationHeaderBox() {
        val ink = Color(0xFF334455)
        val spec = pdfDocument(cfg) {
            text("Before", style)
            box(padding = 10.dp, border = 1.dp, borderColor = ink, continuationHeader = { text("Continued", style) }) {
                textLines("I", 10)
                tallRow()
            }
            text("After", style)
        }
        val result = layoutOf(spec)
        assertTrue(result.relaidWithGuard)
        assertFalse(result.guardedLayoutFailed)
        val pages = result.pages
        assertNothingBelow(pages, rectSlack = 10_000)

        val l = reading(lines(pages, labels("L", 120)))
        val r = reading(lines(pages, labels("R", 40)))
        assertEquals(labels("L", 120), l.map { it.label })
        assertEquals(labels("R", 40), r.map { it.label })
        // Today's move (in the header box's precise context) takes the row to page 2; there and on every
        // later page the lanes start below the box's top padding and its header.
        val entry = frameTop + 10_000 + lineH
        assertEquals(1, l.first().page)
        assertEquals(entry + ascent, l.first().baseline)
        assertEquals(1, r.first().page)
        assertEquals(entry + ascent, r.first().baseline)
        for (p in 1..l.last().page) assertEquals(entry + ascent, l.first { it.page == p }.baseline, "page ${p + 1}: entry y")
        val headers = lines(pages, listOf("Continued"))
        assertEquals((1 until pages.size).toList(), headers.map { it.page }, "a header on each continuation page")
        assertTrue(headers.all { it.baseline == frameTop + 10_000 + ascent })
        for ((p, page) in pages.withIndex()) {
            val border = page.ops.filterIsInstance<RectOp>().filter { it.stroke === ink }
            assertEquals(1, border.size, "page ${p + 1}: one fragment")
            if (p < pages.lastIndex) assertEquals(bottom, border.single().yMpt + border.single().hMpt)
        }
        val after = lines(pages, listOf("After")).single()
        assertEquals(pages.lastIndex, after.page)
        assertEquals(l.last().baseline + lineH + 10_000, after.baseline, "below the deepest lane and the closing padding")
    }

    @Test
    fun rowTallerThanPageIsTopAlignedWhateverItsAlignment() {
        val spec = pdfDocument(cfg) {
            text("Before", style)
            row(gap = 12.dp, verticalAlignment = VerticalAlignment.Center) {
                cell(1f) { textLines("L", 120) }
                cell(1f) { text("Short", style) }
            }
        }
        val result = layoutOf(spec)
        assertTrue(result.relaidWithGuard)
        assertNothingBelow(result.pages)
        val widths = rowCellWidths(spec.nodes[1] as RowNode, contentW, book)
        val l = reading(lines(result.pages, labels("L", 120)))
        assertEquals(labels("L", 120), l.map { it.label })
        val short = lines(result.pages, listOf("Short")).single()
        assertEquals(1, l.first().page)
        assertEquals(frameTop + ascent, l.first().baseline)
        assertEquals(1, short.page)
        assertEquals(frameTop + ascent, short.baseline, "top-aligned, not centred")
        assertEquals(margin + widths[0] + 12_000, short.x)
        // Without the safety net the same row is centred on its 1560 pt height, below the page.
        val centred = lines(layoutOf(spec, overflowGuard = false).pages, listOf("Short")).single()
        assertTrue(centred.baseline > bottom, "the 0.11.0 placement centres it past the bottom")
    }
}
