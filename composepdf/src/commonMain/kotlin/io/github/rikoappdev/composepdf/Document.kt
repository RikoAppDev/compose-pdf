package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.image.parseJpeg
import io.github.rikoappdev.composepdf.layout.BoxNode
import io.github.rikoappdev.composepdf.layout.ColumnNode
import io.github.rikoappdev.composepdf.layout.DividerNode
import io.github.rikoappdev.composepdf.layout.ImageNode
import io.github.rikoappdev.composepdf.layout.Node
import io.github.rikoappdev.composepdf.layout.RowChild
import io.github.rikoappdev.composepdf.layout.RowNode
import io.github.rikoappdev.composepdf.layout.SpacerNode
import io.github.rikoappdev.composepdf.layout.TableColumn
import io.github.rikoappdev.composepdf.layout.TableNode
import io.github.rikoappdev.composepdf.layout.TableRow
import io.github.rikoappdev.composepdf.layout.TextNode
import io.github.rikoappdev.composepdf.layout.drawTableRow
import io.github.rikoappdev.composepdf.layout.measure
import io.github.rikoappdev.composepdf.layout.tableColumnWidths
import io.github.rikoappdev.composepdf.layout.tableRowHeight
import io.github.rikoappdev.composepdf.pdf.JpegImage
import io.github.rikoappdev.composepdf.pdf.serializePdf
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.text.LineBreaker

enum class PageSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

class PageConfig(
    val size: PageSize = PageSize.A4,
    val margin: Dp = 36.dp,
    /** Auto page-number line at the bottom of every page. Its vertical space is reserved so content
     *  never overlaps it. Combine freely with a custom [ContainerScope.footer] band (footer sits
     *  above the number). */
    val pageNumbers: Boolean = true,
    val pageNumberStyle: TextStyle = TextStyle(fontSize = 8.sp, color = Color(0xFF7F807F), align = TextAlign.Center),
    /** Formats the page-number label, e.g. `{ page, total -> "Page $page of $total" }`. */
    val pageNumberFormat: (page: Int, total: Int) -> String = { page, total -> "$page / $total" },
    /** When true (default) the `header` band repeats on every page; when false it appears only on the
     *  first page and later pages reclaim that space (like a title block in Word / Google Docs). */
    val repeatHeader: Boolean = true,
    /**
     * When true (default), a paragraph that fits within a single page is moved **whole** to the next
     * page instead of being split, when it doesn't fit the remaining space — so a block never leaves
     * an awkward sliver at the bottom of a page. Paragraphs taller than a full page still split and
     * flow across pages. Atomic blocks (boxes, rows, images, tables) already move whole when they
     * don't fit.
     */
    val keepBlocksTogether: Boolean = true,
)

/** Compose-style container: stacks children vertically. Shared by the document root, columns,
 *  box contents and row cells, so authoring reads like nested Compose. */
open class ContainerScope internal constructor(internal val images: MutableList<JpegImage>) {
    internal val nodes = ArrayList<Node>()

    fun text(text: String, style: TextStyle = TextStyle()) { nodes.add(TextNode(text, style)) }
    fun spacer(height: Dp) { nodes.add(SpacerNode(0, height.value)) }
    fun divider(color: PdfColor = Color(0xFFDDDDDD), thickness: Dp = 1.dp) {
        nodes.add(DividerNode(color, thickness.value))
    }

    fun column(gap: Dp = 0.dp, build: ContainerScope.() -> Unit) {
        nodes.add(ColumnNode(ContainerScope(images).apply(build).nodes, gap.value))
    }

    fun row(gap: Dp = 0.dp, build: RowScope.() -> Unit) {
        nodes.add(RowScope(images).apply(build).toNode(gap.value))
    }

