package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.preview.PreviewText
import io.github.rikoappdev.composepdf.preview.previewPages
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.render.VectorOp
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Page-aware header/footer bands: page-read detection, static-band behaviour, the band gap, per-page
 * frames, the settle loop and image handling. Synthetic data only.
 */
class PageAwareBandsTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    @Test
    fun bandThatNeverReadsPageRunsOnce() {
        var counter = 0
        val spec = pdfDocument {
            footer { counter++; text("x") }
            text("Body")
        }
        spec.render(regular(), bold())
        spec.render(regular(), bold())
        assertEquals(1, counter)
    }

    @Test
    fun readingPageFromANestedCellMarksTheBandPageAware() {
        val spec = pdfDocument {
            footer { row { cell(1f) { text("P${page.number}") } } }
            text("Body")
        }
        assertTrue(spec.footer is PageAwareBand)
    }

    @Test
    fun staticBandIsStaticBand() {
        val spec = pdfDocument {
            header { text("H") }
            text("Body")
        }
        assertTrue(spec.header is StaticBand)
    }

    @Test
    fun defaultGapIsByteIdenticalToExplicit12() {
        val a = pdfDocument { footer { text("F") }; text("Body") }.render(regular(), bold())
        val b = pdfDocument { footer(gap = 12.dp) { text("F") }; text("Body") }.render(regular(), bold())
        assertContentEquals(a, b)
    }

    private fun bodyY(pdf: ByteArray): Float {
        var y = -1f
        Loader.loadPDF(pdf).use { d ->
            val s = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (text.contains("Body") && y < 0f) y = textPositions.first().yDirAdj
                }
            }
            s.startPage = 1
            s.endPage = 1
            s.getText(d)
        }
        return y
    }

    @Test
    fun largerHeaderGapPushesContentDown() {
        val base = pdfDocument { header { text("H") }; text("Body") }.render(regular(), bold())
        val wide = pdfDocument { header(gap = 40.dp) { text("H") }; text("Body") }.render(regular(), bold())
        val diff = bodyY(wide) - bodyY(base)
        assertTrue(diff >= 28f, "body should move down by >= 28pt, was $diff")
    }

    @Test
    fun largerFooterGapPullsContentUpFromFooter() {
        // A larger footer gap shrinks the usable height: a long body spills onto more pages.
        val many = pdfDocument { footer(gap = 600.dp) { text("F") }; repeat(60) { text("Line $it") } }
            .render(regular(), bold())
        val few = pdfDocument { footer { text("F") }; repeat(60) { text("Line $it") } }.render(regular(), bold())
        fun pages(b: ByteArray) = Loader.loadPDF(b).use { it.numberOfPages }
        assertTrue(pages(many) > pages(few))
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures and PDF probes
    // ---------------------------------------------------------------------------------------------

    private val label = TextStyle(fontSize = 8.sp)

    /** Left edge of the 30-pt logo cell (the row's last cell) on A4 with the default 36-pt margin. */
    private val logoLeft = 595f - 36f - 30f

    private fun png(w: Int, h: Int, rgb: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rgb)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private val redPng = png(30, 12, 0xCC2222)

    private fun svgMark(): ByteArray = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="16" viewBox="0 0 24 16">
          <rect x="1" y="1" width="22" height="14" fill="#3355BB"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    /** A source that records how often it was pulled. */
    private class CountingSource(private val bytes: ByteArray) : PdfImageSource {
        var reads = 0
            private set

        override fun readBytes(): ByteArray {
            reads++
            return bytes
        }
    }

    /** Starts a new page: a spacer taller than any page pushes what follows onto the next one. */
    private fun ContainerScope.pageBreak() = spacer(2000.dp)

    /** A footer row: a two-line text block, the page label in a content-sized cell, a logo. */
    private fun BandScope.numberedFooter(logo: ByteArray) {
        row(verticalAlignment = VerticalAlignment.Center) {
            cell(1f) { text("Contoso Field Reports", label); text("example.com", label) }
            cell(0f) { text("Report ID 12345 · Page ${page.number} / ${page.total}", label) }
            cell(0f) { image(logo, width = 30.dp, height = 12.dp) }
        }
    }

    /** Exactly [pages] pages, one body line each, under [numberedFooter]. */
    private fun numberedDoc(pages: Int) = pdfDocument(PageConfig(pageNumbers = false)) {
        footer { numberedFooter(redPng) }
        repeat(pages) {
            if (it > 0) pageBreak()
            text("Body line $it")
        }
    }

    private fun stream(spec: PdfDocumentSpec, emoji: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold(), emoji)
        return out.toByteArray()
    }

    private fun pageCount(pdf: ByteArray) = Loader.loadPDF(pdf).use { it.numberOfPages }

    /** A glyph run as drawn: consecutive glyphs on one baseline with no gap between them. */
    private class Run(val text: String, val x: Float, val right: Float, val baseline: Float, val top: Float)

    /** Every page's glyph runs, in content-stream order. */
    private fun runsPerPage(pdf: ByteArray): List<List<Run>> = Loader.loadPDF(pdf).use { d ->
        (1..d.numberOfPages).map { pageNo ->
            val glyphs = ArrayList<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs.add(text)
                }
            }
            stripper.startPage = pageNo
            stripper.endPage = pageNo
            stripper.getText(d)
            toRuns(glyphs)
        }
    }

    private fun toRuns(glyphs: List<TextPosition>): List<Run> {
        val runs = ArrayList<Run>()
        val text = StringBuilder()
        var x = 0f
        var right = 0f
        var baseline = 0f
        var top = 0f
        fun flush() {
            if (text.isNotEmpty()) runs.add(Run(text.toString(), x, right, baseline, top))
            text.setLength(0)
        }
        for (g in glyphs) {
            val continues = text.isNotEmpty() && abs(g.yDirAdj - baseline) < 0.5f &&
                g.xDirAdj >= right - 0.5f && g.xDirAdj <= right + 5f
            if (!continues) {
                flush()
                x = g.xDirAdj
                baseline = g.yDirAdj
                top = g.yDirAdj - g.heightDir
            }
            text.append(g.unicode)
            right = g.xDirAdj + g.widthDirAdj
            top = minOf(top, g.yDirAdj - g.heightDir)
        }
        flush()
        return runs
    }

    private fun List<Run>.startingWith(prefix: String) = filter { it.text.startsWith(prefix) }

    /** Subtype (`Image` / `Form`) of every distinct XObject the pages can draw. */
    private fun xObjectSubtypes(pdf: ByteArray): List<String> = Loader.loadPDF(pdf).use { d ->
        val byKey = LinkedHashMap<Any, String>()
        for (page in d.pages) {
            val res = page.resources
            val dict = res.cosObject.getCOSDictionary(COSName.XOBJECT) ?: continue
            for (name in res.xObjectNames) {
                val key: Any = (dict.getItem(name) as? COSObject)?.key ?: name
                byKey.getOrPut(key) { res.getXObject(name).cosObject.getNameAsString(COSName.SUBTYPE) }
            }
        }
        byKey.values.toList()
    }

    /** Pixel sizes of every image XObject the pages can draw. */
    private fun imageSizes(pdf: ByteArray): List<Pair<Int, Int>> = Loader.loadPDF(pdf).use { d ->
        val res = d.getPage(0).resources
        res.xObjectNames.mapNotNull { (res.getXObject(it) as? PDImageXObject)?.let { img -> img.width to img.height } }
    }

    /** Asserts that on every page all body text ([bodyPrefix]) ends above the first footer glyph. */
    private fun assertBodyClearsFooter(pdf: ByteArray, bodyPrefix: String, footerPrefixes: List<String>) {
        runsPerPage(pdf).forEachIndexed { i, runs ->
            val body = runs.startingWith(bodyPrefix)
            val footer = runs.filter { r -> footerPrefixes.any { r.text.startsWith(it) } }
            assertTrue(footer.isNotEmpty(), "page ${i + 1} has a footer")
            if (body.isEmpty()) return@forEachIndexed
            val bodyBottom = body.maxOf { it.baseline } + 3f // baseline + a body descent
            val footerTop = footer.minOf { it.top }
            assertTrue(bodyBottom < footerTop, "page ${i + 1}: body ends at $bodyBottom, footer starts at $footerTop")
        }
    }

    private fun layoutOf(spec: PdfDocumentSpec, book: FontBook = FontBook(regular(), bold())) =
        layoutDocument(spec, book, RenderImageTable(spec.images))

    // ---------------------------------------------------------------------------------------------
    // Page numbers inside a page-aware footer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun footerShowsCorrectPageNumbersOnEveryPage() {
        val pdf = numberedDoc(5).render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertEquals(5, pages.size)
        pages.forEachIndexed { i, runs ->
            val labels = runs.startingWith("Report ID 12345")
            assertEquals(listOf("Report ID 12345 · Page ${i + 1} / 5"), labels.map { it.text }, "page ${i + 1}")
            assertEquals(1, runs.startingWith("Contoso Field Reports").size)
        }
    }

    @Test
    fun contentSizedLabelStaysFlushAgainstTheLogo() {
        val pages = runsPerPage(numberedDoc(12).render(regular(), bold()))
        for (pageNo in listOf(9, 10)) {
            val label = pages[pageNo - 1].startingWith("Report ID 12345").single()
            assertEquals("Report ID 12345 · Page $pageNo / 12", label.text)
            assertTrue(abs(label.right - logoLeft) <= 2f, "page $pageNo: label ends at ${label.right}, logo starts at $logoLeft")
        }
        val nine = pages[8].startingWith("Report ID").single()
        val ten = pages[9].startingWith("Report ID").single()
        assertTrue(ten.x < nine.x, "a wider label starts further left (${ten.x} vs ${nine.x})")
    }

    @Test
    fun threeDigitTotalsStayCorrectAndFlush() {
        val pages = runsPerPage(numberedDoc(120).render(regular(), bold()))
        assertEquals(120, pages.size)
        pages.forEachIndexed { i, runs ->
            val label = runs.startingWith("Report ID 12345").single()
            assertEquals("Report ID 12345 · Page ${i + 1} / 120", label.text)
            assertTrue(abs(label.right - logoLeft) <= 2f, "page ${i + 1}: label ends at ${label.right}")
        }
    }

    /** A reusable band helper written the recommended way: a ContainerScope extension taking the page. */
    private fun ContainerScope.pageLabel(page: PageInfo) = text("Page ${page.number} / ${page.total}", label)

    @Test
    fun pageHelperWithAPageInfoParameterStaysInsideItsCell() {
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            footer {
                row(verticalAlignment = VerticalAlignment.Center) {
                    cell(1f) { text("Contoso Field Reports", label) }
                    cell(0f) { pageLabel(page) }
                    cell(0f) { image(redPng, width = 30.dp, height = 12.dp) }
                }
            }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }.render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertEquals(3, pages.size)
        pages.forEachIndexed { i, runs ->
            val left = runs.startingWith("Contoso").single()
            val pageLabel = runs.startingWith("Page ").single()
            assertEquals("Page ${i + 1} / 3", pageLabel.text)
            assertTrue(abs(pageLabel.baseline - left.baseline) <= 0.5f, "page ${i + 1}: the label sits on the row's baseline (${pageLabel.baseline} vs ${left.baseline})")
            assertTrue(pageLabel.x > left.right, "page ${i + 1}: the label is in its own cell, right of the first one")
            assertTrue(abs(pageLabel.right - logoLeft) <= 2f, "page ${i + 1}: the label ends at the logo cell (${pageLabel.right} vs $logoLeft)")
        }
    }

    @Test
    fun bodylessDocumentHasOnePage() {
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            footer { text("Page ${page.number} / ${page.total}", label) }
        }.render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertEquals(1, pages.size)
        assertEquals(listOf("Page 1 / 1"), pages[0].map { it.text })
    }

    // ---------------------------------------------------------------------------------------------
    // Per-page band heights
    // ---------------------------------------------------------------------------------------------

    @Test
    fun pageAwareHeaderCanBeTallerOnTheFirstPage() {
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            header { if (page.isFirst) repeat(6) { text("Title") } else text("Running") }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }.render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertEquals(3, pages.size)
        assertEquals(6, pages[0].startingWith("Title").size)
        assertTrue(pages[0].startingWith("Running").isEmpty())
        for (p in pages.drop(1)) {
            assertEquals(1, p.startingWith("Running").size)
            assertTrue(p.startingWith("Title").isEmpty())
        }
        val firstBody = pages.map { it.startingWith("Body line").first() }
        assertTrue(firstBody[0].baseline - firstBody[1].baseline >= 40f, "page 1 content starts lower: ${firstBody.map { it.baseline }}")
        pages.forEachIndexed { i, runs ->
            val headerBottom = runs.filter { it.text.startsWith("Title") || it.text.startsWith("Running") }.maxOf { it.baseline }
            assertTrue(runs.startingWith("Body line").all { it.top > headerBottom }, "page ${i + 1}: body below the header")
        }
    }

    @Test
    fun repeatHeaderFalseLimitsPageAwareHeaderToPageOne() {
        val seen = ArrayList<Int>()
        val spec = pdfDocument(PageConfig(pageNumbers = false, repeatHeader = false)) {
            header { seen += page.number; text("Header ${page.number} / ${page.total}") }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }
        seen.clear()
        val pages = runsPerPage(spec.render(regular(), bold()))
        assertEquals(3, pages.size)
        assertEquals(listOf("Header 1 / 3"), pages[0].startingWith("Header").map { it.text })
        assertTrue(pages.drop(1).all { it.startingWith("Header").isEmpty() }, "no header after page 1")
        assertTrue(seen.isNotEmpty() && seen.all { it == 1 }, "the header block is only built for page 1: $seen")
        val first = pages.map { it.startingWith("Body line").first().baseline }
        assertTrue(first[1] < first[0], "page 2 reclaims the header space: $first")
    }

    @Test
    fun lastPageOnlyRowLeavesNoGapElsewhere() {
        fun doc(withRow: Boolean) = pdfDocument(PageConfig(pageNumbers = false)) {
            footer {
                if (withRow && page.isLast) {
                    text("Approved by Fabrikam QA")
                    spacer(18.dp)
                }
                text("Contoso Field Reports · Page ${page.number} / ${page.total}", label)
            }
            repeat(120) { text("Line $it") }
        }
        val with = doc(withRow = true).render(regular(), bold())
        val without = doc(withRow = false).render(regular(), bold())
        val withPages = runsPerPage(with)
        val withoutPages = runsPerPage(without)
        assertEquals(withoutPages.size, withPages.size)

        fun gapOnPage(pages: List<List<Run>>, index: Int): Float {
            val runs = pages[index]
            return runs.startingWith("Contoso").single().baseline - runs.startingWith("Line ").maxOf { it.baseline }
        }
        val n = withPages.size
        assertTrue(n >= 3, "page 1 and the second-to-last page are distinct pages")
        for (i in 0 until n - 1) {
            val gap = gapOnPage(withPages, i)
            val reference = gapOnPage(withoutPages, i)
            assertTrue(abs(gap - reference) <= 1f, "page ${i + 1} gap $gap must equal the row-less footer's $reference")
        }

        assertEquals(1, withPages[n - 1].startingWith("Approved by Fabrikam QA").size, "the last page shows the row")
        assertTrue(withPages.dropLast(1).all { it.startingWith("Approved").isEmpty() }, "only the last page shows the row")
        withPages.forEachIndexed { i, runs ->
            assertEquals("Contoso Field Reports · Page ${i + 1} / $n", runs.startingWith("Contoso").single().text)
        }
        assertBodyClearsFooter(with, "Line ", listOf("Approved", "Contoso"))
    }

    /**
     * 30 body lines, then one keep-together paragraph of [paragraphLines] lines, under a footer that
     * grows by a row on the last page — the shape that can make the page count cycle: with the tall
     * footer on page 1 the paragraph moves whole to page 2; with the short one it fits on page 1.
     */
    private fun signatureFooterDoc(paragraphLines: Int, seen: MutableList<PageInfo>? = null) =
        pdfDocument(PageConfig(pageNumbers = false)) {
            footer {
                seen?.add(page)
                if (page.isLast) {
                    text("Approved by Fabrikam QA")
                    spacer(18.dp)
                }
                text("Contoso Field Reports · Page ${page.number} / ${page.total}", label)
            }
            repeat(30) { text("Body line $it") }
            text((0 until paragraphLines).joinToString("\n") { "Body para $it" })
        }

    /** The smallest keep-together paragraph (in lines) whose pagination cycles and settles through the fallback. */
    private fun cyclingParagraphLines(): Int? {
        val book = FontBook(regular(), bold())
        return (1..120).firstOrNull { layoutOf(signatureFooterDoc(it), book).usedFallback }
    }

    @Test
    fun cyclingPaginationTerminatesWithoutOverlap() {
        val paragraphLines = cyclingParagraphLines()
        assertNotNull(paragraphLines, "no paragraph of 1..120 lines made the page count cycle")
        val spec = signatureFooterDoc(paragraphLines)
        val result = layoutOf(spec)
        assertTrue(result.usedFallback, "the max-reservation fallback settled this document")

        val pdf = spec.render(regular(), bold())
        val pages = runsPerPage(pdf)
        val n = pages.size
        assertEquals(result.pages.size, n)
        pages.forEachIndexed { i, runs ->
            assertEquals("Contoso Field Reports · Page ${i + 1} / $n", runs.startingWith("Contoso").single().text)
        }
        assertEquals(1, pages[n - 1].startingWith("Approved").size, "the last page carries the row")
        assertTrue(pages.dropLast(1).all { it.startingWith("Approved").isEmpty() })
        assertEquals(30 + paragraphLines, pages.sumOf { it.startingWith("Body ").size }, "every body line is drawn once")
        assertEquals(1, pages.count { it.startingWith("Body para").isNotEmpty() }, "the paragraph stays together")
        assertBodyClearsFooter(pdf, "Body ", listOf("Approved", "Contoso"))
    }

    /**
     * [lines] single-line paragraphs under a footer that grows by a 60-pt block on the last page. No
     * keep-together is needed for a cycle: when the last page's content fits without the block but
     * not with it, T = n pushes content onto page n + 1 and T = n + 1 fits on n pages again.
     */
    private fun lastPageBlockDoc(lines: Int, withBlock: Boolean = true) = pdfDocument(PageConfig(pageNumbers = false)) {
        footer {
            if (withBlock && page.isLast) {
                text("Approved by Fabrikam QA")
                spacer(60.dp)
            }
            text("Contoso Field Reports · Page ${page.number} / ${page.total}", label)
        }
        repeat(lines) { text("Line $it") }
    }

    /** Per page, the baseline of the lowest body line. */
    private fun bodyBottoms(spec: PdfDocumentSpec): List<Float> = spec.previewPages(regular(), bold()).map { p ->
        p.ops.filterIsInstance<PreviewText>().filter { it.text.startsWith("Line ") }.maxOf { it.baselineYPt }
    }

    @Test
    fun cycleFallbackLeavesAGapOnlyBeforeTheLastPage() {
        val book = FontBook(regular(), bold())
        val cycling = (300..420).filter { layoutOf(lastPageBlockDoc(it), book).usedFallback }
        assertTrue(cycling.isNotEmpty(), "no body of 300..420 lines made the page count cycle")
        for (count in cycling) {
            val with = bodyBottoms(lastPageBlockDoc(count))
            val without = bodyBottoms(lastPageBlockDoc(count, withBlock = false))
            val n = with.size
            assertTrue(n >= 3 && without.size >= n - 1, "$count lines: $n pages with the block, ${without.size} without")
            // Only the second-to-last page may keep the last page's reservation; every other non-last
            // page fills exactly as far as in the block-less document.
            for (i in 0 until n - 2) {
                assertEquals(without[i], with[i], "$count lines, page ${i + 1} of $n: body bottom must match the block-less document")
            }
        }

        val lines = cycling.first()
        val spec = lastPageBlockDoc(lines)
        val n = layoutOf(spec, book).pages.size
        val pdf = spec.render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertEquals(n, pages.size)
        pages.forEachIndexed { i, runs ->
            assertEquals("Contoso Field Reports · Page ${i + 1} / $n", runs.startingWith("Contoso").single().text)
        }
        assertEquals(1, pages[n - 1].startingWith("Approved").size, "the last page carries the block")
        assertEquals(lines, pages.sumOf { it.startingWith("Line ").size }, "every body line is drawn once")
        assertBodyClearsFooter(pdf, "Line ", listOf("Approved", "Contoso"))
    }

    @Test
    fun settleLoopGivesUpClearlyAtItsPassCap() {
        val spec = numberedDoc(5)
        val e = assertFailsWith<IllegalStateException> {
            layoutDocument(spec, FontBook(regular(), bold()), RenderImageTable(spec.images), maxSettlePasses = 1)
        }
        assertEquals(
            "Pagination did not settle after 1 passes: the header/footer heights depend on the page count " +
                "and keep changing it",
            e.message,
        )
    }

    @Test
    fun lambdaNeverSeesNumberAboveTotal() {
        val cycling = assertNotNull(cyclingParagraphLines())
        for (count in listOf(cycling, 100, 1)) {
            val seen = ArrayList<PageInfo>()
            signatureFooterDoc(count, seen).render(regular(), bold())
            assertTrue(seen.size > 1, "the block ran during render")
            assertTrue(seen.all { it.number in 1..it.total }, "every PageInfo has 1 <= number <= total: $seen")
        }
    }

    @Test
    fun bandTallerThanThePageFailsClearly() {
        val spec = pdfDocument {
            footer { repeat(80) { text("Row ${page.number}") } }
            text("Body")
        }
        val e = assertFailsWith<IllegalArgumentException> { spec.render(regular(), bold()) }
        assertEquals("Header and footer leave no room for content on page 1", e.message)
    }

    @Test
    fun bandTooTallForEveryPageOfALongBodyFailsClearly() {
        val spec = pdfDocument {
            footer { repeat(80) { text("Row ${page.number}") } }
            repeat(100) { text("Body line $it") }
        }
        val e = assertFailsWith<IllegalArgumentException> { spec.render(regular(), bold()) }
        assertEquals("Header and footer leave no room for content on page 1", e.message)
    }

    @Test
    fun lastPageBlockTallerThanThePageFailsClearly() {
        val spec = pdfDocument(PageConfig(pageNumbers = false)) {
            footer { if (page.isLast) repeat(80) { text("Signed for Contoso") } }
            repeat(100) { text("Body line $it") }
        }
        val e = assertFailsWith<IllegalArgumentException> { spec.render(regular(), bold()) }
        assertTrue(e.message!!.startsWith("Header and footer leave no room for content on page "), "${e.message}")
    }

    @Test
    fun tallFirstHeaderAndTallLastFooterNeedNotShareAPage() {
        // Together they overflow the page; apart, each leaves room. Page 1 of the first pass is both
        // first and last, which must not fail a document that needs two pages anyway.
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            header { if (page.isFirst) { text("Contoso Field Reports"); spacer(380.dp) } }
            footer { if (page.isLast) { text("Approved by Fabrikam QA"); spacer(380.dp) } }
            repeat(40) { text("Body line $it") }
        }.render(regular(), bold())
        val pages = runsPerPage(pdf)
        assertTrue(pages.size >= 2, "two pages at least: ${pages.size}")
        assertEquals(40, pages.sumOf { it.startingWith("Body line").size }, "every body line is drawn once")
        assertEquals(1, pages[0].startingWith("Contoso").size, "the title block is on page 1")
        assertTrue(pages.drop(1).all { it.startingWith("Contoso").isEmpty() })
        assertEquals(1, pages.last().startingWith("Approved").size, "the last page carries the block")
        assertTrue(pages.dropLast(1).all { it.startingWith("Approved").isEmpty() })
        val lastBody = pages.last().startingWith("Body line")
        if (lastBody.isNotEmpty()) {
            val approvedTop = pages.last().startingWith("Approved").single().top
            assertTrue(lastBody.maxOf { it.baseline } + 3f < approvedTop, "the last page's body clears the block")
        }
    }

    @Test
    fun negativeBandGapIsRejected() {
        assertFailsWith<IllegalArgumentException> { pdfDocument { footer(gap = (-1).dp) { text("F") } } }
        assertFailsWith<IllegalArgumentException> { pdfDocument { header(gap = (-4).dp) { text("H") } } }
    }

    // ---------------------------------------------------------------------------------------------
    // Atomic nodes moving onto a page with less room
    // ---------------------------------------------------------------------------------------------

    /** An intro line, then [media], under a footer that grows by signature rows on the last page. */
    private fun growingFooterDoc(media: ContainerScope.() -> Unit) = pdfDocument(PageConfig(pageNumbers = false)) {
        footer {
            text("Contoso Field Reports · Page ${page.number} / ${page.total}", label)
            if (page.isLast) {
                spacer(12.dp)
                text("Approved by Fabrikam QA")
                text("Signed for Contoso")
            }
        }
        text("Intro")
        media()
    }

    @Test
    fun mediaMovedOntoALastPageWhoseFooterGrowsStaysClearOfIt() {
        val book = FontBook(regular(), bold())
        val photo = png(40, 30, 0x3366CC)
        // Page 1 (short footer) has 748 pt of room, the last page (tall footer) 706 pt.
        val cases: List<Pair<String, ContainerScope.() -> Unit>> = listOf(
            // Taller than any page: clamped — to the room of the page it lands on, not of page 1.
            "photo taller than a page" to { image(photo, height = 2000.dp, fit = PhotoFit.Contain) },
            // Fits page 1's room but not after the intro, and not the last page's room: moved, then clamped.
            "photo between the two pages' room" to { image(photo, height = 740.dp, fit = PhotoFit.Contain) },
            "vector between the two pages' room" to { vector(svgMark(), height = 740.dp) },
        )
        for ((name, media) in cases) {
            val pages = layout(growingFooterDoc(media), book)
            assertEquals(2, pages.size, name)
            val last = pages[1]
            val bottom = when (val drawn = last.ops.single { it is ImageOp || it is VectorOp }) {
                is ImageOp -> (drawn.yMpt + drawn.hMpt) / 1000
                is VectorOp -> (drawn.yMpt + drawn.hMpt) / 1000
                else -> error("unreachable")
            }
            val footerTop = last.ops.filterIsInstance<TextOp>().minOf { it.baselineYMpt - book.ascentMpt(it.weight, it.fontSizeMpt) } / 1000
            assertEquals(12, footerTop - bottom, "$name: ends at $bottom, the last page's footer starts at $footerTop")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry: band gap, footer ↔ page-number line
    // ---------------------------------------------------------------------------------------------

    private val ruleColor = Color(0xFFCC2222)

    /** Bottom of the last 1-pt body rule on page 1 — rules stack until the content bottom. */
    private fun contentBottom(spec: PdfDocumentSpec, book: FontBook): Int =
        layout(spec, book).first().ops.filterIsInstance<RectOp>().filter { it.fill === ruleColor }.maxOf { it.yMpt + it.hMpt } / 1000

    private fun ContainerScope.ruledBody() = repeat(1200) { divider(ruleColor, thickness = 1.dp) }

    @Test
    fun footerToPageNumberLineStaysTwelvePoints() {
        val book = FontBook(regular(), bold())
        val style = PageConfig().pageNumberStyle
        val numberTop = 842 - 36 - (style.fontSize.value * style.lineHeightMultiple).toInt()
        val blue = Color(0xFF3355BB)
        val footers: List<BandScope.() -> Unit> = listOf(
            { divider(blue, thickness = 4.dp) },
            { if (page.number > 0) divider(blue, thickness = 4.dp) },
        )
        for (band in footers) {
            val page = layout(pdfDocument { footer(gap = 30.dp, build = band); text("Body") }, book).single()
            val rule = page.ops.filterIsInstance<RectOp>().single()
            val number = page.ops.filterIsInstance<TextOp>().maxBy { it.baselineYMpt }
            assertEquals(numberTop, (number.baselineYMpt - book.ascentMpt(style.fontWeight, style.fontSize.mpt)) / 1000)
            assertEquals(12, numberTop - (rule.yMpt + rule.hMpt) / 1000, "footer ↔ number line stays 12 pt whatever the gap")
        }
    }

    @Test
    fun withoutAFooterContentEndsTwelvePointsAboveTheNumberLine() {
        val book = FontBook(regular(), bold())
        val style = PageConfig().pageNumberStyle
        val numberTop = 842 - 36 - (style.fontSize.value * style.lineHeightMultiple).toInt()
        assertEquals(12, numberTop - contentBottom(pdfDocument { ruledBody() }, book))
    }

    @Test
    fun largerFooterGapMovesTheContentBottomUpByTheDifference() {
        val book = FontBook(regular(), bold())
        for (pageAware in listOf(false, true)) {
            fun doc(gap: Int) = pdfDocument {
                footer(gap = gap.dp) { if (!pageAware || page.number > 0) text("Contoso Field Reports", label) }
                ruledBody()
            }
            val base = contentBottom(doc(12), book)
            val wide = contentBottom(doc(40), book)
            assertEquals(28, base - wide, "page-aware=$pageAware: the content bottom moves up by exactly 40 - 12")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Passes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun staticDocumentTakesOneFlowPass() {
        val result = layoutOf(pdfDocument {
            header { text("Contoso Field Reports") }
            footer { text("example.com") }
            repeat(200) { text("Line $it") }
        })
        assertTrue(result.pages.size > 1)
        assertEquals(1, result.flowPasses)
        assertFalse(result.usedFallback)
    }

    @Test
    fun typicalPageAwareDocumentSettlesInTwoPasses() {
        val result = layoutOf(numberedDoc(5))
        assertEquals(5, result.pages.size)
        assertEquals(2, result.flowPasses)
        assertFalse(result.usedFallback)
    }

    // ---------------------------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------------------------

    @Test
    fun sameLogoIsEmbeddedOnce() {
        val hoistedPng = png(16, 8, 0x3366CC)
        val hoistedSvg = svgMark()
        val variants: List<Triple<String, String, BandScope.() -> Unit>> = listOf(
            Triple("hoisted PNG bytes", "Image") { image(hoistedPng, width = 16.dp, height = 8.dp); text("P${page.number}") },
            Triple("PNG bytes re-read per page", "Image") { image(png(16, 8, 0x3366CC), width = 16.dp, height = 8.dp); text("P${page.number}") },
            Triple("PNG source re-created per page", "Image") { image(PdfImageSource { png(16, 8, 0x3366CC) }, width = 16.dp, height = 8.dp); text("P${page.number}") },
            Triple("hoisted vector", "Form") { vector(hoistedSvg, width = 24.dp, height = 16.dp); text("P${page.number}") },
            Triple("vector re-read per page", "Form") { vector(svgMark(), width = 24.dp, height = 16.dp); text("P${page.number}") },
        )
        for ((name, subtype, band) in variants) {
            val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
                footer(build = band)
                repeat(10) {
                    if (it > 0) pageBreak()
                    text("Body line $it")
                }
            }.render(regular(), bold())
            assertEquals(10, pageCount(pdf), name)
            assertEquals(listOf(subtype), xObjectSubtypes(pdf), "$name: one XObject for ten pages")
        }
    }

    @Test
    fun sameVectorInAStaticHeaderAndAPageAwareFooterIsOneObject() {
        val badge = svgMark()
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            header { vector(badge, width = 24.dp, height = 16.dp) }
            footer {
                row {
                    cell(1f) { text("Page ${page.number} / ${page.total}", label) }
                    cell(0f) { vector(badge, width = 12.dp, height = 8.dp) }
                }
            }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }.render(regular(), bold())
        assertEquals(3, pageCount(pdf))
        assertEquals(listOf("Form"), xObjectSubtypes(pdf), "the footer reuses the header's badge: one Form XObject")
    }

    @Test
    fun unusedImageIsDropped() {
        val a = png(8, 8, 0xCC2222)
        val b = png(10, 6, 0x2222CC)
        val pdf = pdfDocument(PageConfig(pageNumbers = false)) {
            footer { if (page.total == 1) image(a, width = 8.dp, height = 8.dp) else image(b, width = 10.dp, height = 6.dp) }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }.render(regular(), bold())
        assertEquals(3, pageCount(pdf))
        assertEquals(listOf("Image"), xObjectSubtypes(pdf), "only the image the final pages draw")
        assertEquals(listOf(10 to 6), imageSizes(pdf), "and it is b, not the probe's a")
    }

    @Test
    fun hoistedSourceInAPageAwareFooterIsReadOnlyByTheProbe() {
        val source = CountingSource(png(16, 8, 0x3366CC))
        val spec = pdfDocument(PageConfig(pageNumbers = false)) {
            footer {
                image(source, width = 16.dp, height = 8.dp)
                text("Page ${page.number} / ${page.total}", label)
            }
            repeat(6) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }
        assertEquals(1, source.reads, "the build-time probe reads it once")
        val first = spec.render(regular(), bold())
        val second = spec.render(regular(), bold())
        assertEquals(1, source.reads, "renders recognise the probe's instance: no re-read, no re-decode")
        assertEquals(listOf("Image"), xObjectSubtypes(first))
        assertContentEquals(first, second)
    }

    // ---------------------------------------------------------------------------------------------
    // Reuse, transports, emoji, concurrency
    // ---------------------------------------------------------------------------------------------

    @Test
    fun pageAwareSpecIsReusable() {
        val spec = numberedDoc(4)
        val first = spec.render(regular(), bold())
        assertContentEquals(first, spec.render(regular(), bold()), "render twice → identical bytes")
        assertContentEquals(first, stream(spec), "render == renderTo")
        val preview = spec.previewPages(regular(), bold())
        assertEquals(pageCount(first), preview.size)
        preview.forEachIndexed { i, p ->
            val texts = p.ops.filterIsInstance<PreviewText>().map { it.text }
            assertTrue(texts.any { "Page ${i + 1} / 4" in it }, "preview page ${i + 1} shows its label: $texts")
        }
    }

    @Test
    fun emojiInPageAwareFooter() {
        val emojiFont = SyntheticEmojiFont.build()
        val emoji = String(Character.toChars(SyntheticEmojiFont.EMOJI_CP))
        val spec = pdfDocument(PageConfig(pageNumbers = false)) {
            footer { text("Page ${page.number} / ${page.total} $emoji", label) }
            repeat(3) {
                if (it > 0) pageBreak()
                text("Body line $it")
            }
        }
        val rendered = spec.render(regular(), bold(), emojiFont)
        assertContentEquals(rendered, stream(spec, emojiFont), "render == renderTo")
        assertEquals(3, pageCount(rendered), "PDFBox loads it")
        assertEquals(listOf("Image"), xObjectSubtypes(rendered), "one emoji image for three footers")
        runsPerPage(rendered).forEachIndexed { i, runs ->
            assertEquals(1, runs.startingWith("Page ${i + 1} / 3").size)
        }
    }

    @Test
    fun concurrentRendersOfOneSpecAreIdentical() {
        val spec = numberedDoc(6)
        val regular = regular()
        val bold = bold()
        val expected = spec.render(regular, bold)
        repeat(3) {
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val results = arrayOfNulls<ByteArray>(2)
            val threads = List(2) { k ->
                thread {
                    try {
                        start.await()
                        results[k] = spec.render(regular, bold)
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            failure.get()?.let { throw it }
            results.forEach { assertContentEquals(expected, it) }
        }
    }
}
