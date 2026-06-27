package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.TrueTypeFont
import io.github.rikoappdev.composepdf.image.isPng
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color as AwtColor
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end color-emoji test. A synthetic Apple-`sbix` emoji font (built in-memory, so no host font is
 * needed) maps one code point to a solid-green color bitmap. A document mixing normal text with that
 * emoji is rendered; PDFBox rasterizes the page and we assert both the green emoji bitmap and the black
 * text are present. Also checks determinism (same bytes twice) and the no-emoji fallback.
 */
class EmojiTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()
    private val emojiChar = String(Character.toChars(SyntheticEmojiFont.EMOJI_CP))

    @Test
    fun colorBitmapParsesFromSbix() {
        val font = TrueTypeFont(SyntheticEmojiFont.build())
        assertTrue(font.hasColorBitmaps, "synthetic sbix font should expose color bitmaps")
        val gid = font.gidForCodePoint(SyntheticEmojiFont.EMOJI_CP)
        assertEquals(1, gid, "emoji code point should map to gid 1")
        val png = font.colorPng(gid)
        assertNotNull(png, "gid 1 should have a color PNG")
        assertTrue(isPng(png), "extracted bytes should be a PNG")
        assertEquals(null, font.colorPng(0), ".notdef has no color bitmap")
    }

    @Test
    fun emojiRendersAsColorImageInPdf() {
        val emojiFont = SyntheticEmojiFont.build()
        val doc = pdfDocument(PageConfig(margin = 36.dp, pageNumbers = false)) {
            text("Status $emojiChar done", TextStyle(fontSize = 40.sp))
        }
        val pdf = doc.render(regular(), bold(), emojiFont)
        File("build").mkdirs()
        File("build/emoji-test.pdf").writeBytes(pdf)

        val (green, dark) = countGreenAndDark(pdf)
        assertTrue(green > 1500, "expected a green emoji bitmap region, got $green px")
        assertTrue(dark > 1500, "expected black text alongside the emoji, got $dark px")
    }

    @Test
    fun outputIsDeterministic() {
        val emojiFont = SyntheticEmojiFont.build()
        val build = {
            pdfDocument(PageConfig(margin = 36.dp)) { text("A $emojiChar B", TextStyle(fontSize = 24.sp)) }
                .render(regular(), bold(), emojiFont)
        }
        assertTrue(build().contentEquals(build()), "emoji render must be byte-for-byte deterministic")
    }

    @Test
    fun withoutEmojiFontThereIsNoColor() {
        val doc = pdfDocument(PageConfig(margin = 36.dp, pageNumbers = false)) {
            text("Status $emojiChar done", TextStyle(fontSize = 40.sp))
        }
        // No emoji font supplied: the code point falls back to the text face's .notdef — no green.
        val pdf = doc.render(regular(), bold())
        val (green, _) = countGreenAndDark(pdf)
        assertTrue(green < 200, "no emoji font should mean no green emoji bitmap, got $green px")
    }

    private fun countGreenAndDark(pdf: ByteArray): Pair<Int, Int> {
        Loader.loadPDF(pdf).use { d ->
            val img = PDFRenderer(d).renderImageWithDPI(0, 150f)
            var green = 0; var dark = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val c = AwtColor(img.getRGB(x, y))
                if (c.green > 150 && c.red < 130 && c.blue < 140) green++
                if (c.red < 60 && c.green < 60 && c.blue < 60) dark++
            }
            return green to dark
        }
    }
}
