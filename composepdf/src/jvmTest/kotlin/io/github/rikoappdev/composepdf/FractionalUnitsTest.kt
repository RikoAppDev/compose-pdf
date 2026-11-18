package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.render.TextOp
import org.apache.pdfbox.Loader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fractional `dp` / `sp`: the values given are honoured to 0.001 pt and written to the PDF as such,
 * fixed-point sums never drift, and whole values keep producing the bytes they always did.
 */
class FractionalUnitsTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private val plain = PageConfig(pageNumbers = false)

    private fun render(cfg: PageConfig = plain, build: PdfContentScope.() -> Unit): ByteArray =
        pdfDocument(cfg, build).render(regular(), bold())

    /** Decoded content stream of [page]. */
    private fun content(pdf: ByteArray, page: Int = 0): String =
        Loader.loadPDF(pdf).use { d -> d.getPage(page).contents.readBytes().decodeToString() }

    @Test
    fun unitsAcceptIntFloatAndDouble() {
        assertEquals(12.dp, Dp(12))
        assertEquals(12.dp, 12f.dp)
        assertEquals(12.dp, 12.0.dp)
        assertEquals(0.75f, 0.75.dp.value)
        assertEquals(8.sp, Sp(8))
        assertEquals(7.5f, 7.5.sp.value)
        assertEquals(7.5.sp, 7.5f.sp)
    }

    @Test
    fun wholeValuesWrittenAsFloatOrDoubleAreByteIdentical() {
        fun doc(gap: Dp, size: Sp) = render {
            spacer(gap)
            text("Example App", TextStyle(fontSize = size))
            divider(thickness = gap)
        }
        val ints = doc(12.dp, 9.sp)
        assertContentEquals(ints, doc(12f.dp, 9f.sp))
        assertContentEquals(ints, doc(12.0.dp, 9.0.sp))
        assertContentEquals(ints, doc(Dp(12), Sp(9)))
    }

    @Test
    fun fractionalFontSizeIsWrittenToTheContentStream() {
        val c = content(render { text("Try it at example.com", TextStyle(fontSize = 7.5.sp)) })
        assertTrue(Regex("""/F0 7\.5 Tf""").containsMatchIn(c), "expected a 7.5 pt Tf, content was:\n$c")
    }

    @Test
    fun fractionalLineHeightIsFlooredToWholePoints() {
        val spec = pdfDocument(plain) { text("first\nsecond", TextStyle(fontSize = 7.5.sp)) } // 7.5 × 1.3 = 9.75 → 9
        val ops = layout(spec, FontBook(regular(), bold())).single().ops.filterIsInstance<TextOp>()
        assertEquals(listOf(7500, 7500), ops.map { it.fontSizeMpt })
        assertEquals(9000, ops[1].baselineYMpt - ops[0].baselineYMpt)
    }

    @Test
    fun fractionalDividerThicknessIsWrittenExactly() {
        // A4, 36 pt margins: the rule spans x 36..559 and its top sits at y = 36, so PDF y = 842 - 36.75.
        val c = content(render { divider(thickness = 0.75.dp) })
        assertTrue("36 805.25 523 0.75 re" in c, "expected the 0.75 pt rule, content was:\n$c")
    }

    @Test
    fun fractionalSpacersAccumulateWithoutDrift() {
        val tenths = render { repeat(10) { spacer(0.1.dp) }; repeat(4) { spacer(2.5.dp) }; divider() }
        val whole = render { spacer(11.dp); divider() }
        assertContentEquals(whole, tenths, "10 × 0.1 + 4 × 2.5 must be exactly 11 pt")
    }

    @Test
    fun fractionalPaddingBorderAndRadiusReachThePdf() {
        val c = content(render {
            box(padding = 4.5.dp, border = 0.5.dp, cornerRadius = 2.5.dp) { text("Boxed note", TextStyle(fontSize = 9.sp)) }
        })
        assertTrue("0.5 w" in c, "expected a 0.5 pt stroke, content was:\n$c")
        // Box: 36..559 wide, 36 pt from the top, 4.5 + 11 (9 × 1.3 floored) + 4.5 = 20 pt tall → PDF y 786..806.
        // The corner path starts at x + r = 38.5 on the bottom edge.
        assertTrue("38.5 786 m" in c, "expected the rounded path to start at 38.5 786, content was:\n$c")
        // The text sits inside the padding: x = 36 + 4.5.
        assertTrue(Regex("""40\.5 [0-9.]+ Td""").containsMatchIn(c), "expected text at x = 40.5, content was:\n$c")
    }

    @Test
    fun coverImageFillsAFractionalBoxEdgeToEdge() {
        val img = BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 50) for (x in 0 until 100) img.setRGB(x, y, 0x3366CC)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val c = content(render { image(png, width = 50.4.dp, height = 20.dp, fit = PhotoFit.Cover, align = HorizontalAlignment.Start) })
        // Cover scale = max(50.4 / 100, 20 / 50) = 0.504 → drawn 50.4 × 25.2, clipped to the 50.4 × 20 box.
        assertTrue("36 786 50.4 20 re W n" in c, "expected the 50.4 × 20 clip box, content was:\n$c")
        assertTrue("50.4 0 0 25.2 36 " in c, "expected a 50.4 × 25.2 draw, content was:\n$c")
    }

    @Test
    fun aFractionalBoxCentresItsImageToTheMillipoint() {
        val img = BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val c = content(render { image(png, width = 50.5.dp, height = 20.dp, fit = PhotoFit.Contain) })
        // Contain scale = min(50.5 / 100, 20 / 50) = 0.4 → 40 × 20, centred: x = 36 + (50.5 - 40) / 2 = 41.25.
        assertTrue("40 0 0 20 41.25 786 cm" in c, "expected the image centred at x = 41.25, content was:\n$c")
    }

    @Test
    fun aSmallFractionalCornerRadiusStillCurves() {
        val c = content(render { box(border = 1.dp, cornerRadius = 0.5.dp) { text("Example Ltd.") } })
        // ck = 0.5 × 0.5523 = 0.276 pt (whole-point rounding would make it 0: a chamfer). Right edge
        // x + w - r = 558.5, so the first corner's control point sits at 558.776.
        assertTrue("36.5 791 m 558.5 791 l 558.776 791 " in c, "expected a curved 0.5 pt corner, content was:\n$c")
    }

    @Test
    fun streamingMatchesRenderForFractionalDocuments() {
        val spec = pdfDocument(PageConfig(margins = PageMargins(all = 20.5.dp, bottom = 14.6.dp))) {
            footer(gap = 7.25.dp) { divider(thickness = 0.75.dp); text("Page ${page.number} / ${page.total}", TextStyle(fontSize = 7.5.sp)) }
            repeat(80) { text("Line $it", TextStyle(fontSize = 10.5.sp)); spacer(1.3.dp) }
        }
        val out = ByteArrayOutputStream()
        spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold())
        assertContentEquals(spec.render(regular(), bold()), out.toByteArray())
    }

    @Test
    fun invalidLengthsAreRejected() {
        assertFailsWith<IllegalArgumentException> { pdfDocument { spacer(Float.NaN.dp) } }
        assertFailsWith<IllegalArgumentException> { pdfDocument { spacer(Float.POSITIVE_INFINITY.dp) } }
        assertFailsWith<IllegalArgumentException> { pdfDocument { spacer(2_000_000.dp) } }
        assertFailsWith<IllegalArgumentException> { pdfDocument { text("x", TextStyle(fontSize = Float.NaN.sp)) }.render(regular(), bold()) }
    }
}
