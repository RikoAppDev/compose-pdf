package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.HorizontalAlignment
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.TextStyle
import io.github.rikoappdev.composepdf.VerticalAlignment

/** Immutable layout tree produced by the DSL and consumed by the measure/place engine. Lengths are
 *  in millipoints (1 pt = 1000 mpt). */
internal sealed interface Node

internal class TextNode(val text: String, val style: TextStyle) : Node
internal class SpacerNode(val widthMpt: Int, val heightMpt: Int) : Node
internal class DividerNode(val color: PdfColor, val thicknessMpt: Int) : Node
internal class ImageNode(
    val imageIndex: Int,
    val widthMpt: Int,   // 0 = fill available width
    val heightMpt: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
    val align: HorizontalAlignment = HorizontalAlignment.Center,
) : Node {
    /** This image with box height [heightMpt]; every other field is kept. */
    fun withHeight(heightMpt: Int) = ImageNode(imageIndex, widthMpt, heightMpt, intrinsicW, intrinsicH, fit, align)
}
internal class VectorNode(
    val vectorIndex: Int,
    val widthMpt: Int,   // 0 = fill available width
    val heightMpt: Int,
    val viewportW: Int,
    val viewportH: Int,
    val fit: PhotoFit,
    val align: HorizontalAlignment = HorizontalAlignment.Center,
) : Node {
    /** This vector with box height [heightMpt]; every other field is kept. */
    fun withHeight(heightMpt: Int) = VectorNode(vectorIndex, widthMpt, heightMpt, viewportW, viewportH, fit, align)
}
internal class ColumnNode(val children: List<Node>, val gapMpt: Int) : Node
internal class RowChild(val node: Node, val weight: Float)
internal class RowNode(
    val children: List<RowChild>,
    val gapMpt: Int,
    val valign: VerticalAlignment = VerticalAlignment.Top,
) : Node
internal class BoxNode(
    val child: Node,
    val paddingMpt: Int,
    val borderMpt: Int,
    val borderColor: PdfColor,
    val background: PdfColor?,
    val cornerRadiusMpt: Int = 0,
) : Node
