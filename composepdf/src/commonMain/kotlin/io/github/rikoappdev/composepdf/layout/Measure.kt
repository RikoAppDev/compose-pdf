package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.MPT_PER_PT
import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.VerticalAlignment
import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.halfTrunc
import io.github.rikoappdev.composepdf.lineHeightMpt
import io.github.rikoappdev.composepdf.mpt
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.satAdd
import io.github.rikoappdev.composepdf.satMul
import io.github.rikoappdev.composepdf.saturatedToInt
import io.github.rikoappdev.composepdf.text.LineBreaker
import io.github.rikoappdev.composepdf.wholeTrunc

/** A measured node: its size + a placer that emits absolute draw ops at a given top-left (x,y). Millipoints. */
internal class Placeable(
    val widthMpt: Int,
    val heightMpt: Int,
    val place: (x: Int, y: Int, out: MutableList<DrawOp>) -> Unit,
)

/**
 * Measures [node] against [availWidthMpt] and returns its size + a placer. Width-filling nodes
 * (Text/Column/Row/Box/Divider) take the available width; Image/Spacer are intrinsic. All integer
 * (fixed-point millipoint) math → identical placement on every platform.
 */
internal fun measure(node: Node, availWidthMpt: Int, book: TextMetrics): Placeable = when (node) {
    is SpacerNode -> Placeable(node.widthMpt, node.heightMpt) { _, _, _ -> }

    is DividerNode -> Placeable(availWidthMpt, node.thicknessMpt) { x, y, out ->
        out.add(RectOp(x, y, availWidthMpt, node.thicknessMpt, fill = node.color, stroke = null, strokeWidthMpt = 0))
    }

    is ImageNode -> {
        val w = if (node.widthMpt in 1..availWidthMpt) node.widthMpt else availWidthMpt
        Placeable(w, node.heightMpt) { x, y, out ->
            out.add(ImageOp(x, y, w, node.heightMpt, node.imageIndex, node.intrinsicW, node.intrinsicH, node.fit, node.align))
        }
    }

    is VectorNode -> {
        val w = if (node.widthMpt in 1..availWidthMpt) node.widthMpt else availWidthMpt
        Placeable(w, node.heightMpt) { x, y, out ->
            out.add(VectorOp(x, y, w, node.heightMpt, node.vectorIndex, node.viewportW, node.viewportH, node.fit, node.align))
        }
    }

    is TextNode -> {
        val weight = node.style.fontWeight
        val fs = node.style.fontSize.mpt
        val lineH = lineHeightMpt(fs, node.style.lineHeightMultiple)
        val ascent = book.ascentMpt(weight, fs)
        val lines = LineBreaker.wrap(node.text, weight, fs, availWidthMpt, book)
        // Heights here and below saturate at Int.MAX_VALUE (taller than any page) rather than wrap.
        Placeable(availWidthMpt, satMul(lines.size, lineH)) { x, y, out ->
            var ly = y
            for (line in lines) {
                if (line.isNotEmpty()) {
                    val lw = book.measureWidthMpt(line, weight, fs)
                    val lx = when (node.style.align) {
                        TextAlign.Start -> x
                        TextAlign.Center -> x + halfTrunc(availWidthMpt - lw)
                        TextAlign.End -> x + (availWidthMpt - lw)
                    }
                    book.placeLine(line, weight, fs, node.style.color, lx, ly + ascent, out)
                }
                ly = satAdd(ly, lineH)
            }
        }
    }

    is ColumnNode -> {
        val measured = node.children.map { measure(it, availWidthMpt, book) }
        val gaps = if (measured.size > 1) node.gapMpt.toLong() * (measured.size - 1) else 0L
        val h = (measured.sumOf { it.heightMpt.toLong() } + gaps).saturatedToInt()
        Placeable(availWidthMpt, h) { x, y, out ->
            var cy = y
            for (m in measured) { m.place(x, cy, out); cy = (cy.toLong() + m.heightMpt + node.gapMpt).saturatedToInt() }
        }
    }

    is RowNode -> {
        val n = node.children.size
        val totalGap = if (n > 1) satMul(node.gapMpt, n - 1) else 0
        val avail = (availWidthMpt.toLong() - totalGap).coerceAtLeast(0L).saturatedToInt()
        val widths = IntArray(n)
        // Content-sized cells (weight <= 0) take their intrinsic width first.
        var autoUsed = 0
        for (i in 0 until n) {
            if (node.children[i].weight <= 0f) {
                widths[i] = intrinsicWidth(node.children[i].node, book).coerceIn(0, avail - autoUsed)
                autoUsed += widths[i]
            }
        }
        // Weighted cells split whatever width is left: each share truncated to whole points (Float ×
        // weight / Double total, as in 0.10.0), the remainder — fractional or not — to the last one.
        val weightedAvail = (avail - autoUsed).coerceAtLeast(0)
        val weightedAvailPt = weightedAvail / MPT_PER_PT.toFloat()
        val totalWeight = node.children.sumOf { (if (it.weight > 0f) it.weight else 0f).toDouble() }
        if (totalWeight > 0.0) {
            var used = 0
            var lastWeighted = -1
            for (i in 0 until n) {
                if (node.children[i].weight > 0f) {
                    widths[i] = ((weightedAvailPt * node.children[i].weight) / totalWeight).toInt() * MPT_PER_PT
                    used += widths[i]
                    lastWeighted = i
                }
            }
            widths[lastWeighted] += (weightedAvail - used) // rounding remainder → last weighted cell
        }
        val measured = node.children.mapIndexed { i, c -> measure(c.node, widths[i], book) }
        val h = measured.maxOfOrNull { it.heightMpt } ?: 0
        Placeable(availWidthMpt, h) { x, y, out ->
            var cx = x
            for (i in measured.indices) {
                val dy = when (node.valign) {
                    VerticalAlignment.Top -> 0
                    VerticalAlignment.Center -> halfTrunc(h - measured[i].heightMpt)
                    VerticalAlignment.Bottom -> h - measured[i].heightMpt
                }
                measured[i].place(cx, y + dy, out)
                cx = (cx.toLong() + widths[i] + node.gapMpt).saturatedToInt()
            }
        }
    }

    is BoxNode -> {
        val pad = node.paddingMpt
        val innerW = (availWidthMpt - 2 * pad).coerceAtLeast(0)
        val childP = measure(node.child, innerW, book)
        val boxH = (childP.heightMpt.toLong() + 2L * pad).saturatedToInt()
        Placeable(availWidthMpt, boxH) { x, y, out ->
            if (node.background != null) {
                out.add(RectOp(x, y, availWidthMpt, boxH, fill = node.background, stroke = null, strokeWidthMpt = 0, cornerRadiusMpt = node.cornerRadiusMpt))
            }
            if (node.borderMpt > 0) {
                out.add(RectOp(x, y, availWidthMpt, boxH, fill = null, stroke = node.borderColor, strokeWidthMpt = node.borderMpt, cornerRadiusMpt = node.cornerRadiusMpt))
            }
            childP.place(x + pad, y + pad, out)
        }
    }

    is TableNode -> {
        // Nested/atomic table: header + all rows, no internal page splitting.
        val colWidths = tableColumnWidths(node.columns, availWidthMpt)
        val headerCells = node.columns.map { it.header }
        val aligns = node.columns.map { it.align }
        val headerH = tableRowHeight(headerCells, node.headerStyle, colWidths, node.cellPadHMpt, node.cellPadVMpt, book)
        val rowHeights = node.rows.map { tableRowHeight(it.cells, it.style, colWidths, node.cellPadHMpt, node.cellPadVMpt, book) }
        Placeable(availWidthMpt, (headerH.toLong() + rowHeights.sumOf { it.toLong() }).saturatedToInt()) { x, y, out ->
            var cy = y
            drawTableRow(headerCells, node.headerStyle, aligns, x, cy, headerH, colWidths, node.cellPadHMpt, node.cellPadVMpt, node.headerBackground, node.gridColor, book, out)
            cy = satAdd(cy, headerH)
            for (i in node.rows.indices) {
                val row = node.rows[i]
                drawTableRow(row.cells, row.style, aligns, x, cy, rowHeights[i], colWidths, node.cellPadHMpt, node.cellPadVMpt, row.background, node.gridColor, book, out)
                cy = satAdd(cy, rowHeights[i])
            }
        }
    }
}

