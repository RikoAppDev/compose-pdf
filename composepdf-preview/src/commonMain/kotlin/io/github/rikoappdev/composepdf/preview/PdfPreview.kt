package io.github.rikoappdev.composepdf.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rikoappdev.composepdf.PdfDocumentSpec
import kotlin.math.roundToInt

/**
 * A live, on-screen preview of a [pdfDocument][io.github.rikoappdev.composepdf.pdfDocument] spec —
 * the intended use is a **design-time IDE `@Preview`**: wrap your `pdfDocument { … }` in a
 * `@Preview @Composable` that calls [PdfPreview] and the IDE renders the document live as you edit
 * the builder, with no app run and no export. (It is also a usable runtime composable, but it is not
 * meant as an in-app "view instead of download" button.)
 *
 * It runs the **same** layout pass as `render()` (via [previewPages]), so page count, line breaks,
 * tables, boxes, images and vectors land exactly where the exported PDF puts them. Block/line/image
 * positions come from the engine; intra-line glyph advances use the platform font (a faithful
 * approximation — the PDF stays the source of truth). Every paginated page is stacked vertically (a
 * subtle shadow separates them) — set [pageWidth] to render at a fixed width and let the preview
 * self-size to the whole document, or leave it null to fill the available width and scroll.
 *
 * Fonts are the same Regular + Bold bytes you pass to `render()`. For zero-setup IDE previews of your
 * own documents, use [previewFontRegular]/[previewFontBold] (a font bundled in this preview artifact,
 * loaded synchronously so it works inside the IDE preview runtime).
 */
@Composable
fun PdfPreview(
    spec: PdfDocumentSpec,
    regularFontBytes: ByteArray,
    boldFontBytes: ByteArray,
    modifier: Modifier = Modifier,
    /**
     * Width to render each page at. When `null` (default) pages fill the available width and the column
     * fills its parent and scrolls — for an in-app/runtime view. Set it (e.g. `360.dp`) to make the
     * preview **self-size**: each page gets this fixed width and the column wraps its content height, so
     * a bare `@Preview` (no `widthDp`/`heightDp`) sizes itself to the whole paginated document.
     */
    pageWidth: Dp? = null,
    pageGap: Dp = 16.dp,
    pageColor: Color = Color.White,
    backgroundColor: Color = Color(0xFFE9ECEF),
) {
    val pages = remember(spec, regularFontBytes, boldFontBytes) {
        spec.previewPages(regularFontBytes, boldFontBytes)
    }
    val family = remember(regularFontBytes, boldFontBytes) {
        previewFontFamily(regularFontBytes, boldFontBytes)
    }
    val bitmaps = remember(pages) {
        val map = HashMap<PreviewImage, ImageBitmap>()
        for (page in pages) for (op in page.ops) if (op is PreviewImage) {
            runCatching { decodePreviewImage(op.source) }.getOrNull()?.let { map[op] = it }
        }
        map
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val selfSize = pageWidth != null
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .then(if (selfSize) Modifier else Modifier.fillMaxSize())
            .background(backgroundColor)
            .then(if (selfSize) Modifier else Modifier.verticalScroll(scroll))
            .padding(pageGap),
        verticalArrangement = Arrangement.spacedBy(pageGap),
    ) {
        for (page in pages) {
            Canvas(
                (if (pageWidth != null) Modifier.width(pageWidth) else Modifier.fillMaxWidth())
                    .aspectRatio(page.widthPt.toFloat() / page.heightPt)
                    .shadow(3.dp)
                    .background(pageColor)
            ) {
                val scale = size.width / page.widthPt
                for (op in page.ops) drawOp(op, scale, family, measurer, density, bitmaps)
            }
        }
    }
}

private fun DrawScope.drawOp(
    op: PreviewOp,
    scale: Float,
    family: FontFamily,
    measurer: TextMeasurer,
    density: Density,
    bitmaps: Map<PreviewImage, ImageBitmap>,
) {
    when (op) {
        is PreviewRect -> {
            val tl = Offset(op.xPt * scale, op.yPt * scale)
            val sz = Size(op.wPt * scale, op.hPt * scale)
            val r = op.cornerRadiusPt * scale
            op.fillArgb?.let {
                if (r > 0f) drawRoundRect(Color(it), tl, sz, CornerRadius(r, r), style = Fill)
                else drawRect(Color(it), tl, sz, style = Fill)
            }
            op.strokeArgb?.let {
                val sw = (op.strokeWidthPt * scale).coerceAtLeast(0.5f)
                if (r > 0f) drawRoundRect(Color(it), tl, sz, CornerRadius(r, r), style = Stroke(sw))
                else drawRect(Color(it), tl, sz, style = Stroke(sw))
            }
        }

        is PreviewText -> {
            if (op.text.isEmpty()) return
            val sp = with(density) { (op.fontSizePt * scale).toSp() }
            val layout = measurer.measure(
                AnnotatedString(op.text),
                style = TextStyle(
                    color = Color(op.colorArgb),
                    fontFamily = family,
                    fontWeight = if (op.bold) FontWeight.Bold else FontWeight.Normal,
                    fontSize = sp,
                ),
                maxLines = 1,
                softWrap = false,
            )
            drawText(layout, topLeft = Offset(op.xPt * scale, op.baselineYPt * scale - layout.firstBaseline))
        }

        is PreviewImage -> {
            val bmp = bitmaps[op] ?: return
            val dstOffset = IntOffset((op.dstXPt * scale).roundToInt(), (op.dstYPt * scale).roundToInt())
            val dstSize = IntSize(
                (op.dstWPt * scale).roundToInt().coerceAtLeast(1),
                (op.dstHPt * scale).roundToInt().coerceAtLeast(1),
            )
            if (op.clip) {
                clipRect(
                    op.clipXPt * scale, op.clipYPt * scale,
                    (op.clipXPt + op.clipWPt) * scale, (op.clipYPt + op.clipHPt) * scale,
                ) { drawImage(bmp, dstOffset = dstOffset, dstSize = dstSize) }
            } else {
                drawImage(bmp, dstOffset = dstOffset, dstSize = dstSize)
            }
        }

        is PreviewVector -> {
            for (vp in op.paths) {
                val path = Path()
                path.fillType = if (vp.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
                for (seg in vp.segs) when (seg) {
                    is PreviewMoveTo -> path.moveTo(seg.xPt * scale, seg.yPt * scale)
                    is PreviewLineTo -> path.lineTo(seg.xPt * scale, seg.yPt * scale)
                    is PreviewCubicTo -> path.cubicTo(
                        seg.c1xPt * scale, seg.c1yPt * scale,
                        seg.c2xPt * scale, seg.c2yPt * scale,
                        seg.xPt * scale, seg.yPt * scale,
                    )
                    PreviewClose -> path.close()
                }
                vp.fillArgb?.let { drawPath(path, Color(it), style = Fill) }
                vp.strokeArgb?.let {
                    drawPath(path, Color(it), style = Stroke((vp.strokeWidthPt * scale).coerceAtLeast(0.3f)))
                }
            }
        }
    }
}
