package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
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
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.serializePdf
import io.github.rikoappdev.composepdf.pdf.serializePdfTo
import kotlin.jvm.JvmName
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

enum class PageSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

/**
 * Page margins, one per side, set once for the whole document through [PageConfig.margins] — shaped
 * like Compose `PaddingValues`. Every parameter defaults to the more general one: a side falls back to
 * [horizontal] or [vertical], and those fall back to [all]:
 *
 * ```
 * PageMargins(36.dp)                                 // 36 pt on every side
 * PageMargins(all = 36.dp, bottom = 14.6.dp)         // only the bottom differs
 * PageMargins(horizontal = 40.dp, vertical = 28.dp)
 * PageMargins(start = 30.dp, top = 24.dp, end = 30.dp, bottom = 20.dp)
 * ```
 *
 * [start] is the left margin and [end] the right one (PDF pages have no layout direction). The
 * header's top edge rests on [top]; the footer's bottom edge — or the page-number line, when
 * `PageConfig.pageNumbers` is on — rests on [bottom]. The body takes what is left on each page: the
 * page minus the margins, minus the header and footer as measured on that page, minus their gaps. No
 * side may be negative.
 *
 * Two margins are equal when every side is the same to 0.001 pt, the precision the layout honours.
 */
class PageMargins private constructor(
    /** Left margin. */
    val start: Dp,
    val top: Dp,
    /** Right margin. */
    val end: Dp,
    val bottom: Dp,
    validate: Boolean,
) {
    constructor(
        all: Dp = 36.dp,
        horizontal: Dp = all,
        vertical: Dp = all,
        start: Dp = horizontal,
        top: Dp = vertical,
        end: Dp = horizontal,
        bottom: Dp = vertical,
    ) : this(start, top, end, bottom, validate = true)

    init {
        if (validate) {
            require(start.value >= 0f && top.value >= 0f && end.value >= 0f && bottom.value >= 0f) {
                "Page margins must not be negative, were $this"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PageMargins && start.mptKey() == other.start.mptKey() && top.mptKey() == other.top.mptKey() &&
            end.mptKey() == other.end.mptKey() && bottom.mptKey() == other.bottom.mptKey()

    override fun hashCode(): Int =
        ((start.mptKey().hashCode() * 31 + top.mptKey().hashCode()) * 31 + end.mptKey().hashCode()) * 31 + bottom.mptKey().hashCode()

    override fun toString(): String =
        "PageMargins(start=${start.text()}, top=${top.text()}, end=${end.text()}, bottom=${bottom.text()})"

    internal companion object {
        /**
         * The default `PageConfig.margins`: the `margin` shorthand on every side, **not** validated —
         * a negative `margin` was accepted (and laid out) before per-side margins existed, so old
         * documents keep rendering exactly as they did.
         */
        fun allSides(margin: Dp): PageMargins = PageMargins(margin, margin, margin, margin, validate = false)
    }
}

/** This length as the layout rounds it, in whole millipoints (`-0f` and `0f` agree); never throws. */
private fun Dp.mptKey(): Long = (floor(value.toDouble() * MPT_PER_PT + 0.5) + 0.0).toBits()

/** `36.dp`, `14.6.dp`: a whole value without the trailing `.0`. */
private fun Dp.text(): String =
    (if (value.isFinite() && value == truncate(value) && abs(value) < 1e15f) value.toLong().toString() else value.toString()) + ".dp"

class PageConfig(
    val size: PageSize = PageSize.A4,
    /** Shorthand for the same margin on all four sides: the default [margins] puts it on every side,
     *  like `PageMargins(all = margin)` (though a negative value is not rejected here, as in earlier
     *  releases). When [margins] is passed explicitly, it wins and this is unused. */
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
     * When true (default), a paragraph, a box or a split row (`row(split = true)`) that fits within a
     * single page is moved **whole** to the next page instead of being split when it doesn't fit the
     * remaining space — so a block never leaves an awkward sliver at the bottom of a page. Blocks
     * taller than a full page still split and flow across pages. A box or a split row can override
     * this setting with its own `keepTogether` (`null` follows it).
     *
     * Rows that are not split, images and vectors are atomic: they move whole to the next page when
     * they don't fit, whatever this says. A non-split row that would still cross the content bottom
     * from there (one taller than the content area) is split instead (see [ContainerScope.row]).
     * Tables split row by row.
     */
    val keepBlocksTogether: Boolean = true,
    /** Per-side page margins (see [PageMargins]); defaults to [margin] on every side. */
    val margins: PageMargins = PageMargins.allSides(margin),
)

/** Compose-style container: stacks children vertically. Shared by the document root, columns,
 *  box contents and row cells, so authoring reads like nested Compose. */
open class ContainerScope internal constructor(internal val registry: ImageRegistry) {
    internal val nodes = ArrayList<Node>()

    fun text(text: String, style: TextStyle = TextStyle()) { nodes.add(TextNode(text, style)) }
    fun spacer(height: Dp) { nodes.add(SpacerNode(0, height.mpt)) }
    fun divider(color: PdfColor = Color(0xFFDDDDDD), thickness: Dp = 1.dp) {
        nodes.add(DividerNode(color, thickness.mpt))
    }

    fun column(gap: Dp = 0.dp, build: ContainerScope.() -> Unit) {
        nodes.add(ColumnNode(ContainerScope(registry).apply(build).nodes, gap.mpt))
    }

    /**
     * A horizontal row of weighted [cell]s. [gap] is inserted between cells; [verticalAlignment]
     * places each cell within the row's height (the tallest cell) — handy to vertically centre a
     * short logo next to a taller text block.
     *
     * A row is atomic by default: when it does not fit the room left on the page it moves whole to
     * the next one. If it would still cross the content bottom from there (a row taller than the
     * content area), it is split instead, exactly like a split row that does not fit (below), so it
     * does not run into the footer or off the page; [keyValue] and [photoGrid] rows included. The one
     * exception: if laying the document out that way fails a layout check (page-aware bands leave a
     * page no room, or the page count does not settle), the document is laid out as in earlier
     * releases, with the row drawn past the bottom, rather than failing. A row that fits after moving
     * is unaffected. The safety net first moves the row, as earlier releases did, so a tall non-split
     * row that is the first thing in a padded box can leave the box's first fragment empty before it
     * splits; [split] avoids that, because it starts splitting at the pen. To let a row split where it
     * stands instead of moving first, use [split].
     *
     * With [split] = `true`, a row that does not fit lets its cells continue across
     * pages instead: the cells start side by side at the row's top and each flows on its own, splitting
     * as finely as top-level content does — text by lines, boxes into fragments (border and background
     * redrawn on each page), tables row by row (header repeated when the table's `repeatHeader` is
     * true, the default), nested split rows in turn. Single lines, table rows, images and non-split
     * rows inside a cell stay whole (an image taller than the page is shrunk to it, a non-split row
     * taller than the page splits as above). On every new page all cells resume at the same height,
     * below the padding (and continuation headers) of the boxes around the row, and the row ends below
     * its longest cell. Spacing in a cell (a spacer, a column gap) that runs past the page bottom
     * is dropped at the break instead of being carried to the next page, so no cell is continued on a
     * page just for its spacing. A row that splits is laid out top-aligned whatever
     * [verticalAlignment] says (centring and bottom alignment cannot hold across pages); a split row
     * that fits is placed exactly like a non-split one, aligned as asked.
     *
     * [keepTogether] applies to split rows only (a non-split row that does not fit moves, as above):
     * `null` follows [PageConfig.keepBlocksTogether], `true` moves the row whole to the next page when
     * it fits an empty page but not the room left, and `false` starts it at the current position. A
     * split row that does not fit is never started below the top of a page with less than 28 pt of
     * room left: it starts on the next page instead. [keepTogether] applies to the row as a whole;
     * blocks inside a cell keep their own rules, so a paragraph or box that fits a page may still move
     * to the next page under [PageConfig.keepBlocksTogether] or its own `keepTogether`, which can
     * leave that cell empty on the row's first page.
     */
    fun row(
        gap: Dp = 0.dp,
        verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
        split: Boolean = false,
        keepTogether: Boolean? = null,
        build: RowScope.() -> Unit,
    ) {
        nodes.add(RowScope(registry).apply(build).toNode(gap.mpt, verticalAlignment, split, keepTogether))
    }

    /** Binary-compatible entry point of the previous release; use the overload with `split`. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun row(gap: Dp = 0.dp, verticalAlignment: VerticalAlignment = VerticalAlignment.Top, build: RowScope.() -> Unit) =
        row(gap, verticalAlignment, false, null, build)

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

    /**
     * A container with optional padding, border, background and rounded corners. It splits across
     * pages (border and background are redrawn on each fragment) unless it fits an empty page but
     * not the room left, in which case it moves whole to the next page (keep-together, on by
     * default; see [keepTogether]).
     *
     * [padding] insets the content on every side; [paddingHorizontal] (left and right) and
     * [paddingVertical] (top and bottom) override it per axis. [keepTogether] overrides
     * [PageConfig.keepBlocksTogether] for this box: `null` follows the document setting, `true`
     * moves the box whole to the next page when it fits an empty page but not the room left, `false`
     * starts it at the current position and lets it split. A box is never opened with less than
     * 28 pt of room left, whatever [keepTogether] says.
     *
     * [continuationHeader], when set, is drawn at the top of every continuation fragment of the box,
     * that is on each page after the first one it spans (never on the first): inside the padding, at
     * the box's inner width, placed whole like a band, and the fragment's content starts below it.
     * On a page where it does not fit between the fragment's top padding and the page bottom it is
     * skipped, so it never runs into the footer. A header that fits but leaves less room than one
     * line of content still takes its place, and that content may then run past the content bottom
     * on continuation pages, so keep headers short. A block that adds nothing is the same as no
     * header. Keep-together decides on the box's own height, so when it applies a box that fits an
     * empty page moves whole and never shows its header. While such a box is open, a spacer or a
     * column gap that runs past the page bottom is dropped at the break instead of starting a new
     * page, so no continuation fragment holds only the header and spacing: the next content, if
     * any, starts the next page as usual.
     */
    fun box(
        padding: Dp = 0.dp,
        border: Dp = 0.dp,
        borderColor: PdfColor = Color(0xFF6C757D),
        background: PdfColor? = null,
        cornerRadius: Dp = 0.dp,
        paddingHorizontal: Dp = padding,
        paddingVertical: Dp = padding,
        keepTogether: Boolean? = null,
        continuationHeader: (ContainerScope.() -> Unit)? = null,
        build: ContainerScope.() -> Unit,
    ) {
        nodes.add(
            BoxNode(
                child = ColumnNode(ContainerScope(registry).apply(build).nodes, 0),
                padHMpt = paddingHorizontal.mpt,
                padVMpt = paddingVertical.mpt,
                borderMpt = border.mpt,
                borderColor = borderColor,
                background = background,
                cornerRadiusMpt = cornerRadius.mpt,
                keepTogether = keepTogether,
                // A header block that adds nothing is no header: it neither draws nor changes the flow.
                continuationHeader = continuationHeader
                    ?.let { ContainerScope(registry).apply(it).nodes }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ColumnNode(it, 0) },
            )
        )
    }

    /** Binary-compatible entry point of the previous release; use the overload with `paddingHorizontal`. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    fun box(
        padding: Dp = 0.dp,
        border: Dp = 0.dp,
        borderColor: PdfColor = Color(0xFF6C757D),
        background: PdfColor? = null,
        cornerRadius: Dp = 0.dp,
        build: ContainerScope.() -> Unit,
    ) = box(padding, border, borderColor, background, cornerRadius, padding, padding, null, null, build)

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
        nodes.add(ImageNode(img.index, width.mpt, height.mpt, img.intrinsicW, img.intrinsicH, fit, align))
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
        nodes.add(VectorNode(form.index, width.mpt, height.mpt, form.intrinsicW, form.intrinsicH, fit, align))
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

    /**
     * A table with weighted columns, a repeating header, body rows and bold total rows.
     *
     * Wherever it is flowed — at the document top level, in a [column], a [box] or a cell of a split
     * [row] — it splits across pages row by row, repeating the header on each new page when
     * [repeatHeader] is true. Inside a row that is not split, a header / footer band or a box's
     * continuation header it is measured and placed whole, as part of that block — except in a
     * non-split row that is split because it would cross the content bottom (see [row]), whose cells
     * flow like a split row's.
     */
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
                cellPadHMpt = cellPaddingHorizontal.mpt,
                cellPadVMpt = cellPaddingVertical.mpt,
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

    internal fun toNode(gapMpt: Int, valign: VerticalAlignment, split: Boolean, keepTogether: Boolean?) =
        RowNode(cells.toList(), gapMpt, valign, split, keepTogether)
}

class PdfContentScope internal constructor(registry: ImageRegistry) : ContainerScope(registry) {
    internal var headerBand: Band? = null
    internal var footerBand: Band? = null

    private fun buildBand(gap: Dp, build: BandScope.() -> Unit): Band {
        require(gap.value >= 0f) { "A header/footer gap must not be negative, was ${gap.value}.dp" }
        // The probe's images land in the document; recording them lets a page-aware band's render
        // reuse them unread — including a logo the other, static band already embeds.
        val probe = RecordingImageRegistry(registry)
        val scope = BandScope(probe, PageInfo(1, 1)).apply(build)
        return if (scope.pageRead) {
            PageAwareBand(build, gap.mpt, probe.known())
        } else {
            StaticBand(scope.nodes, gap.mpt, probe.known())
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
     * Renders to PDF bytes using the supplied Regular + Bold TrueType fonts (and, optionally, a Medium
     * face).
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
     * [mediumFontBytes], when supplied, is the face for [FontWeight.Medium] text. Without it, Medium
     * text is set in the Regular face (as CSS font matching does for weight 500), so it renders exactly
     * like [FontWeight.Normal] text; documents without Medium text are unaffected either way.
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
        mediumFontBytes: ByteArray? = null,
    ): ByteArray {
        // Emoji bitmaps discovered during layout are appended to the render's table after the
        // document's own images, so every index the layout emits points into result.images.
        val table = RenderImageTable(images)
        val book = FontBook(regularFontBytes, boldFontBytes, emojiFontBytes, imageTable = table, mediumBytes = mediumFontBytes)
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
     * Font (including [mediumFontBytes]), emoji and [onProgress] semantics are exactly those of [render].
     */
    fun renderTo(
        sink: PdfSink,
        regularFontBytes: ByteArray,
        boldFontBytes: ByteArray,
        emojiFontBytes: ByteArray? = null,
        onProgress: ((Float) -> Unit)? = null,
        mediumFontBytes: ByteArray? = null,
    ) {
        val table = RenderImageTable(images)
        val book = FontBook(regularFontBytes, boldFontBytes, emojiFontBytes, imageTable = table, mediumBytes = mediumFontBytes)
        val result = layoutDocument(this, book, table)
        onProgress?.invoke(0f)
        serializePdfTo(sink, result.pages, book, result.images, onProgress)
    }
}