    /** A label/value line: [label] on the left, [value] right-aligned. Common in detail reports. */
    fun keyValue(
        label: String,
        value: String,
        labelStyle: TextStyle = TextStyle(fontSize = 9.sp, color = Color(0xFF6C757D)),
        valueStyle: TextStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold),
        gap: Dp = 8.dp,
    ) {
        row(gap) {
            cell(1f) { text(label, labelStyle) }
            cell(1f) { text(value, valueStyle.copy(align = TextAlign.End)) }
        }
    }

    fun box(
        padding: Dp = 0.dp,
        border: Dp = 0.dp,
        borderColor: PdfColor = Color(0xFF6C757D),
        background: PdfColor? = null,
        cornerRadius: Dp = 0.dp,
        build: ContainerScope.() -> Unit,
    ) {
        nodes.add(
            BoxNode(
                child = ColumnNode(ContainerScope(images).apply(build).nodes, 0),
                paddingPt = padding.value,
                borderPt = border.value,
                borderColor = borderColor,
                background = background,
                cornerRadiusPt = cornerRadius.value,
            )
        )
    }

    /** Embeds a JPEG. [width] = 0.dp fills the available width. [fit] controls cover/contain/smart. */
    fun image(jpegBytes: ByteArray, width: Dp = 0.dp, height: Dp, fit: PhotoFit = PhotoFit.Cover) {
        val info = parseJpeg(jpegBytes)
        val index = images.size
        images.add(JpegImage(info.width, info.height, info.components, jpegBytes))
        nodes.add(ImageNode(index, width.value, height.value, info.width, info.height, fit))
    }

    /**
     * Lays out [photos] (JPEG bytes) in a grid of [perRow] equal cells, each [cellHeight] tall.
     * [fit] = [PhotoFit.Cover] crops each photo to fill its cell; [PhotoFit.Contain] fits the whole
     * photo preserving aspect; [PhotoFit.Smart] preserves aspect but crops extreme-aspect photos so
     * they don't become thin slivers.
     */
    fun photoGrid(photos: List<ByteArray>, perRow: Int = 3, cellHeight: Dp, gap: Dp = 6.dp, fit: PhotoFit = PhotoFit.Cover) {
        photos.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            if (rowIndex > 0) spacer(gap)
            row(gap) {
                chunk.forEach { bytes -> cell(1f) { image(bytes, width = 0.dp, height = cellHeight, fit = fit) } }
                repeat(perRow - chunk.size) { cell(1f) {} } // pad to keep cells aligned
            }
        }
    }

    /** A table with weighted columns, a repeating header, body rows and bold total rows.
     *  At the document top level it splits across pages (repeating the header); nested inside a
     *  container it is placed atomically. */
    fun table(
        columns: List<PdfColumn>,
        headerStyle: TextStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF212529)),
        cellStyle: TextStyle = TextStyle(fontSize = 8.sp, color = Color(0xFF212529)),
        totalStyle: TextStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF212529)),
        headerBackground: PdfColor? = null,
        totalBackground: PdfColor? = Color(0xFFF1F3F5),
        zebra: PdfColor? = null,
        gridColor: PdfColor = Color(0xFFDDDDDD),
        cellPadding: Dp = 4.dp,
        cellPaddingHorizontal: Dp = cellPadding,
        cellPaddingVertical: Dp = cellPadding,
        repeatHeader: Boolean = true,
        build: TableScope.() -> Unit,
    ) {
        val scope = TableScope(cellStyle, totalStyle, totalBackground, zebra).apply(build)
        nodes.add(
            TableNode(
                columns = columns.map { TableColumn(it.weight, it.header, it.align) },
                rows = scope.rows,
                headerStyle = headerStyle,
                headerBackground = headerBackground,
                cellPadHPt = cellPaddingHorizontal.value,
                cellPadVPt = cellPaddingVertical.value,
                gridColor = gridColor,
                repeatHeader = repeatHeader,
            )
        )
    }
}

class PdfColumn(val weight: Float = 1f, val header: String = "", val align: TextAlign = TextAlign.Start)

class TableScope internal constructor(
    private val cellStyle: TextStyle,
    private val totalStyle: TextStyle,
    private val totalBackground: PdfColor?,
    private val zebra: PdfColor? = null,
) {
    internal val rows = ArrayList<TableRow>()
    private var bodyIndex = 0

    /** Background for the next body row: [zebra] on every other row (when set), else none. */
    private fun nextBodyBackground(): PdfColor? =
        (if (zebra != null && bodyIndex % 2 == 1) zebra else null).also { bodyIndex++ }

    fun row(vararg cells: String) { rows.add(TableRow(cells.toList(), cellStyle, nextBodyBackground())) }
    fun row(cells: List<String>) { rows.add(TableRow(cells, cellStyle, nextBodyBackground())) }
    fun totalRow(vararg cells: String) { rows.add(TableRow(cells.toList(), totalStyle, totalBackground)) }

    /** A bold total/summary row with an explicit [background] (e.g. a distinct grand-total colour). */
    fun totalRow(cells: List<String>, background: PdfColor? = totalBackground) {
        rows.add(TableRow(cells, totalStyle, background))
    }
}

/** Distributes width across weighted cells (like `Modifier.weight`). */
class RowScope internal constructor(private val images: MutableList<JpegImage>) {
    private val cells = ArrayList<RowChild>()

