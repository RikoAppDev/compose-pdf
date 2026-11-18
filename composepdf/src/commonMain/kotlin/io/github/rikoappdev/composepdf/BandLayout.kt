package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.layout.ColumnNode
import io.github.rikoappdev.composepdf.layout.Node
import io.github.rikoappdev.composepdf.layout.Placeable
import io.github.rikoappdev.composepdf.layout.measure
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.render.Page

/** The default band ↔ content gap, and the fixed gap between the footer and the page-number line: 12 pt. */
private const val BAND_GAP_MPT = 12 * MPT_PER_PT

/** Safety cap on flow passes while a page-aware document's page count settles. */
private const val MAX_SETTLE_PASSES = 50

/** The content area of one page (top-left origin, millipoints): content flows from [topY] down to [usableBottom]. */
internal class PageFrame(val topY: Int, val usableBottom: Int) {
    /** This frame narrowed to also lie inside [other] (`null` = no limit): each edge keeps the larger reservation. */
    fun within(other: PageFrame?): PageFrame =
        if (other == null) this else PageFrame(maxOf(topY, other.topY), minOf(usableBottom, other.usableBottom))
}

/** Laid-out pages plus the image list their image / vector ops' indices point into. */
internal class LayoutResult(
    val pages: List<Page>,
    val images: List<EmbeddedImage>,
    /** How often the body was flowed: 1 when no band reads `page`, typically 2 when one does. */
    val flowPasses: Int = 1,
    /** True when the page count cycled and the max-reservation fallback settled it. */
    val usedFallback: Boolean = false,
)

/** [layoutDocument] for callers that only need the pages and whose [book] adds no images (geometry
 *  checks with stub metrics). Anything that draws images goes through [layoutDocument]. */
internal fun layout(spec: PdfDocumentSpec, book: TextMetrics): List<Page> =
    layoutDocument(spec, book, RenderImageTable(spec.images)).pages

/**
 * Lays [spec] out against [book]: flows the body between each page's bands, then draws the bands and
 * the page-number line. [table] is the render's image table — the one [book] appends emoji bitmaps to
 * and page-aware bands register their images in — and the returned images are that table compacted
 * to what the pages actually draw.
 *
 * When no band reads `page`, every page's frame is known up front and the body is flowed once,
 * exactly as before page-aware bands existed. Otherwise [settle] finds the page count.
 */
internal fun layoutDocument(
    spec: PdfDocumentSpec,
    book: TextMetrics,
    table: RenderImageTable,
    maxSettlePasses: Int = MAX_SETTLE_PASSES,
): LayoutResult {
    val bands = BandBuilder(spec, book, table)
    val laid = if (bands.pageAware) {
        settle(spec, book, bands, maxSettlePasses)
    } else {
        val built = ArrayList<PageBands>()
        val pages = flowBody(spec, book) { i -> bands.forPage(i, total = i + 1).also { built += it }.frame }
        Settled(pages, built, passes = 1, usedFallback = false)
    }
    drawBands(laid.pages, laid.bands, bands, book, spec.config)
    return LayoutResult(laid.pages, compactImages(laid.pages, table.all), laid.passes, laid.usedFallback)
}

/** One page's measured header and footer (`null` = none on that page) and the content frame they leave. */
private class PageBands(val header: Placeable?, val footer: Placeable?, val frame: PageFrame)

/** The accepted pass: its pages, the bands to draw on each, and how pagination got there. */
private class Settled(val pages: List<Page>, val bands: List<PageBands>, val passes: Int, val usedFallback: Boolean)

/**
 * Builds and measures each page's bands, and the frame they leave with each band reserving exactly
 * its own height. Static bands are measured once and shared by every page. A page-aware band's block
 * runs again for every page it is asked for; its images go through one deduplicating registry per
 * layout — shared by every pass, and seeded with what the build-time probes of both bands registered
 * — so a logo on every page is one image object, and so is the same logo instance in a static header
 * and a page-aware footer. Static bands' probes are seeded first, so that object is the one the static
 * band already draws. Documents without a page-aware band never use the registry.
 */
private class BandBuilder(spec: PdfDocumentSpec, private val book: TextMetrics, table: RenderImageTable) {
    private val cfg = spec.config
    private val header = spec.header
    private val footer = spec.footer
    private val marginTop = cfg.margins.top.mpt
    private val marginBottom = cfg.margins.bottom.mpt
    private val pageH = cfg.size.heightPt * MPT_PER_PT
    private val contentW = cfg.size.widthPt * MPT_PER_PT - cfg.margins.start.mpt - cfg.margins.end.mpt
    private val headerGap = header?.gapMpt ?: BAND_GAP_MPT
    val footerGap = footer?.gapMpt ?: BAND_GAP_MPT

