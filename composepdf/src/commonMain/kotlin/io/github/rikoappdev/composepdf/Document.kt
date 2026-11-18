package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.font.TextMetrics
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
import io.github.rikoappdev.composepdf.layout.VectorNode
import io.github.rikoappdev.composepdf.layout.drawTableRow
import io.github.rikoappdev.composepdf.layout.measure
import io.github.rikoappdev.composepdf.layout.tableColumnWidths
import io.github.rikoappdev.composepdf.layout.tableRowHeight
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.serializePdf
import io.github.rikoappdev.composepdf.pdf.serializePdfTo
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.text.LineBreaker
import kotlin.jvm.JvmName

enum class PageSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

class PageConfig(
    val size: PageSize = PageSize.A4,
    val margin: Dp = 36.dp,
    /** Auto page-number line at the bottom of every page. Its vertical space is reserved so content
     *  never overlaps it. Combine freely with a custom [ContainerScope.footer] band (footer sits
     *  above the number). For custom placement (e.g. inside a footer row next to a logo) read
     *  `page.number` / `page.total` in a page-aware header/footer and set this to `false`. */
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
open class ContainerScope internal constructor(internal val registry: ImageRegistry) {
    internal val nodes = ArrayList<Node>()

    fun text(text: String, style: TextStyle = TextStyle()) { nodes.add(TextNode(text, style)) }
    fun spacer(height: Dp) { nodes.add(SpacerNode(0, height.value)) }
    fun divider(color: PdfColor = Color(0xFFDDDDDD), thickness: Dp = 1.dp) {
        nodes.add(DividerNode(color, thickness.value))
    }

    fun column(gap: Dp = 0.dp, build: ContainerScope.() -> Unit) {
        nodes.add(ColumnNode(ContainerScope(registry).apply(build).nodes, gap.value))
    }

    /**
     * A horizontal row of weighted [cell]s. [gap] is inserted between cells; [verticalAlignment]
     * places each cell within the row's height (the tallest cell) — handy to vertically centre a
     * short logo next to a taller text block.
     */
    fun row(gap: Dp = 0.dp, verticalAlignment: VerticalAlignment = VerticalAlignment.Top, build: RowScope.() -> Unit) {
        nodes.add(RowScope(registry).apply(build).toNode(gap.value, verticalAlignment))
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
                child = ColumnNode(ContainerScope(registry).apply(build).nodes, 0),
                paddingPt = padding.value,
                borderPt = border.value,
                borderColor = borderColor,
                background = background,
                cornerRadiusPt = cornerRadius.value,
            )
        )
    }

    /**
     * Embeds an image pulled from a [PdfImageSource] — a file, a content-provider stream, a cache
     * entry. This is the primary form: a document of any size stays bounded in memory because a
     * **JPEG** is read here only to learn its size, then dropped, and read back one photo at a time
     * while the file is written (pair it with [PdfDocumentSpec.renderTo] and neither the photos nor
     * the finished PDF ever exist as a whole). See [PdfImageSource] for the (strict) contract.
     *
     * The format is auto-detected from the magic number: JPEG (`FF D8 FF`) is embedded verbatim via
     * `/DCTDecode`; PNG (`89 50 4E 47 …`) is decoded to RGB pixels and embedded via `/FlateDecode`,
     * with transparency carried as a `/SMask`. **PNG is decoded eagerly here and the pixels are kept**
     * — unlike a pass-through JPEG, a PNG's `/Length` is its *deflated* size, which is only known by
     * compressing it, and the head carrying that `/Length` is written before the payload. PNGs are
     * logos and icons, not photo streams, so this costs a bounded, small amount.
     *
     * [width] = 0.dp fills the available width. [fit] controls cover/contain/smart. [align] places the
     * fitted image inside its box horizontally — [HorizontalAlignment.Center] (default) centers it;
     * [HorizontalAlignment.Start] left-aligns it (handy for a logo in a wide cell). The intrinsic size
     * comes from the JPEG SOF / PNG IHDR. Throws if the bytes are neither JPEG nor PNG, or an
     * unsupported PNG variant.
     */
    fun image(source: PdfImageSource, width: Dp = 0.dp, height: Dp, fit: PhotoFit = PhotoFit.Cover, align: HorizontalAlignment = HorizontalAlignment.Center) {
        addImage(source, source, width, height, fit, align)
    }

    /**
     * Convenience overload of [image] for bytes already in hand — identical output, and identical
     * machinery: [imageBytes] is wrapped in a source that returns it. Simple and fine for logos,
     * screenshots and small documents; for a report full of photos prefer the [PdfImageSource] form,
     * which lets the engine hold one photo at a time instead of all of them.
     */
    fun image(imageBytes: ByteArray, width: Dp = 0.dp, height: Dp, fit: PhotoFit = PhotoFit.Cover, align: HorizontalAlignment = HorizontalAlignment.Center) {
        addImage(PdfImageSource { imageBytes }, imageBytes, width, height, fit, align)
    }

    /** Shared body of the [image] overloads; [identity] is the caller's own handle (source or bytes). */
    private fun addImage(source: PdfImageSource, identity: Any, width: Dp, height: Dp, fit: PhotoFit, align: HorizontalAlignment) {
        val img = registry.image(source, identity)
        nodes.add(ImageNode(img.index, width.value, height.value, img.intrinsicW, img.intrinsicH, fit, align))
    }

    /**
     * Embeds a **vector** image — an Android VectorDrawable or an SVG (auto-detected from the XML
     * root) — as native PDF vector paths (crisp at any zoom, resolution-independent), compiled once
     * into a Form XObject. [width] = 0.dp fills the available width; [fit] controls how the vector's
     * viewport maps into the [width] × [height] box (default [PhotoFit.Contain] preserves aspect).
     * [align] places the fitted vector inside its box horizontally — [HorizontalAlignment.Center]
     * (default) centers it; [HorizontalAlignment.Start] left-aligns it (handy for a logo in a wide cell).
     * Supports paths (full M/L/H/V/C/S/Q/T/A/Z command set), basic SVG shapes, group transforms,
     * solid fills/strokes (nonzero & even-odd) and per-element opacity. Gradients, patterns, text and
     * filters are not supported. Throws if the bytes aren't a recognizable VectorDrawable/SVG.
     */
    fun vector(vectorBytes: ByteArray, width: Dp = 0.dp, height: Dp, fit: PhotoFit = PhotoFit.Contain, align: HorizontalAlignment = HorizontalAlignment.Center) {
        val form = registry.vector(vectorBytes)
        nodes.add(VectorNode(form.index, width.value, height.value, form.intrinsicW, form.intrinsicH, fit, align))
    }

    /**
     * Lays out [photos] in a grid of [perRow] equal cells, each [cellHeight] tall, pulling each photo
     * from its [PdfImageSource]. This is the form that scales: a thousand-photo log costs one photo of
     * memory at a time, not a thousand (see [image] and [PdfImageSource]).
     *
     * [fit] = [PhotoFit.Cover] crops each photo to fill its cell; [PhotoFit.Contain] fits the whole
     * photo preserving aspect; [PhotoFit.Smart] preserves aspect but crops extreme-aspect photos so
     * they don't become thin slivers.
     */
    @JvmName("photoGridFromSources")
    fun photoGrid(photos: List<PdfImageSource>, perRow: Int = 3, cellHeight: Dp, gap: Dp = 6.dp, fit: PhotoFit = PhotoFit.Cover) {
        addPhotoGrid(photos.map { source -> source to source }, perRow, cellHeight, gap, fit)
    }

    /**
     * Convenience overload of [photoGrid] for JPEG/PNG byte arrays already in hand — identical output,
     * same machinery (each array is wrapped in a source that returns it). Holding every photo at once
     * is what it costs; for a photo-heavy export pass [PdfImageSource]s instead.
     */
    fun photoGrid(photos: List<ByteArray>, perRow: Int = 3, cellHeight: Dp, gap: Dp = 6.dp, fit: PhotoFit = PhotoFit.Cover) {
        addPhotoGrid(photos.map { bytes -> PdfImageSource { bytes } to bytes }, perRow, cellHeight, gap, fit)
    }

    /** Shared body of the [photoGrid] overloads: each photo is a (source, identity) pair, as in [addImage]. */
    private fun addPhotoGrid(photos: List<Pair<PdfImageSource, Any>>, perRow: Int, cellHeight: Dp, gap: Dp, fit: PhotoFit) {
        require(perRow >= 1) { "photoGrid perRow must be >= 1" }
        photos.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            if (rowIndex > 0) spacer(gap)
            row(gap) {
                chunk.forEach { (source, identity) ->
                    cell(1f) { addImage(source, identity, width = 0.dp, height = cellHeight, fit = fit, align = HorizontalAlignment.Center) }
                }
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
class RowScope internal constructor(private val registry: ImageRegistry) {
    private val cells = ArrayList<RowChild>()

    /**
     * A column in the row. [weight] > 0 splits the remaining width proportionally (like
     * `Modifier.weight`). [weight] = 0 makes the cell **content-sized** — it shrinks to the intrinsic
     * width of its content, and the leftover width goes to the weighted cells. Pair a content-sized
     * cell with a `cell(1f) {}` spacer to push a logo / info block to the far left or right.
     */
    fun cell(weight: Float = 1f, build: ContainerScope.() -> Unit) {
        cells.add(RowChild(ColumnNode(ContainerScope(registry).apply(build).nodes, 0), weight))
    }

    internal fun toNode(gapPt: Int, valign: VerticalAlignment) = RowNode(cells.toList(), gapPt, valign)
}

class PdfContentScope internal constructor(registry: ImageRegistry) : ContainerScope(registry) {
    internal var headerBand: Band? = null
    internal var footerBand: Band? = null

    private fun buildBand(gap: Dp, build: BandScope.() -> Unit): Band {
        require(gap.value >= 0) { "A header/footer gap must not be negative, was ${gap.value}.dp" }
        // The probe's images land in the document; recording them lets a page-aware band's render
        // reuse them unread — including a logo the other, static band already embeds.
        val probe = RecordingImageRegistry(registry)
        val scope = BandScope(probe, PageInfo(1, 1)).apply(build)
        return if (scope.pageRead) {
            PageAwareBand(build, gap.value, probe.known())
        } else {
            StaticBand(scope.nodes, gap.value, probe.known())
        }
    }

    /**
     * A band repeated at the top of every page (e.g. a title or logo). [gap] (not negative) is the
     * vertical space between the band and the content below it.
     *
     * A block that never reads [BandScope.page] is built once and is identical on every page. A block
     * that reads it is page-aware: it runs again for each page, so it may run several times and with
     * provisional page totals while pagination settles (only the final pass is drawn). Keep it free
     * of side effects; it runs during render. Set `PageConfig.pageNumbers = false` when you render page
     * numbers yourself. A page-aware band that adds no content on a page takes no space there.
     * With `PageConfig.repeatHeader = false` the header appears on the first page only.
     */
    fun header(gap: Dp = 12.dp, build: BandScope.() -> Unit) { headerBand = buildBand(gap, build) }

    /**
     * A band repeated at the bottom of every page (above the page-number line). [gap] (not negative)
     * is the vertical space between the content and the band. See [header] for how page-aware
     * blocks behave; set `PageConfig.pageNumbers = false` when you render the page number inside the
     * footer.
     */
    fun footer(gap: Dp = 12.dp, build: BandScope.() -> Unit) { footerBand = buildBand(gap, build) }

    /** Binary-compatible entry point of the previous release; use the overload with `gap`. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun header(build: ContainerScope.() -> Unit) = header(12.dp, build)

    /** Binary-compatible entry point of the previous release; use the overload with `gap`. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun footer(build: ContainerScope.() -> Unit) = footer(12.dp, build)
}

fun pdfDocument(config: PageConfig = PageConfig(), build: PdfContentScope.() -> Unit): PdfDocumentSpec {
    val images = ArrayList<EmbeddedImage>()
    val scope = PdfContentScope(DocumentImageRegistry(images)).apply(build)
    return PdfDocumentSpec(config, scope.nodes, images, scope.headerBand, scope.footerBand)
}

class PdfDocumentSpec internal constructor(
    internal val config: PageConfig,
    internal val nodes: List<Node>,
    internal val images: List<EmbeddedImage>,
    internal val header: Band?,
    internal val footer: Band?,
) {
    /**
     * Renders to PDF bytes using the supplied Regular + Bold TrueType fonts.
     *
     * The result is one contiguous array, so peak memory includes the whole file. For a photo-heavy
     * export prefer [renderTo], which writes the identical bytes straight to a destination — and pair
     * it with [PdfImageSource] photos so they are pulled one at a time as well.
     *
     * Fonts are supplied by the caller (the application), not bundled in the library: the app picks
     * a default and can let the user choose another, then passes the chosen face bytes here. This
     * keeps the engine font-agnostic and dependency-free, and works identically on every platform
     * (the app reads its own bundled `.ttf` via its resource mechanism). Only the glyphs a document
     * uses are subset and embedded.
     *
     * [emojiFontBytes], when supplied, is an Apple `sbix` or Noto `CBLC`/`CBDT` **color-emoji** font:
     * code points the Regular/Bold faces can't render but the emoji face can are drawn inline as color
     * bitmap images (the "phone" emoji look) instead of `.notdef` boxes. Single–code-point emoji are
     * supported; ZWJ sequences and skin-tone modifiers render their base emoji (the joiner/modifier is
     * dropped), and color **vector** fonts (`COLR`/`CPAL`, e.g. Segoe UI Emoji) are not used. Passing
     * `null` keeps output byte-for-byte identical to the no-emoji path.
     *
     * [onProgress], when supplied, is invoked with a monotonically increasing fraction in `0f..1f`:
     * `0f` once pagination is done (the total page count is known), then `(page + 1) / total` (scaled
     * to leave a small tail) as each page's content is serialized, and exactly `1f` once the final
     * byte array is assembled. It is purely observational — the produced bytes are byte-for-byte
     * identical whether or not a callback is passed.
     */
    fun render(
        regularFontBytes: ByteArray,
        boldFontBytes: ByteArray,
        emojiFontBytes: ByteArray? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray {
        // Emoji bitmaps discovered during layout are appended to the render's table after the
        // document's own images, so every index the layout emits points into result.images.
        val table = RenderImageTable(images)
        val book = FontBook(regularFontBytes, boldFontBytes, emojiFontBytes, imageTable = table)
        val result = layoutDocument(this, book, table)
        onProgress?.invoke(0f)
        return serializePdf(result.pages, book, result.images, onProgress)
    }

    /**
     * Streaming twin of [render]: writes the **byte-for-byte identical** PDF into [sink] instead of
     * returning one array, so the document is never assembled into a single contiguous file-sized
     * allocation.
     *
     * Together with [PdfImageSource] this makes document size stop mattering. Two things scale with a
     * photo report — the photos and the finished file — and each has its own answer:
     *  - photos supplied as [PdfImageSource]s are pulled one at a time while their object is written
     *    and released immediately, so the *photos* never exist all at once;
     *  - streaming means the *file* never exists all at once.
     *
     * Use both and peak memory is a small constant: measured at ~3.5 MiB above baseline for an
     * 870-photo, 397 MB report, which streams in a 64 MiB heap. Photos supplied as `ByteArray`s still
     * cost what the caller holds — the engine references those arrays rather than copying them, but it
     * cannot release what the caller owns. The non-image objects (content streams, fonts) are built in
     * memory either way; they are a fraction of a percent of a photo document.
     *
     * If [sink] throws, the exception propagates out of this call and whatever bytes were already
     * written remain at the destination — deleting the partial output is the caller's job. The same
     * applies to a throwing [PdfImageSource].
     *
     * Font, emoji and [onProgress] semantics are exactly those of [render].
     */
    fun renderTo(
        sink: PdfSink,
        regularFontBytes: ByteArray,
        boldFontBytes: ByteArray,
        emojiFontBytes: ByteArray? = null,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val table = RenderImageTable(images)
        val book = FontBook(regularFontBytes, boldFontBytes, emojiFontBytes, imageTable = table)
        val result = layoutDocument(this, book, table)
        onProgress?.invoke(0f)
        serializePdfTo(sink, result.pages, book, result.images, onProgress)
    }
}

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
 * Each page's content area comes from [frameFor], asked once per page as the page opens, so bands
 * may reserve a different height on every page. All integer math → identical placement on every
 * platform.
 */
private class Flow(
    val w: Int,
    val h: Int,
    private val frameFor: (pageIndex: Int) -> PageFrame,
    val book: TextMetrics,
) {
    val pages = ArrayList<Page>()
    var page = Page(w, h).also { pages.add(it) }
    private var frame = frameFor(0)
    val topY get() = frame.topY                 // content top on the current page
    val usableBottom get() = frame.usableBottom // content bottom on the current page
    var y = topY
    private val openBoxes = ArrayList<BoxFrame>()
    private val backgrounds = ArrayList<Triple<Int, Int, RectOp>>() // (pageIndex, depth, rect)

    val depth get() = openBoxes.size
    fun roomLeft() = usableBottom - y

    fun newPage() {
        // Open box fragments close at the bottom of the page being left, before its frame changes.
        for (b in openBoxes) emitFragment(b, b.fragTop, usableBottom)
        page = Page(w, h).also { pages.add(it) }
        frame = frameFor(pages.lastIndex)
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

/**
 * One flow pass: lays [spec]'s body out into pages whose content areas come from [frameFor] (asked
 * once per page, in page order, as each page opens). Bands are not drawn here — see [layoutDocument].
 */
internal fun flowBody(spec: PdfDocumentSpec, book: TextMetrics, frameFor: (pageIndex: Int) -> PageFrame): List<Page> {
    val cfg = spec.config
    val m = cfg.margin.value
    val ctx = Flow(cfg.size.widthPt, cfg.size.heightPt, frameFor, book)
    for (node in spec.nodes) flowNode(node, m, cfg.size.widthPt - 2 * m, ctx, cfg)
    return ctx.finish()
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
        is RowNode, is ImageNode, is VectorNode -> {
            // Atomic — never split; move whole to the next page if it doesn't fit.
            val roomBefore = ctx.usableBottom - ctx.topY
            val natural = measure(node, availW, ctx.book)
            if (ctx.y + natural.heightPt > ctx.usableBottom && ctx.y > ctx.topY) ctx.newPage()
            // An image/vector taller than the content area can never fit; clamp it so it doesn't draw
            // past the page bottom into the footer. The page it landed on may have less room than the
            // one it left (page-aware bands), hence the smaller of the two — without page-aware bands
            // the next page never has less, so this is the old clamp. Valid documents use heights far
            // smaller than a page, so this never triggers for them. A row cannot shrink: one taller
            // than the content area overflows that page's bottom, as it always has.
            val room = minOf(roomBefore, ctx.usableBottom - ctx.topY)
            val p = when {
                node is ImageNode && node.heightPt > room -> measure(node.withHeight(room), availW, ctx.book)
                node is VectorNode && node.heightPt > room -> measure(node.withHeight(room), availW, ctx.book)
                else -> natural
            }
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
            val lw = ctx.book.measureWidthPt(line, weight, fs)
            val lx = when (node.style.align) {
                TextAlign.Start -> x
                TextAlign.Center -> x + (availW - lw) / 2
                TextAlign.End -> x + (availW - lw)
            }
            ctx.book.placeLine(line, weight, fs, node.style.color, lx, ctx.y + ascent, ctx.page.ops)
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
