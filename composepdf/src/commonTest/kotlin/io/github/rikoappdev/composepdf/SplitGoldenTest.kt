package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.TextOp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform identity gate for **page breaks**: a split row with uneven cells (one of them
 * content-sized, one holding a box that splits) inside a box with a continuation header, crossing
 * three pages, with fractional paddings, gaps and borders, laid out with the deterministic
 * [StubMetrics]. The lane geometry — where each cell resumes, the box fragments, the header on
 * every continuation page — must reproduce this golden exactly on JVM, Android and iOS, like
 * [GoldenLayoutTest] and [FractionalGoldenTest] do for their documents.
 */
class SplitGoldenTest {

    @Test
    fun splitLayoutIsIdenticalToGolden() {
        assertEquals(SPLIT_GOLDEN, splitCanonical())
    }
}

private fun splitDocument(): PdfDocumentSpec = pdfDocument(
    // A short content area (320 pt to 522 pt) keeps the golden small while the row spans three pages.
    PageConfig(pageNumbers = false, margins = PageMargins(horizontal = 36.dp, vertical = 320.dp)),
) {
    val small = TextStyle(fontSize = 8.sp)
    val bold = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold)

    text("Split layout", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold))
    spacer(4.dp)
    box(
        padding = 6.5.dp,
        border = 0.75.dp,
        cornerRadius = 3.dp,
        background = Color(0xFFF8F9FA),
        continuationHeader = { text("Card (continued)", bold) },
    ) {
        text("Card", bold)
        spacer(3.dp)
        // Centre alignment falls back to the top once the row splits.
        row(gap = 12.5.dp, verticalAlignment = VerticalAlignment.Center, split = true, keepTogether = false) {
            cell(1f) { repeat(36) { text("L$it", small) } }
            cell(1f) {
                repeat(6) { text("R$it", small) }
                box(paddingHorizontal = 4.dp, paddingVertical = 2.5.dp, border = 0.5.dp, keepTogether = false) {
                    repeat(14) { text("N$it", small) }
                }
                divider(thickness = 0.5.dp)
            }
            cell(0f) { text("Qty", small) }
        }
    }
    text("After", small)
}

/** Canonical string of every text origin and rectangle: `page|T|x,y|size|weight|gids` / `page|R|x,y,w,h|stroke|radius`. */
internal fun splitCanonical(): String {
    val pages: List<Page> = layout(splitDocument(), StubMetrics)
    val sb = StringBuilder()
    sb.append("pages=").append(pages.size).append('\n')
    for ((p, page) in pages.withIndex()) {
        for (op in page.ops) when (op) {
            is TextOp -> sb.append(p).append("|T|")
                .append(fmtMpt(op.xMpt)).append(',').append(fmtMpt(op.baselineYMpt)).append('|')
                .append(fmtMpt(op.fontSizeMpt)).append('|')
                .append(op.weight.name).append('|')
                .append(op.gids.joinToString(","))
                .append('\n')
            is RectOp -> sb.append(p).append("|R|")
                .append(fmtMpt(op.xMpt)).append(',').append(fmtMpt(op.yMpt)).append(',')
                .append(fmtMpt(op.wMpt)).append(',').append(fmtMpt(op.hMpt)).append('|')
                .append(fmtMpt(op.strokeWidthMpt)).append('|')
                .append(fmtMpt(op.cornerRadiusMpt))
                .append('\n')
            else -> {}
        }
    }
    return sb.toString()
}

// Captured from the deterministic millipoint engine on JVM. Any platform that computes different integers fails this.
private val SPLIT_GOLDEN = """pages=3
0|R|36,337,523,185|0|3
0|T|36,328|10|Bold|83,112,108,105,116,32,108,97,121,111,117,116
0|T|42.5,349.5|8|Bold|67,97,114,100
0|T|42.5,362.5|8|Normal|76,48
0|T|42.5,372.5|8|Normal|76,49
0|T|42.5,382.5|8|Normal|76,50
0|T|42.5,392.5|8|Normal|76,51
0|T|42.5,402.5|8|Normal|76,52
0|T|42.5,412.5|8|Normal|76,53
0|T|42.5,422.5|8|Normal|76,54
0|T|42.5,432.5|8|Normal|76,55
0|T|42.5,442.5|8|Normal|76,56
0|T|42.5,452.5|8|Normal|76,57
0|T|42.5,462.5|8|Normal|76,49,48
0|T|42.5,472.5|8|Normal|76,49,49
0|T|42.5,482.5|8|Normal|76,49,50
0|T|42.5,492.5|8|Normal|76,49,51
0|T|42.5,502.5|8|Normal|76,49,52
0|T|42.5,512.5|8|Normal|76,49,53
0|R|36,337,523,185|0.75|3
0|T|290,362.5|8|Normal|82,48
0|T|290,372.5|8|Normal|82,49
0|T|290,382.5|8|Normal|82,50
0|T|290,392.5|8|Normal|82,51
0|T|290,402.5|8|Normal|82,52
0|T|290,412.5|8|Normal|82,53
0|T|294,425|8|Normal|78,48
0|T|294,435|8|Normal|78,49
0|T|294,445|8|Normal|78,50
0|T|294,455|8|Normal|78,51
0|T|294,465|8|Normal|78,52
0|T|294,475|8|Normal|78,53
0|T|294,485|8|Normal|78,54
0|T|294,495|8|Normal|78,55
0|T|294,505|8|Normal|78,56
0|T|294,515|8|Normal|78,57
0|R|290,416.5,236,105.5|0.5|0
0|T|538.5,362.5|8|Normal|81,116,121
1|R|36,320,523,202|0|3
1|T|42.5,332.5|8|Bold|67,97,114,100,32,40,99,111,110,116,105,110,117,101,100,41
1|T|42.5,342.5|8|Normal|76,49,54
1|T|42.5,352.5|8|Normal|76,49,55
1|T|42.5,362.5|8|Normal|76,49,56
1|T|42.5,372.5|8|Normal|76,49,57
1|T|42.5,382.5|8|Normal|76,50,48
1|T|42.5,392.5|8|Normal|76,50,49
1|T|42.5,402.5|8|Normal|76,50,50
1|T|42.5,412.5|8|Normal|76,50,51
1|T|42.5,422.5|8|Normal|76,50,52
1|T|42.5,432.5|8|Normal|76,50,53
1|T|42.5,442.5|8|Normal|76,50,54
1|T|42.5,452.5|8|Normal|76,50,55
1|T|42.5,462.5|8|Normal|76,50,56
1|T|42.5,472.5|8|Normal|76,50,57
1|T|42.5,482.5|8|Normal|76,51,48
1|T|42.5,492.5|8|Normal|76,51,49
1|T|42.5,502.5|8|Normal|76,51,50
1|T|42.5,512.5|8|Normal|76,51,51
1|R|36,320,523,202|0.75|3
1|T|294,345|8|Normal|78,49,48
1|T|294,355|8|Normal|78,49,49
1|T|294,365|8|Normal|78,49,50
1|T|294,375|8|Normal|78,49,51
1|R|290,336.5,236,45|0.5|0
1|R|290,381.5,236,0.5|0|0
2|R|36,320,523,43|0|3
2|T|42.5,332.5|8|Bold|67,97,114,100,32,40,99,111,110,116,105,110,117,101,100,41
2|T|42.5,342.5|8|Normal|76,51,52
2|T|42.5,352.5|8|Normal|76,51,53
2|R|36,320,523,43|0.75|3
2|T|36,369|8|Normal|65,102,116,101,114
"""
