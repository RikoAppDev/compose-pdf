package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
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
import io.github.rikoappdev.composepdf.render.TextOp
import io.github.rikoappdev.composepdf.text.LineBreaker

enum class PageSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

class PageConfig(
    val size: PageSize = PageSize.A4,
    val margin: Dp = 36.dp,
    val pageNumbers: Boolean = true,
    val pageNumberStyle: TextStyle = TextStyle(fontSize = 8.sp, color = Color(0xFF7F807F), align = TextAlign.Center),
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

    fun box(
        padding: Dp = 0.dp,
        border: Dp = 0.dp,
        borderColor: PdfColor = Color(0xFF6C757D),
        background: PdfColor? = null,
        build: ContainerScope.() -> Unit,
    ) {
        nodes.add(
            BoxNode(
                child = ColumnNode(ContainerScope(images).apply(build).nodes, 0),
                paddingPt = padding.value,
                borderPt = border.value,
                borderColor = borderColor,
                background = background,
            )
        )
    }

    /** Embeds a JPEG. [width] = 0.dp fills the available width. [cover] crops to fill, else fits. */
    fun image(jpegBytes: ByteArray, width: Dp = 0.dp, height: Dp, cover: Boolean = true) {
        val info = parseJpeg(jpegBytes)
        val index = images.size
        images.add(JpegImage(info.width, info.height, info.components, jpegBytes))
        nodes.add(ImageNode(index, width.value, height.value, info.width, info.height, cover))
    }

    /** Lays out [photos] (JPEG bytes) in a grid of [perRow] equal cells, each [cellHeight] tall. */
    fun photoGrid(photos: List<ByteArray>, perRow: Int = 3, cellHeight: Dp, gap: Dp = 6.dp) {
        photos.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            if (rowIndex > 0) spacer(gap)
            row(gap) {
                chunk.forEach { bytes -> cell(1f) { image(bytes, width = 0.dp, height = cellHeight, cover = true) } }
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
        gridColor: PdfColor = Color(0xFFDDDDDD),
        cellPadding: Dp = 4.dp,
        repeatHeader: Boolean = true,
        build: TableScope.() -> Unit,
    ) {
        val scope = TableScope(cellStyle, totalStyle, totalBackground).apply(build)
        nodes.add(
            TableNode(
                columns = columns.map { TableColumn(it.weight, it.header, it.align) },
                rows = scope.rows,
                headerStyle = headerStyle,
                headerBackground = headerBackground,
                cellPadHPt = cellPadding.value,
                cellPadVPt = cellPadding.value,
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
) {
    internal val rows = ArrayList<TableRow>()
    fun row(vararg cells: String) { rows.add(TableRow(cells.toList(), cellStyle, null)) }
    fun row(cells: List<String>) { rows.add(TableRow(cells, cellStyle, null)) }
    fun totalRow(vararg cells: String) { rows.add(TableRow(cells.toList(), totalStyle, totalBackground)) }
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
    /** Renders to PDF bytes using the supplied Regular + Bold TrueType fonts. */
    fun render(regularFontBytes: ByteArray, boldFontBytes: ByteArray): ByteArray {
        val book = FontBook(regularFontBytes, boldFontBytes)
        return serializePdf(layout(this, book), book, images)
    }
}

private const val BAND_GAP_PT = 12

private fun layout(spec: PdfDocumentSpec, book: FontBook): List<Page> {
    val cfg = spec.config
    val w = cfg.size.widthPt
    val h = cfg.size.heightPt
    val m = cfg.margin.value
    val contentW = w - 2 * m

    // Repeating bands reserve space at the top/bottom; the flow lives between them.
    val headerP = spec.headerNodes?.let { measure(ColumnNode(it, 0), contentW, book) }
    val footerP = spec.footerNodes?.let { measure(ColumnNode(it, 0), contentW, book) }
    val topY = m + (headerP?.let { it.heightPt + BAND_GAP_PT } ?: 0)
    val usableBottom = h - m - (footerP?.let { it.heightPt + BAND_GAP_PT } ?: 0)

    val pages = ArrayList<Page>()
    var page = Page(w, h).also { pages.add(it) }
    var y = topY
    fun newPage() { page = Page(w, h).also { pages.add(it) }; y = topY }

    for (node in spec.nodes) when (node) {
        is SpacerNode -> {
            y += node.heightPt
            if (y > usableBottom) newPage()
        }
        is TextNode -> {
            // Top-level paragraphs split line-by-line across pages.
            val weight = node.style.fontWeight
            val fs = node.style.fontSize.value
            val lineH = (fs * node.style.lineHeightMultiple).toInt()
            val ascent = book.ascentPt(weight, fs)
            for (line in LineBreaker.wrap(node.text, weight, fs, contentW, book)) {
                if (y + lineH > usableBottom && y > topY) newPage()
                if (line.isNotEmpty()) {
                    val gids = book.shape(line, weight)
                    val lw = book.widthOfPt(gids, weight, fs)
                    val x = when (node.style.align) {
                        TextAlign.Start -> m
                        TextAlign.Center -> m + (contentW - lw) / 2
                        TextAlign.End -> m + (contentW - lw)
                    }
                    page.ops.add(TextOp(x, y + ascent, gids, weight, fs, node.style.color))
                }
                y += lineH
            }
        }
        is TableNode -> {
            // Top-level tables split across pages by row, repeating the header.
            val colWidths = tableColumnWidths(node.columns, contentW)
            val headerCells = node.columns.map { it.header }
            val aligns = node.columns.map { it.align }
            val headerH = tableRowHeight(headerCells, node.headerStyle, colWidths, node.cellPadHPt, node.cellPadVPt, book)
            fun placeHeader() {
                if (y + headerH > usableBottom && y > topY) newPage()
                drawTableRow(headerCells, node.headerStyle, aligns, m, y, headerH, colWidths, node.cellPadHPt, node.cellPadVPt, node.headerBackground, node.gridColor, book, page.ops)
                y += headerH
            }
            placeHeader()
            for (row in node.rows) {
                val rh = tableRowHeight(row.cells, row.style, colWidths, node.cellPadHPt, node.cellPadVPt, book)
                if (y + rh > usableBottom && y > topY) {
                    newPage()
                    if (node.repeatHeader) placeHeader()
                }
                drawTableRow(row.cells, row.style, aligns, m, y, rh, colWidths, node.cellPadHPt, node.cellPadVPt, row.background, node.gridColor, book, page.ops)
                y += rh
            }
        }
        else -> {
            // Containers/dividers/images place atomically; move to next page if they don't fit.
            val p = measure(node, contentW, book)
            if (y + p.heightPt > usableBottom && y > topY) newPage()
            p.place(m, y, page.ops)
            y += p.heightPt
        }
    }

    // Repeat the header/footer bands on every page (placers are reusable across pages).
    for (p in pages) {
        headerP?.place(m, m, p.ops)
        footerP?.place(m, h - m - footerP.heightPt, p.ops)
    }
    if (cfg.pageNumbers) addPageNumbers(pages, book, cfg)
    return pages
}

private fun addPageNumbers(pages: List<Page>, book: FontBook, cfg: PageConfig) {
    val style = cfg.pageNumberStyle
    val fs = style.fontSize.value
    val weight = style.fontWeight
    val m = cfg.margin.value
    val total = pages.size
    for ((i, p) in pages.withIndex()) {
        val gids = book.shape("${i + 1} / $total", weight)
        val tw = book.widthOfPt(gids, weight, fs)
        val contentW = p.widthPt - 2 * m
        val x = when (style.align) {
            TextAlign.Start -> m
            TextAlign.Center -> m + (contentW - tw) / 2
            TextAlign.End -> m + (contentW - tw)
        }
        p.ops.add(TextOp(x, p.heightPt - m + fs, gids, weight, fs, style.color))
    }
}
