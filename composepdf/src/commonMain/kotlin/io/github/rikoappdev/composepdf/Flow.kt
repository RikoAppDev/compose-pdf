package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.layout.BoxNode
import io.github.rikoappdev.composepdf.layout.ColumnNode
import io.github.rikoappdev.composepdf.layout.DividerNode
import io.github.rikoappdev.composepdf.layout.ImageNode
import io.github.rikoappdev.composepdf.layout.Node
import io.github.rikoappdev.composepdf.layout.Placeable
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.SpacerNode
import io.github.rikoappdev.composepdf.layout.TableNode
import io.github.rikoappdev.composepdf.layout.TextNode
import io.github.rikoappdev.composepdf.layout.VectorNode
import io.github.rikoappdev.composepdf.layout.drawTableRow
import io.github.rikoappdev.composepdf.layout.measure
import io.github.rikoappdev.composepdf.layout.rowCellWidths
import io.github.rikoappdev.composepdf.layout.tableColumnWidths
import io.github.rikoappdev.composepdf.layout.tableRowHeight
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.text.LineBreaker

/** Don't open a box or start a split row with less than this much vertical room left (28 pt) — avoids an empty sliver. */
private const val MIN_BOX_OPEN_ROOM_MPT = 28 * MPT_PER_PT

/** A box currently being flowed; tracks the top of its fragment on the page its pen is on. Millipoints. */
private class BoxFrame(
    val x: Int,
    val width: Int,
    val padHMpt: Int,
    val padVMpt: Int,
    val borderMpt: Int,
    val borderColor: PdfColor,
    val background: PdfColor?,
    val cornerRadiusMpt: Int,
    val depth: Int,
    /** The continuation header, measured once at the box's inner width; drawn on fragments 2..n where it fits. */
    val header: Placeable?,
    var fragTop: Int,
)

/**
 * The pages of one single-pass flow, shared by every [Pen]. Text and tables flow line/row-by-row;
 * boxes and columns split across pages (their border/background is redrawn per page fragment, and a
 * box's continuation header is drawn at the top of each fragment after the first);
 * images and rows are atomic, except split rows, whose cells flow on their own in lanes, and — with
 * [overflowGuard] on — rows that would cross the content bottom even after moving, which are laid out
 * the same way. Each page's
 * content area comes from [frameFor], asked once per page, in page order, as the page opens, so bands
 * may reserve a different height on every page. [w] / [h] are the page size in whole points; every
 * position is an integer in millipoints → identical placement on every platform.
 */
private class Flow(
    private val w: Int,
    private val h: Int,
    private val frameFor: (pageIndex: Int) -> PageFrame,
    val book: TextMetrics,
    /** The row safety net: a non-split row that would cross the content bottom is laid out in lanes instead. */
    val overflowGuard: Boolean,
) {
    val pages = ArrayList<Page>()
    private val frames = ArrayList<PageFrame>()
    private val backgrounds = ArrayList<Triple<Int, Int, RectOp>>() // (pageIndex, depth, rect)

    /**
     * Set whenever a non-split row is placed with its bottom below the content bottom of its page, in
     * any pen. With [overflowGuard] on, only a row with no cells can still be placed that way.
     */
    var rowCrossedBottom = false

    init { addPage() }

    fun frame(pageIndex: Int): PageFrame = frames[pageIndex]

    /** Opens the next new page and asks for its frame. */
    fun addPage() {
        pages.add(Page(w, h))
        frames.add(frameFor(pages.lastIndex))
    }

    fun addBackground(pageIndex: Int, depth: Int, rect: RectOp) {
        backgrounds.add(Triple(pageIndex, depth, rect))
    }

    fun finish(): List<Page> {
        // Backgrounds go behind content; outer boxes (lower depth) behind inner ones.
        for ((pi, list) in backgrounds.groupBy { it.first }) {
            pages[pi].ops.addAll(0, list.sortedBy { it.second }.map { it.third })
        }
        return pages
    }
}

/**
 * A write position over the shared [flow]: the page it is on ([pageIndex]), its [y] there, and the
 * boxes it has open. The root pen (no [parent]) flows the document body and is always on the last
 * page. A split row lays each of its cells out in a *lane*: a child pen of the pen the row is in,
 * starting at the row's top and moving page by page on its own over the shared pages.
 *
 * Entering a page goes up the parent chain first: every pen in the chain closes its open boxes'
 * fragments on the page being left, then sets their fragment top on the new page and steps below
 * their top padding and, where it fits, their continuation header (drawn there), once per page
 * however many lanes enter it ([entryFor]). Only the root pen opens pages, so pages are still created
 * on demand, in order.
 */