    /** Height of the legacy page-number line; 0 when it is off. */
    val pnH = if (cfg.pageNumbers) lineHeightMpt(cfg.pageNumberStyle.fontSize.mpt, cfg.pageNumberStyle.lineHeightMultiple) else 0

    val pageAware = header is PageAwareBand || footer is PageAwareBand
    private val staticHeader = (header as? StaticBand)?.let { measureBand(it.nodes) }
    private val staticFooter = (footer as? StaticBand)?.let { measureBand(it.nodes) }
    private val registry = DedupingImageRegistry(
        table,
        listOfNotNull(header, footer).let { both ->
            both.filterIsInstance<StaticBand>().map { it.probed } + both.filterIsInstance<PageAwareBand>().map { it.probed }
        },
    )

    /** Page [index]'s bands in a document of [total] pages ([total] only matters to page-aware bands). */
    fun forPage(index: Int, total: Int): PageBands {
        // Without repeatHeader the header belongs to the first page only, page-aware or not.
        val head = if (index == 0 || cfg.repeatHeader) build(header, staticHeader, index, total) else null
        val foot = build(footer, staticFooter, index, total)
        return PageBands(head, foot, frameOf(head, foot))
    }

    private fun build(band: Band?, measured: Placeable?, index: Int, total: Int): Placeable? = when (band) {
        null -> null
        is StaticBand -> measured
        // A page-aware band that adds nothing on this page is absent there: no height, no gap.
        is PageAwareBand -> BandScope(registry, PageInfo(index + 1, total)).apply(band.build).nodes
            .takeIf { it.isNotEmpty() }
            ?.let { measureBand(it) }
    }

    private fun measureBand(nodes: List<Node>) = measure(ColumnNode(nodes, 0), contentW, book)

    /**
     * The content area left by [header] and [footer]. A header reserves its height plus its gap (even
     * when it measures 0, as a static `header {}` always has) below the top margin. At the bottom, the
     * stack footer → 12 pt → page-number line rests on the bottom margin and the footer gap separates
     * it from the content (the number line alone keeps that gap too).
     */
    private fun frameOf(header: Placeable?, footer: Placeable?): PageFrame {
        // Summed in Long and saturated: a band taller than `Int` leaves no room rather than wrapping.
        val top = (marginTop.toLong() + (header?.let { it.heightMpt.toLong() + headerGap } ?: 0L)).saturatedToInt()
        val footerH = footer?.heightMpt ?: 0
        val bottomReserve = footerH.toLong() + pnH +
            (if (footerH > 0 && pnH > 0) BAND_GAP_MPT else 0) +
            (if (footerH > 0 || pnH > 0) footerGap else 0)
        return PageFrame(top, (pageH.toLong() - marginBottom - bottomReserve).saturatedToInt())
    }

    /** Fails clearly instead of producing one page per element when bands leave page [index] no room. */
    fun requireRoom(frame: PageFrame, index: Int) {
        require(frame.usableBottom > frame.topY) { "Header and footer leave no room for content on page ${index + 1}" }
    }
}

/**
 * Paginates a document with a page-aware band. A band's height on a page is known only once the
 * total is, and the total depends on those heights, so flow passes repeat until they agree:
 *
 *  1. Guess a total (first 1). As page *i* opens, build its bands with `PageInfo(i + 1, max(guess, i + 1))`
 *     — user code always sees `number <= total`, pages past the guess being provisionally last — and
 *     reserve exactly their heights.
 *  2. A pass that ends with as many pages as it guessed built every band with the true total: done.
 *  3. Otherwise flow again with the count it produced. A last-page-only block can make the count
 *     cycle (10 → 11 → 10: the block pushes the last page's content onto an 11th page, and with 11
 *     pages page 10 is no longer last, so everything fits on 10); keep-together blocks can too. Once
 *     a guess repeats, each page reserves the most that the passes forming the cycle — from the first
 *     pass that guessed the repeated count on — needed for it, and every later pass can only add to
 *     that. Reservations only grow, so the count settles, and a band shorter than its reservation
 *     leaves extra room beside the content — never an overlap.
 *
 * Only pages a pass built with the total it guessed count towards a reservation: a page past the
 * guess is a provisional last page, and keeping its height would make every page reserve a
 * last-page-only block. For the same reason a page left without room fails the layout only when it
 * was built with a count some flow produced (any pass after the first — pass 1's guess of 1 builds
 * page 1 as both first and last, which a longer document's page 1 never is) or when its pass is
 * accepted.
 *
 * In that fallback a pass that ends with as many pages as it guessed was again built with the final
 * total, and each band fits by construction (its frame is its exact frame narrowed by the
 * reservation), so its bands are the final ones without being built again.
 */