    fun cell(weight: Float = 1f, build: ContainerScope.() -> Unit) {
        cells.add(RowChild(ColumnNode(ContainerScope(images).apply(build).nodes, 0), weight))
    }

    internal fun toNode(gapPt: Int) = RowNode(cells.toList(), gapPt)
}

class PdfContentScope internal constructor(images: MutableList<JpegImage>) : ContainerScope(images) {
    internal var headerNodes: List<Node>? = null
    internal var footerNodes: List<Node>? = null

    /** A band repeated at the top of every page (e.g. a title or logo). Built once. */
    fun header(build: ContainerScope.() -> Unit) { headerNodes = ContainerScope(images).apply(build).nodes }

    /** A band repeated at the bottom of every page (above the page-number line). Built once. */
    fun footer(build: ContainerScope.() -> Unit) { footerNodes = ContainerScope(images).apply(build).nodes }
}

fun pdfDocument(config: PageConfig = PageConfig(), build: PdfContentScope.() -> Unit): PdfDocumentSpec {
    val images = ArrayList<JpegImage>()
    val scope = PdfContentScope(images).apply(build)
    return PdfDocumentSpec(config, scope.nodes, images, scope.headerNodes, scope.footerNodes)
}

class PdfDocumentSpec internal constructor(
    internal val config: PageConfig,
    internal val nodes: List<Node>,
    internal val images: List<JpegImage>,
    internal val headerNodes: List<Node>?,
    internal val footerNodes: List<Node>?,
) {
    /**
     * Renders to PDF bytes using the supplied Regular + Bold TrueType fonts.
     *
     * Fonts are supplied by the caller (the application), not bundled in the library: the app picks
     * a default and can let the user choose another, then passes the chosen face bytes here. This
     * keeps the engine font-agnostic and dependency-free, and works identically on every platform
     * (the app reads its own bundled `.ttf` via its resource mechanism). Only the glyphs a document
     * uses are subset and embedded.
     */
    fun render(regularFontBytes: ByteArray, boldFontBytes: ByteArray): ByteArray {
        val book = FontBook(regularFontBytes, boldFontBytes)
        return serializePdf(layout(this, book), book, images)
    }
}

private const val BAND_GAP_PT = 12

/** Don't open a box with less than this much vertical room left — avoids an empty box sliver. */
private const val MIN_BOX_OPEN_ROOM_PT = 28

/** A box currently being flowed; tracks the top of its fragment on the current page. */
private class BoxFrame(
    val x: Int,
    val width: Int,
    val padPt: Int,
    val borderPt: Int,
    val borderColor: PdfColor,
    val background: PdfColor?,
    val cornerRadiusPt: Int,
    val depth: Int,
    var fragTop: Int,
)

/**
 * Single-pass page flow. Text and tables flow line/row-by-row; boxes and columns split across pages
 * (their border/background is redrawn per page fragment); rows and images are atomic (never split).
 * All integer math → identical placement on every platform.
 */
private class Flow(
    val w: Int,
    val h: Int,
    private val firstTopY: Int,
    private val otherTopY: Int,
    val usableBottom: Int,
    val book: TextMetrics,
) {
    val pages = ArrayList<Page>()
    var page = Page(w, h).also { pages.add(it) }
    var topY = firstTopY   // content top on the current page (later pages may reclaim header space)
        private set
    var y = topY
    private val openBoxes = ArrayList<BoxFrame>()
    private val backgrounds = ArrayList<Triple<Int, Int, RectOp>>() // (pageIndex, depth, rect)

    val depth get() = openBoxes.size
    fun roomLeft() = usableBottom - y

    fun newPage() {
        for (b in openBoxes) emitFragment(b, b.fragTop, usableBottom)
        page = Page(w, h).also { pages.add(it) }
        topY = otherTopY
        y = topY
        for (b in openBoxes) { b.fragTop = y; y += b.padPt }
    }

    fun openBox(b: BoxFrame) {
        b.fragTop = y
        openBoxes.add(b)
        y += b.padPt
    }

    fun closeBox(b: BoxFrame) {
        y += b.padPt
        emitFragment(b, b.fragTop, y)
        openBoxes.removeAt(openBoxes.lastIndex)
    }

    private fun emitFragment(b: BoxFrame, top: Int, bottom: Int) {
        val height = bottom - top
        if (height <= 0) return
        if (b.background != null) {
            backgrounds.add(Triple(pages.size - 1, b.depth, RectOp(b.x, top, b.width, height, fill = b.background, stroke = null, strokeWidthPt = 0, cornerRadiusPt = b.cornerRadiusPt)))
        }
        if (b.borderPt > 0) {
            page.ops.add(RectOp(b.x, top, b.width, height, fill = null, stroke = b.borderColor, strokeWidthPt = b.borderPt, cornerRadiusPt = b.cornerRadiusPt))
        }
    }

    fun finish(): List<Page> {
        // Backgrounds go behind content; outer boxes (lower depth) behind inner ones.
        for ((pi, list) in backgrounds.groupBy { it.first }) {
            pages[pi].ops.addAll(0, list.sortedBy { it.second }.map { it.third })
        }
        return pages
    }
}

