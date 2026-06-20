package io.github.rikoappdev.composepdf.layout

import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.TextStyle

/** Immutable layout tree produced by the DSL and consumed by the measure/place engine. */
internal sealed interface Node

internal class TextNode(val text: String, val style: TextStyle) : Node
internal class SpacerNode(val widthPt: Int, val heightPt: Int) : Node
internal class DividerNode(val color: PdfColor, val thicknessPt: Int) : Node
internal class ImageNode(
    val imageIndex: Int,
    val widthPt: Int,   // 0 = fill available width
    val heightPt: Int,
    val intrinsicW: Int,
    val intrinsicH: Int,
    val fit: PhotoFit,
) : Node
internal class ColumnNode(val children: List<Node>, val gapPt: Int) : Node
internal class RowChild(val node: Node, val weight: Float)
internal class RowNode(val children: List<RowChild>, val gapPt: Int) : Node
internal class BoxNode(
    val child: Node,
    val paddingPt: Int,
    val borderPt: Int,
    val borderColor: PdfColor,
    val background: PdfColor?,
    val cornerRadiusPt: Int = 0,
) : Node
