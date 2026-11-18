package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.layout.Node

/**
 * The page a header/footer band is being built for. Read it through [BandScope.page].
 *
 * [number] is 1-based and always satisfies `1 <= number <= total`. While pagination settles, [total]
 * may be a provisional value; only the final pass is drawn.
 */
class PageInfo internal constructor(
    /** 1-based number of this page. */
    val number: Int,
    /** Total number of pages in the document. */
    val total: Int,
) {
    /** `true` on the first page. */
    val isFirst: Boolean get() = number == 1

    /** `true` on the last page. */
    val isLast: Boolean get() = number == total

    override fun toString(): String = "PageInfo(number=$number, total=$total)"
}

/**
 * Receiver of a [PdfContentScope.header] / [PdfContentScope.footer] block: everything a
 * [ContainerScope] offers, plus [page].
 */
class BandScope internal constructor(
    registry: ImageRegistry,
    private val info: PageInfo,
) : ContainerScope(registry) {
    internal var pageRead = false

    /**
     * The page this band is being built for. Reading it (also from a nested `row { cell { … } }`)
     * makes the band page-aware: it is rebuilt for every page once the page total is known, so it can
     * show "Page 3 / 12" or differ between the first page and the rest.
     *
     * A page-aware band's block may run several times (once when the document is declared and again
     * for each page on every render), and with provisional totals while pagination settles, so keep it
     * free of side effects. It runs during render. Set `PageConfig.pageNumbers = false` when you draw
     * page numbers yourself.
     *
     * Reusable helpers: write them as `fun ContainerScope.pageLabel(page: PageInfo) { … }` and call
     * `pageLabel(page)`, so they work at any depth. A `BandScope` extension (`fun BandScope.pageLabel()`)
     * called inside a nested `cell { }` / `column { }` / `box { }` resolves against the band itself,
     * not the nested scope, so its content lands in the band below the row instead of in the cell —
     * call such an extension only at the top level of the `header { }` / `footer { }` block.
     */
    val page: PageInfo
        get() {
            pageRead = true
            return info
        }
}

/**
 * A repeating header/footer band and the vertical gap between it and the content area. [probed] are
 * the images its build-time probe registered in the document (by the caller's handle only, no bytes),
 * so a page-aware band's render recognises those instances unread and reuses their image objects.
 */
internal sealed interface Band {
    val gapMpt: Int
    val probed: KnownImages
}

/** A band that never reads [BandScope.page]: built once, identical on every page. */
internal class StaticBand(
    val nodes: List<Node>,
    override val gapMpt: Int,
    override val probed: KnownImages,
) : Band

/** A band that reads [BandScope.page]: rebuilt per page at layout time. */
internal class PageAwareBand(
    val build: BandScope.() -> Unit,
    override val gapMpt: Int,
    override val probed: KnownImages,
) : Band