private class Pen(val flow: Flow, private val parent: Pen?, var pageIndex: Int, var y: Int) {
    private val openBoxes = ArrayList<BoxFrame>()

    /** How many of [openBoxes] have a continuation header. */
    private var headerBoxes = 0

    /** The last page this pen has entered: its own page, or — while a row it holds is laid out — the deepest page its lanes reached. */
    private var enteredUpTo = pageIndex

    /** While a row of this pen is laid out: the entry y of pages `entryBase + 1 .. enteredUpTo`, for its lanes. */
    private val entries = ArrayList<Int>()
    private var entryBase = pageIndex

    val book: TextMetrics get() = flow.book
    val ops get() = flow.pages[pageIndex].ops
    private val frame get() = flow.frame(pageIndex)
    val usableBottom get() = frame.usableBottom

    /**
     * A precise pen measures "the top" of a page from the content top instead of the frame top (see
     * [top]) and defers spacing-only page breaks ([breakAfterSpacing]). Lanes are precise, and so is
     * any pen while a box with a continuation header is open in it; a root pen without such a box is
     * not, so documents without split rows or continuation headers keep every check exactly as before.
     */
    val precise: Boolean get() = parent != null || headerBoxes > 0

    /**
     * "The top" of the current page, for the at-the-top tests, the keep-together empty-page height and
     * the image / vector clamp: the frame top, or in a precise pen the content top ([contentTop]).
     */
    val top: Int get() = if (precise) contentTop(pageIndex) else frame.topY

    /** Boxes open around the pen, the parent chain's included (orders background fragments). */
    val depth: Int get() = (parent?.depth ?: 0) + openBoxes.size

    fun roomLeft() = usableBottom - y

    /** Moves the pen down by [heightMpt], saturating: a block taller than `Int` leaves the pen past every page bottom. */
    fun advance(heightMpt: Int) { y = satAdd(y, heightMpt) }

    /** Whether a block of [heightMpt] starting at the pen ends below the content bottom (computed without overflow). */
    fun overflows(heightMpt: Int) = y.toLong() + heightMpt > usableBottom

    /** Moves the pen to the top of the next page, opening it if it is new. */
    fun newPage() {
        y = enterNext()
        pageIndex = enteredUpTo
    }

    /**
     * Called after a spacer or a sibling gap moved the pen down from [from]. Outside precise pens a pen
     * past the content bottom starts a new page, as always. In a precise pen it does not (F): the pen
     * stays at the content bottom (or where it stood, if already below it), so the spacing is dropped
     * at the break and the next real content breaks the page through its own check.
     */
    fun breakAfterSpacing(from: Int) {
        if (y <= usableBottom) return
        if (precise) y = maxOf(usableBottom, minOf(from, y)) else newPage()
    }

    /** Opens [b] at the pen: its first fragment starts here and its content below the top padding (no header on the first fragment). */
    fun openBox(b: BoxFrame) {
        b.fragTop = y
        openBoxes.add(b)
        if (b.header != null) headerBoxes++
        advance(b.padVMpt)
    }

    fun closeBox(b: BoxFrame) {
        advance(b.padVMpt)
        emitFragment(b, pageIndex, b.fragTop, y)
        openBoxes.removeAt(openBoxes.lastIndex)
        if (b.header != null) headerBoxes--
    }

    /**
     * Content top of page [pageIndex] for this pen: the frame top plus the top padding and the fitting
     * continuation header of every box open around it, up the chain — where a fresh continuation page
     * puts this pen, on any page.
     */
    fun contentTop(pageIndex: Int): Int =
        padded(pageIndex, parent?.contentTop(pageIndex) ?: flow.frame(pageIndex).topY, enter = false)

    /** Starts a row of lanes at the pen: they ask [entryFor] for the pages after this one. */
    fun beginRow() {
        entries.clear()
        entryBase = pageIndex
    }

    /**
     * Entry y of page [pageIndex] (after the row's first page) for a lane of this pen: memoised, so the
     * boxes open here get one fragment per page and their padding and continuation header once,
     * whichever lane enters first.
     */
    fun entryFor(pageIndex: Int): Int {
        while (enteredUpTo < pageIndex) entries.add(enterNext())
        return entries[pageIndex - entryBase - 1]
    }

    /** Ends a row of lanes: the pen continues below the deepest lane, at [y] on page [pageIndex]. */
    fun endRow(pageIndex: Int, y: Int) {
        this.pageIndex = pageIndex
        this.y = y
    }

