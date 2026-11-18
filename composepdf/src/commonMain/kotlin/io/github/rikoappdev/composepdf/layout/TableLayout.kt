package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.MPT_PER_PT
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.TextStyle
import io.github.rikoappdev.composepdf.font.TextMetrics
import io.github.rikoappdev.composepdf.halfTrunc
import io.github.rikoappdev.composepdf.lineHeightMpt
import io.github.rikoappdev.composepdf.mpt
import io.github.rikoappdev.composepdf.render.DrawOp
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.saturatedToInt
import io.github.rikoappdev.composepdf.text.LineBreaker

internal class TableColumn(val weight: Float, val header: String, val align: TextAlign)
internal class TableRow(val cells: List<String>, val style: TextStyle, val background: PdfColor?)

internal class TableNode(
    val columns: List<TableColumn>,
    val rows: List<TableRow>,
    val headerStyle: TextStyle,
    val headerBackground: PdfColor?,
    val cellPadHMpt: Int,
    val cellPadVMpt: Int,
    val gridColor: PdfColor,
    val repeatHeader: Boolean,
) : Node

/** Thickness of the rule under every table row, and the narrowest inner cell width: 1 pt. */
private const val ONE_PT_MPT = MPT_PER_PT

/** Column widths (weights filling [contentWidthMpt], each truncated to whole points, remainder to the last column). */
internal fun tableColumnWidths(columns: List<TableColumn>, contentWidthMpt: Int): IntArray {
    val n = columns.size
    val total = columns.sumOf { it.weight.toDouble() }.let { if (it <= 0.0) 1.0 else it }
    val contentPt = contentWidthMpt / MPT_PER_PT.toFloat()
    val widths = IntArray(n)
    var used = 0
    for (i in 0 until n) { widths[i] = ((contentPt * columns[i].weight) / total).toInt() * MPT_PER_PT; used += widths[i] }
    if (n > 0) widths[n - 1] += contentWidthMpt - used
    return widths
}

internal fun tableRowHeight(cells: List<String>, style: TextStyle, colWidths: IntArray, padHMpt: Int, padVMpt: Int, book: TextMetrics): Int {
    val fs = style.fontSize.mpt
    val lineH = lineHeightMpt(fs, style.lineHeightMultiple)
    var maxLines = 1
    for (i in cells.indices) {
        val innerW = (colWidths[i] - 2 * padHMpt).coerceAtLeast(ONE_PT_MPT)
        val lines = LineBreaker.wrap(cells[i], style.fontWeight, fs, innerW, book).size
        if (lines > maxLines) maxLines = lines
    }
    return (maxLines.toLong() * lineH + 2L * padVMpt).saturatedToInt() // saturates rather than wraps
}

/** Draws one table row (header or body) at top-left (x, yTop). Emits optional background, the
 *  per-cell wrapped text aligned per column, and a bottom rule. Millipoints. */
internal fun drawTableRow(
    cells: List<String>,
    style: TextStyle,
    aligns: List<TextAlign>,
    x: Int,
    yTop: Int,
    rowHeight: Int,
    colWidths: IntArray,
    padHMpt: Int,
    padVMpt: Int,
    background: PdfColor?,
    gridColor: PdfColor,
    book: TextMetrics,
    out: MutableList<DrawOp>,
) {
    val totalW = colWidths.sum()
    if (background != null) out.add(RectOp(x, yTop, totalW, rowHeight, fill = background, stroke = null, strokeWidthMpt = 0))

    val fs = style.fontSize.mpt
    val lineH = lineHeightMpt(fs, style.lineHeightMultiple)
    val ascent = book.ascentMpt(style.fontWeight, fs)
    var cellX = x
    for (i in cells.indices) {
        val innerX = cellX + padHMpt
        val innerW = (colWidths[i] - 2 * padHMpt).coerceAtLeast(ONE_PT_MPT)
        val lines = LineBreaker.wrap(cells[i], style.fontWeight, fs, innerW, book)
        var ly = yTop + padVMpt
        for (line in lines) {
            if (line.isNotEmpty()) {
                val lw = book.measureWidthMpt(line, style.fontWeight, fs)
                val lx = when (aligns[i]) {
                    TextAlign.Start -> innerX
                    TextAlign.Center -> innerX + halfTrunc(innerW - lw)
                    TextAlign.End -> innerX + (innerW - lw)
                }
                book.placeLine(line, style.fontWeight, fs, style.color, lx, ly + ascent, out)
            }
            ly += lineH
        }
        cellX += colWidths[i]
    }
    // bottom rule
    out.add(RectOp(x, yTop + rowHeight - ONE_PT_MPT, totalW, ONE_PT_MPT, fill = gridColor, stroke = null, strokeWidthMpt = 0))
}
