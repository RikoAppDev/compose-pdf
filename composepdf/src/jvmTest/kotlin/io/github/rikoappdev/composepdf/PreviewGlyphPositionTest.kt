package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.preview.PreviewText
import io.github.rikoappdev.composepdf.preview.previewPages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The preview places each glyph at the rounded CUMULATIVE advance (matching the engine's round-once
 * [measureWidthPt]), not a running sum of per-glyph rounded deltas. The old per-glyph rounding added
 * ~0.4pt per tabular digit, so a right-aligned number drifted past its edge in the on-screen preview
 * (the exported PDF was always correct). A run of identical digits is the tell-tale: with round-once
 * cumulative math the integer step is not constant (it alternates floor/ceil); with per-glyph rounding
 * every step is identical.
 */
class PreviewGlyphPositionTest {

    private fun reg() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    @Test
    fun identicalDigitsUseCumulativeRounding() {
        val digits = "0000000000"
        val spec = pdfDocument(PageConfig(margin = 36.dp)) {
            text(digits, TextStyle(fontSize = 10.sp, align = TextAlign.End))
        }
        val text = spec.previewPages(reg(), bold())
            .flatMap { it.ops }
            .filterIsInstance<PreviewText>()
            .first { it.text == digits }

        assertEquals(text.xPt, text.glyphXPt[0], "first glyph must sit at the run origin")
        val deltas = text.glyphXPt.toList().zipWithNext { a, b -> b - a }
        assertTrue(deltas.all { it > 0 }, "glyph x positions must be strictly increasing, was $deltas")
        // A digit's advance scaled to points is fractional, so round-once cumulative produces a
        // non-constant integer step; per-glyph rounding (the bug) would make every step identical.
        assertTrue(deltas.toSet().size > 1, "expected varying steps from cumulative rounding, got $deltas")
    }
}
