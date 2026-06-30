package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.VerticalAlignment
import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.text.LineBreaker

/** A measured node: its size + a placer that emits absolute draw ops at a given top-left (x,y). */
internal class Placeable(
    val widthPt: Int,
    val heightPt: Int,
    val place: (x: Int, y: Int, out: MutableList<DrawOp>) -> Unit,
)

/**
 * Measures [node] against [availWidthPt] and returns its size + a placer. Width-filling nodes
 * (Text/Column/Row/Box/Divider) take the available width; Image/Spacer are intrinsic. All integer
 * math → identical placement on every platform.
 */
internal fun measure(node: Node, availWidthPt: Int, book: TextMetrics): Placeable = when (node) {
    is SpacerNode -> Placeable(node.widthPt, node.heightPt) { _, _, _ -> }

    is DividerNode -> Placeable(availWidthPt, node.thicknessPt) { x, y, out ->
        out.add(RectOp(x, y, availWidthPt, node.thicknessPt, fill = node.color, stroke = null, strokeWidthPt = 0))
    }

    is ImageNode -> {
        val w = if (node.widthPt in 1..availWidthPt) node.widthPt else availWidthPt
        Placeable(w, node.heightPt) { x, y, out ->
            out.add(ImageOp(x, y, w, node.heightPt, node.imageIndex, node.intrinsicW, node.intrinsicH, node.fit, node.align))
        }
    }

    is VectorNode -> {
        val w = if (node.widthPt in 1..availWidthPt) node.widthPt else availWidthPt
        Placeable(w, node.heightPt) { x, y, out ->
            out.add(VectorOp(x, y, w, node.heightPt, node.vectorIndex, node.viewportW, node.viewportH, node.fit, node.align))
        }
    }

    is TextNode -> {
        val weight = node.style.fontWeight
        val fs = node.style.fontSize.value
        val lineH = (fs * node.style.lineHeightMultiple).toInt()
        val ascent = book.ascentPt(weight, fs)
        val lines = LineBreaker.wrap(node.text, weight, fs, availWidthPt, book)
        Placeable(availWidthPt, lines.size * lineH) { x, y, out ->
            var ly = y
            for (line in lines) {
                if (line.isNotEmpty()) {
                    val lw = book.measureWidthPt(line, weight, fs)
                    val lx = when (node.style.align) {
                        TextAlign.Start -> x
                        TextAlign.Center -> x + (availWidthPt - lw) / 2
                        TextAlign.End -> x + (availWidthPt - lw)
                    }
                    book.placeLine(line, weight, fs, node.style.color, lx, ly + ascent, out)
                }
                ly += lineH
            }
        }
    }

    is ColumnNode -> {
        val measured = node.children.map { measure(it, availWidthPt, book) }
        val gaps = if (measured.size > 1) node.gapPt * (measured.size - 1) else 0
        val h = measured.sumOf { it.heightPt } + gaps
        Placeable(availWidthPt, h) { x, y, out ->
            var cy = y
            for (m in measured) { m.place(x, cy, out); cy += m.heightPt + node.gapPt }
        }
    }

    is RowNode -> {
        val n = node.children.size
        val totalGap = if (n > 1) node.gapPt * (n - 1) else 0
        val avail = (availWidthPt - totalGap).coerceAtLeast(0)
        val widths = IntArray(n)
        // Content-sized cells (weight <= 0) take their intrinsic width first.
        var autoUsed = 0
        for (i in 0 until n) {
            if (node.children[i].weight <= 0f) {
                widths[i] = intrinsicWidth(node.children[i].node, book).coerceIn(0, avail - autoUsed)
                autoUsed += widths[i]
            }
        }
        // Weighted cells split whatever width is left.
        val weightedAvail = (avail - autoUsed).coerceAtLeast(0)
        val totalWeight = node.children.sumOf { (if (it.weight > 0f) it.weight else 0f).toDouble() }
        if (totalWeight > 0.0) {
            var used = 0
            var lastWeighted = -1
            for (i in 0 until n) {
                if (node.children[i].weight > 0f) {
                    widths[i] = ((weightedAvail * node.children[i].weight) / totalWeight).toInt()
                    used += widths[i]
                    lastWeighted = i
                }
            }
            widths[lastWeighted] += (weightedAvail - used) // rounding remainder → last weighted cell
        }
        val measured = node.children.mapIndexed { i, c -> measure(c.node, widths[i], book) }
        val h = measured.maxOfOrNull { it.heightPt } ?: 0
        Placeable(availWidthPt, h) { x, y, out ->
            var cx = x
            for (i in measured.indices) {
                val dy = when (node.valign) {
                    VerticalAlignment.Top -> 0
                    VerticalAlignment.Center -> (h - measured[i].heightPt) / 2
                    VerticalAlignment.Bottom -> h - measured[i].heightPt
                }
                measured[i].place(cx, y + dy, out)
                cx += widths[i] + node.gapPt
            }
        }
    }

    is BoxNode -> {
        val pad = node.paddingPt
        val innerW = (availWidthPt - 2 * pad).coerceAtLeast(0)
        val childP = measure(node.child, innerW, book)
        val boxH = childP.heightPt + 2 * pad
        Placeable(availWidthPt, boxH) { x, y, out ->
            if (node.background != null) {
                out.add(RectOp(x, y, availWidthPt, boxH, fill = node.background, stroke = null, strokeWidthPt = 0, cornerRadiusPt = node.cornerRadiusPt))
            }
            if (node.borderPt > 0) {
                out.add(RectOp(x, y, availWidthPt, boxH, fill = null, stroke = node.borderColor, strokeWidthPt = node.borderPt, cornerRadiusPt = node.cornerRadiusPt))
            }
            childP.place(x + pad, y + pad, out)
        }
    }

    is TableNode -> {
        // Nested/atomic table: header + all rows, no internal page splitting.
        val colWidths = tableColumnWidths(node.columns, availWidthPt)
        val headerCells = node.columns.map { it.header }
        val aligns = node.columns.map { it.align }
        val headerH = tableRowHeight(headerCells, node.headerStyle, colWidths, node.cellPadHPt, node.cellPadVPt, book)
        val rowHeights = node.rows.map { tableRowHeight(it.cells, it.style, colWidths, node.cellPadHPt, node.cellPadVPt, book) }
        Placeable(availWidthPt, headerH + rowHeights.sum()) { x, y, out ->
            var cy = y
            drawTableRow(headerCells, node.headerStyle, aligns, x, cy, headerH, colWidths, node.cellPadHPt, node.cellPadVPt, node.headerBackground, node.gridColor, book, out)
            cy += headerH
            for (i in node.rows.indices) {
                val row = node.rows[i]
                drawTableRow(row.cells, row.style, aligns, x, cy, rowHeights[i], colWidths, node.cellPadHPt, node.cellPadVPt, row.background, node.gridColor, book, out)
                cy += rowHeights[i]
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
    is SpacerNode -> node.widthPt
    is DividerNode -> 0
    is TextNode -> {
        val fs = node.style.fontSize.value
        node.text.split('\n').maxOf { book.measureWidthPt(it, node.style.fontWeight, fs) }
    }
    is ImageNode ->
        if (node.widthPt > 0) node.widthPt
        else if (node.intrinsicH > 0) node.heightPt * node.intrinsicW / node.intrinsicH
        else node.heightPt
    is VectorNode ->
        if (node.widthPt > 0) node.widthPt
        else if (node.viewportH > 0) node.heightPt * node.viewportW / node.viewportH
        else node.heightPt
    is ColumnNode -> node.children.maxOfOrNull { intrinsicWidth(it, book) } ?: 0
    is RowNode -> {
        val gaps = if (node.children.size > 1) node.gapPt * (node.children.size - 1) else 0
        node.children.sumOf { intrinsicWidth(it.node, book) } + gaps
    }
    is BoxNode -> intrinsicWidth(node.child, book) + 2 * node.paddingPt
    is TableNode -> 0
}
