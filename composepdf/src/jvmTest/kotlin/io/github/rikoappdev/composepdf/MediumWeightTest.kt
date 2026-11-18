package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.FontBook
import io.github.rikoappdev.composepdf.preview.PreviewText
import io.github.rikoappdev.composepdf.preview.previewPages
import io.github.rikoappdev.composepdf.render.TextOp
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FontWeight.Medium`: an optional third face embedded after Regular and Bold, and — without that
 * face — the Regular face, with output identical to `FontWeight.Normal`.
 */
class MediumWeightTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    private val cfg = PageConfig(pageNumbers = false)

    private fun line(weight: FontWeight) = pdfDocument(cfg) {
        text("Created in Example App", TextStyle(fontSize = 8.sp, fontWeight = weight, align = TextAlign.End))
    }

    private fun mixed() = pdfDocument(cfg) {
        text("Regular line", TextStyle(fontSize = 9.sp))
        text("Bold line", TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold))
        text("Created in Example App", TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Medium))
    }

    /** Decoded content stream of page 1. */
    private fun content(pdf: ByteArray): String =
        Loader.loadPDF(pdf).use { d -> d.getPage(0).contents.readBytes().decodeToString() }

    /** Resource name → base font name, for page 1. */
    private fun fonts(pdf: ByteArray): Map<String, String> = Loader.loadPDF(pdf).use { d ->
        val res = d.getPage(0).resources
        res.fontNames.associate { it.name to res.getFont(it).name }
    }

    @Test
    fun ordinalsOfNormalAndBoldAreUnchanged() {
        assertEquals(listOf(FontWeight.Normal, FontWeight.Bold, FontWeight.Medium), FontWeight.entries)
    }

    @Test
    fun mediumWithoutAFaceRendersExactlyLikeNormal() {
        assertContentEquals(line(FontWeight.Normal).render(regular(), bold()), line(FontWeight.Medium).render(regular(), bold()))
    }

    @Test
    fun anUnusedMediumFaceChangesNothing() {
        val doc = pdfDocument(cfg) {
            text("Regular line")
            text("Bold line", TextStyle(fontWeight = FontWeight.Bold))
        }
        assertContentEquals(doc.render(regular(), bold()), doc.render(regular(), bold(), mediumFontBytes = bold()))
    }

    @Test
    fun mediumFaceIsEmbeddedAfterRegularAndBold() {
        // The Bold bytes stand in as the Medium face: a distinct third font, not a reused one.
        val pdf = mixed().render(regular(), bold(), mediumFontBytes = bold())
        assertEquals(
            mapOf("F0" to "CPDFRG+NotoSans", "F1" to "CPDFBD+NotoSans", "F2" to "CPDFMD+NotoSans"),
            fonts(pdf),
        )
        val c = content(pdf)
        assertTrue("/F0 9 Tf" in c && "/F1 9 Tf" in c, "Regular and Bold runs keep F0 / F1, content was:\n$c")
        assertTrue("/F2 8 Tf" in c, "the Medium run must select F2, content was:\n$c")
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("Created in Example App" in text, "the Medium run must extract as text, got:\n$text")
    }

    @Test
    fun aMediumOnlyDocumentEmbedsOnlyTheMediumFace() {
        val pdf = line(FontWeight.Medium).render(regular(), bold(), mediumFontBytes = bold())
        assertEquals(mapOf("F0" to "CPDFMD+NotoSans"), fonts(pdf))
        val c = content(pdf)
        assertTrue("/F0 8 Tf" in c, "the Medium run must select F0, content was:\n$c")
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("Created in Example App" in text, "the Medium run must extract as text, got:\n$text")
    }

    @Test
    fun mediumTextWithAnEmojiFaceRendersTextAndEmoji() {
        val emoji = String(Character.toChars(SyntheticEmojiFont.EMOJI_CP))
        val doc = pdfDocument(cfg) {
            text("Created in Example App $emoji done", TextStyle(fontSize = 7.5.sp, fontWeight = FontWeight.Medium, align = TextAlign.End))
        }
        val pdf = doc.render(regular(), bold(), SyntheticEmojiFont.build(), mediumFontBytes = bold())
        assertEquals(mapOf("F0" to "CPDFMD+NotoSans"), fonts(pdf))
        Loader.loadPDF(pdf).use { d ->
            val res = d.getPage(0).resources
            assertTrue(res.xObjectNames.any { res.isImageXObject(it) }, "the emoji must be embedded as an image")
        }
        val c = content(pdf)
        assertTrue("/Im0 Do" in c, "the emoji image must be drawn, content was:\n$c")
        assertTrue("/F0 7.5 Tf" in c, "the Medium runs must select F0 at 7.5 pt, content was:\n$c")
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue("Created in Example App" in text && "done" in text, "the Medium runs must extract as text, got:\n$text")
    }

    @Test
    fun withoutAMediumFaceOnlyTwoFontsAreEmbedded() {
        val pdf = mixed().render(regular(), bold())
        assertEquals(mapOf("F0" to "CPDFRG+NotoSans", "F1" to "CPDFBD+NotoSans"), fonts(pdf))
    }

    @Test
    fun mediumTextIsMeasuredAndPlacedWithTheMediumFace() {
        // Stand in the Bold face as "Medium": right-aligned Medium text must then land exactly where Bold does.
        val book = FontBook(regular(), bold(), mediumBytes = bold())
        val medium = layout(line(FontWeight.Medium), book).single().ops.filterIsInstance<TextOp>().single()
        val boldOp = layout(line(FontWeight.Bold), FontBook(regular(), bold())).single().ops.filterIsInstance<TextOp>().single()
        assertEquals(FontWeight.Medium, medium.weight)
        assertEquals(boldOp.xMpt, medium.xMpt)
        assertContentEquals(boldOp.gids, medium.gids)
    }

    @Test
    fun streamingMatchesRenderWithAMediumFace() {
        val spec = mixed()
        val out = ByteArrayOutputStream()
        spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold(), mediumFontBytes = bold())
        assertContentEquals(spec.render(regular(), bold(), mediumFontBytes = bold()), out.toByteArray())
    }

    @Test
    fun previewCarriesTheFaceActuallyUsed() {
        fun mediumRun(medium: ByteArray?) = mixed().previewPages(regular(), bold(), medium).single().ops
            .filterIsInstance<PreviewText>().single { it.text == "Created in Example App" }
        val withFace = mediumRun(regular())
        assertEquals(FontWeight.Medium, withFace.fontWeight)
        assertFalse(withFace.bold)
        assertEquals(FontWeight.Normal, mediumRun(null).fontWeight)
        val boldRun = mixed().previewPages(regular(), bold()).single().ops.filterIsInstance<PreviewText>().single { it.text == "Bold line" }
        assertTrue(boldRun.bold)
        assertEquals(FontWeight.Bold, boldRun.fontWeight)
    }
}