    /**
     * Enters the page after [enteredUpTo]: the chain above first, then this pen's open boxes close their
     * fragments on the page being left and step below their top padding and continuation header on the
     * new one. Returns the entry y. The root pen closes its fragments before the page opens, as the flow
     * always has.
     */
    private fun enterNext(): Int {
        val left = enteredUpTo
        val base = if (parent != null) {
            parent.entryFor(left + 1).also { closeFragments(left) }
        } else {
            closeFragments(left)
            flow.addPage() // the root pen is always on the last page: the one after it is new
            flow.frame(left + 1).topY
        }
        enteredUpTo = left + 1
        return padded(left + 1, base, enter = true)
    }

    /**
     * [from] stepped below what this pen's open boxes reserve at the top of a continuation fragment on
     * page [pageIndex], outer to inner: each box's top padding, then its continuation header if it fits
     * between there and the content bottom (a header that does not fit is skipped on that page). When
     * [enter]ing the page, each fragment starts at its step and each fitting header is drawn, whole, at
     * the box's inner left edge.
     */
    private fun padded(pageIndex: Int, from: Int, enter: Boolean): Int {
        var t = from
        for (b in openBoxes) {
            if (enter) b.fragTop = t
            t = satAdd(t, b.padVMpt)
            val header = b.header ?: continue
            if (header.heightMpt.toLong() <= flow.frame(pageIndex).usableBottom.toLong() - t) {
                if (enter) header.place(b.x + b.padHMpt, t, flow.pages[pageIndex].ops)
                t = satAdd(t, header.heightMpt)
            }
        }
        return t
    }

    /** Open box fragments on page [pageIndex] end at its content bottom. */
    private fun closeFragments(pageIndex: Int) {
        val bottom = flow.frame(pageIndex).usableBottom
        for (b in openBoxes) emitFragment(b, pageIndex, b.fragTop, bottom)
    }

    private fun emitFragment(b: BoxFrame, pageIndex: Int, top: Int, bottom: Int) {
        val height = bottom - top
        if (height <= 0) return
        if (b.background != null) {
            flow.addBackground(pageIndex, b.depth, RectOp(b.x, top, b.width, height, fill = b.background, stroke = null, strokeWidthMpt = 0, cornerRadiusMpt = b.cornerRadiusMpt))
        }
        if (b.borderMpt > 0) {
            flow.pages[pageIndex].ops.add(RectOp(b.x, top, b.width, height, fill = null, stroke = b.borderColor, strokeWidthMpt = b.borderMpt, cornerRadiusMpt = b.cornerRadiusMpt))
        }
    }
}

/** One flow pass's pages, and whether a non-split row was drawn past a content bottom in it. */
internal class FlowResult(val pages: List<Page>, val rowCrossedBottom: Boolean)

/**
 * One flow pass: lays [spec]'s body out into pages whose content areas come from [frameFor] (asked
 * once per page, in page order, as each page opens). The body spans the page width between the start
 * and end margins. Bands are not drawn here — see [layoutDocument].
 *
 * With [overflowGuard] off this is exactly the flow of earlier releases, where a non-split row that
 * does not fit even after moving to the next page is drawn past the content bottom; the result says
 * whether that happened. With it on, such a row is laid out like a split row instead.
 */
internal fun flowBody(spec: PdfDocumentSpec, book: TextMetrics, overflowGuard: Boolean, frameFor: (pageIndex: Int) -> PageFrame): FlowResult {
    val cfg = spec.config
    val left = cfg.margins.start.mpt
    val contentW = cfg.size.widthPt * MPT_PER_PT - left - cfg.margins.end.mpt
    val flow = Flow(cfg.size.widthPt, cfg.size.heightPt, frameFor, book, overflowGuard)
    val root = Pen(flow, parent = null, pageIndex = 0, y = flow.frame(0).topY)
    for (node in spec.nodes) flowNode(node, left, contentW, root, cfg)
    return FlowResult(flow.finish(), flow.rowCrossedBottom)
}

/** Flows a list of sibling nodes with [gapMpt] between them. */
private fun flowNodes(nodes: List<Node>, x: Int, availW: Int, gapMpt: Int, pen: Pen, cfg: PageConfig) {
    for ((i, child) in nodes.withIndex()) {
        flowNode(child, x, availW, pen, cfg)
        if (gapMpt > 0 && i < nodes.size - 1) {
            val from = pen.y
            pen.advance(gapMpt)
            pen.breakAfterSpacing(from)
        }
    }
}