private fun settle(spec: PdfDocumentSpec, book: TextMetrics, bands: BandBuilder, maxPasses: Int): Settled {
    var guess = 1
    // Until the fallback starts: each pass's guess and the frames of the pages it built with that guess.
    val history = ArrayList<Pair<Int, List<PageFrame>>>()
    var reserved: HashMap<Int, PageFrame>? = null // in the fallback, per page index: the tightest frame needed
    for (pass in 1..maxPasses) {
        val total = guess
        val built = ArrayList<PageBands>()
        val settled = ArrayList<PageFrame>() // frames of the pages before the guess, in page order
        val pages = flowBody(spec, book) { i ->
            val pageBands = bands.forPage(i, maxOf(total, i + 1))
            val frame = pageBands.frame.within(reserved?.get(i))
            if (i < total) {
                if (pass > 1) bands.requireRoom(frame, i)
                settled += frame
            }
            built += pageBands
            frame
        }
        val n = pages.size
        if (n == total) {
            // Every page of an accepted pass lies before its guess; this covers an accepted pass 1.
            for ((i, frame) in settled.withIndex()) bands.requireRoom(frame, i)
            return Settled(pages, built, pass, usedFallback = reserved != null)
        }
        val inFallback = reserved
        if (inFallback != null) {
            reserve(inFallback, settled)
        } else {
            history += total to settled
            val cycleStart = history.indexOfFirst { it.first == n }
            if (cycleStart >= 0) {
                val fresh = HashMap<Int, PageFrame>()
                for (k in cycleStart until history.size) reserve(fresh, history[k].second)
                reserved = fresh
                history.clear()
            }
        }
        guess = n
    }
    throw IllegalStateException(
        "Pagination did not settle after $maxPasses passes: the header/footer heights depend on " +
            "the page count and keep changing it",
    )
}

/** Narrows each page's reservation in [reserved] to also cover [frames] (index = page index). */
private fun reserve(reserved: HashMap<Int, PageFrame>, frames: List<PageFrame>) {
    for ((i, frame) in frames.withIndex()) reserved[i] = frame.within(reserved[i])
}

/**
 * Draws each page's bands: the header at the top margin, then the footer and the page-number line
 * stacked down to the bottom margin, all starting at the start margin. The footer sits
 * [BandBuilder.footerGap] below the bottom of its own exact frame, so it stays bottom-anchored even
 * where a fallback reservation left more room. All headers, then all footers, then all numbers — the
 * order the single-pass layout always used, so static documents keep their exact bytes.
 */
private fun drawBands(pages: List<Page>, bands: List<PageBands>, builder: BandBuilder, book: TextMetrics, cfg: PageConfig) {
    val left = cfg.margins.start.mpt
    val top = cfg.margins.top.mpt
    for ((i, p) in pages.withIndex()) bands[i].header?.place(left, top, p.ops)
    val numberTops = IntArray(pages.size)
    for ((i, p) in pages.withIndex()) {
        val footer = bands[i].footer
        val footerH = footer?.heightMpt ?: 0
        val footerTop = satAdd(bands[i].frame.usableBottom, builder.footerGap)
        footer?.place(left, footerTop, p.ops)
        numberTops[i] = (footerTop.toLong() + (if (footerH > 0) footerH.toLong() + (if (builder.pnH > 0) BAND_GAP_MPT else 0) else 0L)).saturatedToInt()
    }
    if (cfg.pageNumbers) addPageNumbers(pages, book, cfg, numberTops)
}

private fun addPageNumbers(pages: List<Page>, book: TextMetrics, cfg: PageConfig, topYForNumber: IntArray) {
    val style = cfg.pageNumberStyle
    val fs = style.fontSize.mpt
    val weight = style.fontWeight
    val left = cfg.margins.start.mpt
    val right = cfg.margins.end.mpt
    val ascent = book.ascentMpt(weight, fs)
    val total = pages.size
    for ((i, p) in pages.withIndex()) {
        val label = cfg.pageNumberFormat(i + 1, total)
        val tw = book.measureWidthMpt(label, weight, fs)
        val contentW = p.widthPt * MPT_PER_PT - left - right
        val x = when (style.align) {
            TextAlign.Start -> left
            TextAlign.Center -> left + halfTrunc(contentW - tw)
            TextAlign.End -> left + (contentW - tw)
        }
        book.placeLine(label, weight, fs, style.color, x, topYForNumber[i] + ascent, p.ops)
    }
}
