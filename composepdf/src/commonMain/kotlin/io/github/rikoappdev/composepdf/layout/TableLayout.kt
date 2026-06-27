package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.TextStyle
import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.text.LineBreaker

internal class TableColumn(val weight: Float, val header: String, val align: TextAlign)
internal class TableRow(val cells: List<String>, val style: TextStyle, val background: PdfColor?)

internal class TableNode(
    val columns: List<TableColumn>,
    val rows: List<TableRow>,
    val headerStyle: TextStyle,
    val headerBackground: PdfColor?,
    val cellPadHPt: Int,
    val cellPadVPt: Int,
    val gridColor: PdfColor,
    val repeatHeader: Boolean,
) : Node

/** Column widths (weights filling [contentWidthPt], remainder to the last column). */
internal fun tableColumnWidths(columns: List<TableColumn>, contentWidthPt: Int): IntArray {
    val n = columns.size
    val total = columns.sumOf { it.weight.toDouble() }.let { if (it <= 0.0) 1.0 else it }
    val widths = IntArray(n)
    var used = 0
    for (i in 0 until n) { widths[i] = ((contentWidthPt * columns[i].weight) / total).toInt(); used += widths[i] }
    if (n > 0) widths[n - 1] += contentWidthPt - used
    return widths
}

internal fun tableRowHeight(cells: List<String>, style: TextStyle, colWidths: IntArray, padHPt: Int, padVPt: Int, book: TextMetrics): Int {
    val fs = style.fontSize.value
    val lineH = (fs * style.lineHeightMultiple).toInt()
    var maxLines = 1
    for (i in cells.indices) {
        val innerW = (colWidths[i] - 2 * padHPt).coerceAtLeast(1)
        val lines = LineBreaker.wrap(cells[i], style.fontWeight, fs, innerW, book).size
        if (lines > maxLines) maxLines = lines
    }
    return maxLines * lineH + 2 * padVPt
}

/** Draws one table row (header or body) at top-left (x, yTop). Emits optional background, the
 *  per-cell wrapped text aligned per column, and a bottom rule. */
internal fun drawTableRow(
    cells: List<String>,
    style: TextStyle,
    aligns: List<TextAlign>,
    x: Int,
    yTop: Int,
    rowHeight: Int,
    colWidths: IntArray,
    padHPt: Int,
    padVPt: Int,
    background: PdfColor?,
    gridColor: PdfColor,
    book: TextMetrics,
    out: MutableList<DrawOp>,
) {
    val totalW = colWidths.sum()
    if (background != null) out.add(RectOp(x, yTop, totalW, rowHeight, fill = background, stroke = null, strokeWidthPt = 0))

    val fs = style.fontSize.value
    val lineH = (fs * style.lineHeightMultiple).toInt()
    val ascent = book.ascentPt(style.fontWeight, fs)
    var cellX = x
    for (i in cells.indices) {
        val innerX = cellX + padHPt
        val innerW = (colWidths[i] - 2 * padHPt).coerceAtLeast(1)
        val lines = LineBreaker.wrap(cells[i], style.fontWeight, fs, innerW, book)
        var ly = yTop + padVPt
        for (line in lines) {
            if (line.isNotEmpty()) {
                val lw = book.measureWidthPt(line, style.fontWeight, fs)
                val lx = when (aligns[i]) {
                    TextAlign.Start -> innerX
                    TextAlign.Center -> innerX + (innerW - lw) / 2
                    TextAlign.End -> innerX + (innerW - lw)
                }
                book.placeLine(line, style.fontWeight, fs, style.color, lx, ly + ascent, out)
            }
            ly += lineH
        }
        cellX += colWidths[i]
    }
    // bottom rule
    out.add(RectOp(x, yTop + rowHeight - 1, totalW, 1, fill = gridColor, stroke = null, strokeWidthPt = 0))
}
