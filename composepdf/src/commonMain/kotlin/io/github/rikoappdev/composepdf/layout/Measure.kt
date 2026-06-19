package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
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
internal fun measure(node: Node, availWidthPt: Int, book: FontBook): Placeable = when (node) {
    is SpacerNode -> Placeable(node.widthPt, node.heightPt) { _, _, _ -> }

    is DividerNode -> Placeable(availWidthPt, node.thicknessPt) { x, y, out ->
        out.add(RectOp(x, y, availWidthPt, node.thicknessPt, fill = node.color, stroke = null, strokeWidthPt = 0))
    }

    is ImageNode -> {
        val w = if (node.widthPt in 1..availWidthPt) node.widthPt else availWidthPt
        Placeable(w, node.heightPt) { x, y, out ->
            out.add(ImageOp(x, y, w, node.heightPt, node.imageIndex, node.intrinsicW, node.intrinsicH, node.cover))
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
                    val gids = book.shape(line, weight)
                    val lw = book.widthOfPt(gids, weight, fs)
                    val lx = when (node.style.align) {
                        TextAlign.Start -> x
                        TextAlign.Center -> x + (availWidthPt - lw) / 2
                        TextAlign.End -> x + (availWidthPt - lw)
                    }
                    out.add(TextOp(lx, ly + ascent, gids, weight, fs, node.style.color))
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
        val avail = availWidthPt - totalGap
        val totalWeight = node.children.sumOf { it.weight.toDouble() }.let { if (it <= 0.0) 1.0 else it }
        val widths = IntArray(n)
        var used = 0
        for (i in 0 until n) {
            widths[i] = ((avail * node.children[i].weight) / totalWeight).toInt()
            used += widths[i]
        }
        if (n > 0) widths[n - 1] += (avail - used) // remainder to last cell
        val measured = node.children.mapIndexed { i, c -> measure(c.node, widths[i], book) }
        val h = measured.maxOfOrNull { it.heightPt } ?: 0
        Placeable(availWidthPt, h) { x, y, out ->
            var cx = x
            for (i in measured.indices) { measured[i].place(cx, y, out); cx += widths[i] + node.gapPt }
        }
    }

    is BoxNode -> {
        val pad = node.paddingPt
        val innerW = (availWidthPt - 2 * pad).coerceAtLeast(0)
        val childP = measure(node.child, innerW, book)
        val boxH = childP.heightPt + 2 * pad
        Placeable(availWidthPt, boxH) { x, y, out ->
            if (node.background != null) {
                out.add(RectOp(x, y, availWidthPt, boxH, fill = node.background, stroke = null, strokeWidthPt = 0))
            }
            if (node.borderPt > 0) {
                out.add(RectOp(x, y, availWidthPt, boxH, fill = null, stroke = node.borderColor, strokeWidthPt = node.borderPt))
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