internal fun layout(spec: PdfDocumentSpec, book: TextMetrics): List<Page> {
    val cfg = spec.config
    val w = cfg.size.widthPt
    val h = cfg.size.heightPt
    val m = cfg.margin.value
    val contentW = w - 2 * m

    // Repeating bands reserve space at the top/bottom; the flow lives between them.
    val headerP = spec.headerNodes?.let { measure(ColumnNode(it, 0), contentW, book) }
    val footerP = spec.footerNodes?.let { measure(ColumnNode(it, 0), contentW, book) }
    val headerReserve = headerP?.let { it.heightPt + BAND_GAP_PT } ?: 0
    val footerH = footerP?.heightPt ?: 0
    val pnH = if (cfg.pageNumbers) (cfg.pageNumberStyle.fontSize.value * cfg.pageNumberStyle.lineHeightMultiple).toInt() else 0
    // Reserve the bottom band so content never overlaps the footer / page number.
    val bottomReserve = footerH + pnH +
        (if (footerH > 0 && pnH > 0) BAND_GAP_PT else 0) +
        (if (footerH > 0 || pnH > 0) BAND_GAP_PT else 0)

    val firstTopY = m + headerReserve
    val otherTopY = m + (if (cfg.repeatHeader) headerReserve else 0)
    val usableBottom = h - m - bottomReserve

    val ctx = Flow(w, h, firstTopY, otherTopY, usableBottom, book)
    for (node in spec.nodes) flowNode(node, m, contentW, ctx, cfg)
    val pages = ctx.finish()

    // Header: first page always; later pages only if it repeats.
    for ((i, p) in pages.withIndex()) {
        if (headerP != null && (i == 0 || cfg.repeatHeader)) headerP.place(m, m, p.ops)
    }
    // Footer band then the page-number line, stacked just below the content area.
    val footerTop = usableBottom + BAND_GAP_PT
    val pnTop = footerTop + (if (footerH > 0) footerH + (if (pnH > 0) BAND_GAP_PT else 0) else 0)
    if (footerP != null) for (p in pages) footerP.place(m, footerTop, p.ops)
    if (cfg.pageNumbers) addPageNumbers(pages, book, cfg, pnTop)
    return pages
}

/** Flows a list of sibling nodes with [gapPt] between them. */
private fun flowNodes(nodes: List<Node>, x: Int, availW: Int, gapPt: Int, ctx: Flow, cfg: PageConfig) {
    for ((i, child) in nodes.withIndex()) {
        flowNode(child, x, availW, ctx, cfg)
        if (gapPt > 0 && i < nodes.size - 1) {
            ctx.y += gapPt
            if (ctx.y > ctx.usableBottom) ctx.newPage()
        }
    }
}

private fun flowNode(node: Node, x: Int, availW: Int, ctx: Flow, cfg: PageConfig) {
    when (node) {
        is SpacerNode -> {
            ctx.y += node.heightPt
            if (ctx.y > ctx.usableBottom) ctx.newPage()
        }
        is DividerNode -> {
            if (ctx.y + node.thicknessPt > ctx.usableBottom && ctx.y > ctx.topY) ctx.newPage()
            ctx.page.ops.add(RectOp(x, ctx.y, availW, node.thicknessPt, fill = node.color, stroke = null, strokeWidthPt = 0))
            ctx.y += node.thicknessPt
        }
        is TextNode -> flowText(node, x, availW, ctx, cfg)
        is ColumnNode -> flowNodes(node.children, x, availW, node.gapPt, ctx, cfg)
        is BoxNode -> {
            val pad = node.paddingPt
            // Keep-together: a box that fits on a page is moved whole rather than split with its
            // header orphaned at the page bottom. A box taller than a page still splits and flows.
            if (cfg.keepBlocksTogether && ctx.y > ctx.topY) {
                val boxH = measure(node, availW, ctx.book).heightPt
                if (boxH <= ctx.usableBottom - ctx.topY && ctx.y + boxH > ctx.usableBottom) ctx.newPage()
            }
            if (ctx.y > ctx.topY && ctx.roomLeft() < MIN_BOX_OPEN_ROOM_PT) ctx.newPage()
            val frame = BoxFrame(x, availW, pad, node.borderPt, node.borderColor, node.background, node.cornerRadiusPt, ctx.depth, ctx.y)
            ctx.openBox(frame)
            flowNode(node.child, x + pad, (availW - 2 * pad).coerceAtLeast(0), ctx, cfg)
            ctx.closeBox(frame)
        }
        is TableNode -> flowTable(node, x, availW, ctx, cfg)
        is RowNode, is ImageNode -> {
            // Atomic — never split; move whole to the next page if it doesn't fit.
            val p = measure(node, availW, ctx.book)
            if (ctx.y + p.heightPt > ctx.usableBottom && ctx.y > ctx.topY) ctx.newPage()
            p.place(x, ctx.y, ctx.page.ops)
            ctx.y += p.heightPt
        }
    }
}