private fun flowNode(node: Node, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    when (node) {
        is SpacerNode -> {
            val from = pen.y
            pen.advance(node.heightMpt)
            pen.breakAfterSpacing(from)
        }
        is DividerNode -> {
            if (pen.overflows(node.thicknessMpt) && pen.y > pen.top) pen.newPage()
            pen.ops.add(RectOp(x, pen.y, availW, node.thicknessMpt, fill = node.color, stroke = null, strokeWidthMpt = 0))
            pen.advance(node.thicknessMpt)
        }
        is TextNode -> flowText(node, x, availW, pen, cfg)
        is ColumnNode -> flowNodes(node.children, x, availW, node.gapMpt, pen, cfg)
        is BoxNode -> {
            val padH = node.padHMpt
            // Keep-together: a box that fits on a page is moved whole rather than split with its
            // header orphaned at the page bottom. A box taller than a page — including one whose
            // height saturated past `Int` — still splits and flows. The box's own height decides;
            // its continuation header is not part of it.
            if ((node.keepTogether ?: cfg.keepBlocksTogether) && pen.y > pen.top) {
                val boxH = measure(node, availW, pen.book).heightMpt
                if (boxH <= pen.usableBottom - pen.top && pen.overflows(boxH)) pen.newPage()
            }
            if (pen.y > pen.top && pen.roomLeft() < MIN_BOX_OPEN_ROOM_MPT) pen.newPage()
            val innerW = (availW - 2 * padH).coerceAtLeast(0)
            val header = node.continuationHeader?.let { measure(it, innerW, pen.book) }
            val frame = BoxFrame(x, availW, padH, node.padVMpt, node.borderMpt, node.borderColor, node.background, node.cornerRadiusMpt, pen.depth, header, pen.y)
            pen.openBox(frame)
            flowNode(node.child, x + padH, innerW, pen, cfg)
            pen.closeBox(frame)
        }
        is TableNode -> flowTable(node, x, availW, pen, cfg)
        is RowNode -> if (node.split) flowSplitRow(node, x, availW, pen, cfg) else placeAtomic(node, measure(node, availW, pen.book), x, availW, pen, cfg)
        is ImageNode, is VectorNode -> placeAtomic(node, measure(node, availW, pen.book), x, availW, pen, cfg)
    }
}

/**
 * Places an atomic [node] ([natural] is its measure): it never splits, and moves whole to the next
 * page if it doesn't fit. A non-split row that would still cross the content bottom from there is the
 * one exception, with the flow's overflow guard on: it is laid out in lanes instead (see [flowLanes]).
 */
private fun placeAtomic(node: Node, natural: Placeable, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    val roomBefore = pen.usableBottom - pen.top
    if (pen.overflows(natural.heightMpt) && pen.y > pen.top) pen.newPage()
    // An image/vector taller than the content area can never fit; clamp it so it doesn't draw
    // past the page bottom into the footer. The page it landed on may have less room than the
    // one it left (page-aware bands), hence the smaller of the two — without page-aware bands
    // the next page never has less, so this is the old clamp. Valid documents use heights far
    // smaller than a page, so this never triggers for them.
    val room = minOf(roomBefore, pen.usableBottom - pen.top)
    val p = when {
        node is ImageNode && node.heightMpt > room -> measure(node.withHeight(room), availW, pen.book)
        node is VectorNode && node.heightMpt > room -> measure(node.withHeight(room), availW, pen.book)
        else -> natural
    }
    if (node is RowNode && !node.split && pen.overflows(p.heightMpt)) {
        // A row cannot shrink, and after the move above it still crosses the content bottom: with
        // the guard on it is split instead, from where it stands, top-aligned, exactly as a split row
        // that does not fit (its cells continue in lanes, images in them clamped to the lane area).
        // Without the guard it is drawn past the bottom, as in earlier releases, and the flow
        // records it so the layout can run again with the guard on.
        if (pen.flow.overflowGuard && node.children.isNotEmpty()) {
            flowLanes(node, x, availW, pen, cfg)
            return
        }
        pen.flow.rowCrossedBottom = true
    }
    p.place(x, pen.y, pen.ops)
    pen.advance(p.heightMpt)
}

/**
 * A `row(split = true)`. If it fits the room left it is placed like any row. Otherwise it may move to
 * the next page — whole when keep-together applies and it fits an empty page, or because less than
 * 28 pt is left — and if it still doesn't fit there, its cells are laid out as lanes from the pen.
 */