/**
 * Natural (unwrapped) width of [node] — sizes content-sized row cells (`cell(0f)`). Text measures its
 * widest line as if it never wrapped; containers branch/sum over children. Dividers/tables fill, so
 * they have no intrinsic width here (0).
 */
internal fun intrinsicWidth(node: Node, book: TextMetrics): Int = when (node) {
    is SpacerNode -> node.widthMpt
    is DividerNode -> 0
    is TextNode -> {
        val fs = node.style.fontSize.mpt
        node.text.split('\n').maxOf { book.measureWidthMpt(it, node.style.fontWeight, fs) }
    }
    // The aspect-derived width is truncated to whole points, as in 0.10.0 (`h * iw / ih`). Widths
    // here are formed in Long and saturate rather than wrap.
    is ImageNode ->
        if (node.widthMpt > 0) node.widthMpt
        else if (node.intrinsicH > 0) wholeTrunc((node.heightMpt.toLong() * node.intrinsicW / node.intrinsicH).saturatedToInt())
        else node.heightMpt
    is VectorNode ->
        if (node.widthMpt > 0) node.widthMpt
        else if (node.viewportH > 0) wholeTrunc((node.heightMpt.toLong() * node.viewportW / node.viewportH).saturatedToInt())
        else node.heightMpt
    is ColumnNode -> node.children.maxOfOrNull { intrinsicWidth(it, book) } ?: 0
    is RowNode -> {
        val gaps = if (node.children.size > 1) node.gapMpt.toLong() * (node.children.size - 1) else 0L
        (node.children.sumOf { intrinsicWidth(it.node, book).toLong() } + gaps).saturatedToInt()
    }
    is BoxNode -> (intrinsicWidth(node.child, book).toLong() + 2L * node.paddingMpt).saturatedToInt()
    is TableNode -> 0
}