private fun flowText(node: TextNode, x: Int, availW: Int, ctx: Flow, cfg: PageConfig) {
    val weight = node.style.fontWeight
    val fs = node.style.fontSize.value
    val lineH = (fs * node.style.lineHeightMultiple).toInt()
    val ascent = ctx.book.ascentPt(weight, fs)
    val lines = LineBreaker.wrap(node.text, weight, fs, availW, ctx.book)
    // Keep-together: a paragraph that fits on a page is moved whole rather than leaving a sliver.
    val totalH = lines.size * lineH
    if (cfg.keepBlocksTogether && ctx.y > ctx.topY && ctx.y + totalH > ctx.usableBottom && totalH <= ctx.usableBottom - ctx.topY) {
        ctx.newPage()
    }
    for (line in lines) {
        if (ctx.y + lineH > ctx.usableBottom && ctx.y > ctx.topY) ctx.newPage()
        if (line.isNotEmpty()) {
            val gids = ctx.book.shape(line, weight)
            val lw = ctx.book.widthOfPt(gids, weight, fs)
            val lx = when (node.style.align) {
                TextAlign.Start -> x
                TextAlign.Center -> x + (availW - lw) / 2
                TextAlign.End -> x + (availW - lw)
            }
            ctx.page.ops.add(TextOp(lx, ctx.y + ascent, gids, weight, fs, node.style.color))
        }
        ctx.y += lineH
    }
}

private fun flowTable(node: TableNode, x: Int, availW: Int, ctx: Flow, cfg: PageConfig) {
    val colWidths = tableColumnWidths(node.columns, availW)
    val headerCells = node.columns.map { it.header }
    val aligns = node.columns.map { it.align }
    val headerH = tableRowHeight(headerCells, node.headerStyle, colWidths, node.cellPadHPt, node.cellPadVPt, ctx.book)
    fun placeHeader() {
        if (ctx.y + headerH > ctx.usableBottom && ctx.y > ctx.topY) ctx.newPage()
        drawTableRow(headerCells, node.headerStyle, aligns, x, ctx.y, headerH, colWidths, node.cellPadHPt, node.cellPadVPt, node.headerBackground, node.gridColor, ctx.book, ctx.page.ops)
        ctx.y += headerH
    }
    placeHeader()
    for (row in node.rows) {
        val rh = tableRowHeight(row.cells, row.style, colWidths, node.cellPadHPt, node.cellPadVPt, ctx.book)
        if (ctx.y + rh > ctx.usableBottom && ctx.y > ctx.topY) {
            ctx.newPage()
            if (node.repeatHeader) placeHeader()
        }
        drawTableRow(row.cells, row.style, aligns, x, ctx.y, rh, colWidths, node.cellPadHPt, node.cellPadVPt, row.background, node.gridColor, ctx.book, ctx.page.ops)
        ctx.y += rh
    }
}

private fun addPageNumbers(pages: List<Page>, book: TextMetrics, cfg: PageConfig, topYForNumber: Int) {
    val style = cfg.pageNumberStyle
    val fs = style.fontSize.value
    val weight = style.fontWeight
    val m = cfg.margin.value
    val ascent = book.ascentPt(weight, fs)
    val total = pages.size
    for ((i, p) in pages.withIndex()) {
        val gids = book.shape(cfg.pageNumberFormat(i + 1, total), weight)
        val tw = book.widthOfPt(gids, weight, fs)
        val contentW = p.widthPt - 2 * m
        val x = when (style.align) {
            TextAlign.Start -> m
            TextAlign.Center -> m + (contentW - tw) / 2
            TextAlign.End -> m + (contentW - tw)
        }
        p.ops.add(TextOp(x, topYForNumber + ascent, gids, weight, fs, style.color))
    }
}