private fun flowSplitRow(node: RowNode, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    val natural = measure(node, availW, pen.book)
    val h = natural.heightMpt
    if (pen.overflows(h)) {
        if ((node.keepTogether ?: cfg.keepBlocksTogether) && pen.y > pen.top && h <= pen.usableBottom - pen.top) {
            pen.newPage()
        } else if (pen.y > pen.top && pen.roomLeft() < MIN_BOX_OPEN_ROOM_MPT) {
            pen.newPage()
        }
    }
    if (!pen.overflows(h) || node.children.isEmpty()) placeAtomic(node, natural, x, availW, pen, cfg) else flowLanes(node, x, availW, pen, cfg)
}

/**
 * Lays [node]'s cells out from the pen as lanes, top-aligned: each cell flows through [flowNode] in
 * its own [Pen], at the x and width the row's measure gives it, starting at the pen and continuing on
 * later pages at the entry y the pen's chain gives them. The pen then continues below the deepest
 * lane: on the highest page reached, at the lowest lane y there. Used by split rows that do not fit
 * and, under the overflow guard, by non-split rows that would cross the content bottom.
 */
private fun flowLanes(node: RowNode, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    val widths = rowCellWidths(node, availW, pen.book)
    val startPage = pen.pageIndex
    val startY = pen.y
    pen.beginRow()
    var endPage = startPage
    var endY = Int.MIN_VALUE
    var cx = x
    for (i in node.children.indices) {
        val lane = Pen(pen.flow, pen, startPage, startY)
        flowNode(node.children[i].node, cx, widths[i], lane, cfg)
        if (lane.pageIndex > endPage || (lane.pageIndex == endPage && lane.y > endY)) {
            endPage = lane.pageIndex
            endY = lane.y
        }
        cx = (cx.toLong() + widths[i] + node.gapMpt).saturatedToInt()
    }
    pen.endRow(endPage, endY)
}

private fun flowText(node: TextNode, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    val weight = node.style.fontWeight
    val fs = node.style.fontSize.mpt
    val lineH = lineHeightMpt(fs, node.style.lineHeightMultiple)
    val ascent = pen.book.ascentMpt(weight, fs)
    val lines = LineBreaker.wrap(node.text, weight, fs, availW, pen.book)
    // Keep-together: a paragraph that fits on a page is moved whole rather than leaving a sliver. A
    // height saturated past `Int` is taller than any page, so it flows line by line instead.
    val totalH = satMul(lines.size, lineH)
    if (cfg.keepBlocksTogether && pen.y > pen.top && pen.overflows(totalH) && totalH <= pen.usableBottom - pen.top) {
        pen.newPage()
    }
    for (line in lines) {
        if (pen.overflows(lineH) && pen.y > pen.top) pen.newPage()
        if (line.isNotEmpty()) {
            val lw = pen.book.measureWidthMpt(line, weight, fs)
            val lx = when (node.style.align) {
                TextAlign.Start -> x
                TextAlign.Center -> x + halfTrunc(availW - lw)
                TextAlign.End -> x + (availW - lw)
            }
            pen.book.placeLine(line, weight, fs, node.style.color, lx, pen.y + ascent, pen.ops)
        }
        pen.advance(lineH)
    }
}

private fun flowTable(node: TableNode, x: Int, availW: Int, pen: Pen, cfg: PageConfig) {
    val colWidths = tableColumnWidths(node.columns, availW)
    val headerCells = node.columns.map { it.header }
    val aligns = node.columns.map { it.align }
    val headerH = tableRowHeight(headerCells, node.headerStyle, colWidths, node.cellPadHMpt, node.cellPadVMpt, pen.book)
    fun placeHeader() {
        if (pen.overflows(headerH) && pen.y > pen.top) pen.newPage()
        drawTableRow(headerCells, node.headerStyle, aligns, x, pen.y, headerH, colWidths, node.cellPadHMpt, node.cellPadVMpt, node.headerBackground, node.gridColor, pen.book, pen.ops)
        pen.advance(headerH)
    }
    placeHeader()
    for (row in node.rows) {
        val rh = tableRowHeight(row.cells, row.style, colWidths, node.cellPadHMpt, node.cellPadVMpt, pen.book)
        if (pen.overflows(rh) && pen.y > pen.top) {
            pen.newPage()
            if (node.repeatHeader) placeHeader()
        }
        drawTableRow(row.cells, row.style, aligns, x, pen.y, rh, colWidths, node.cellPadHMpt, node.cellPadVMpt, row.background, node.gridColor, pen.book, pen.ops)
        pen.advance(rh)
    }
}
