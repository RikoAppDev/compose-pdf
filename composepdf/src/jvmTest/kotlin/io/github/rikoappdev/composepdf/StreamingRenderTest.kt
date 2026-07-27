package io.github.rikoappdev.composepdf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * [PdfDocumentSpec.renderTo] must stream the **byte-for-byte identical** file that [render] returns —
 * streaming changes only the transport, never the bytes (renderTo exists so a photo-heavy report can
 * go straight to disk instead of allocating the whole multi-MB PDF, which OOM'd on Android's 256 MB
 * heap). Covers text + JPEG + PNG + emoji so every object-emission path runs through both transports.
 * Synthetic data only.
 */
class StreamingRenderTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /** Noisy synthetic JPEG — noise defeats compression so multi-KB payloads actually exercise big buffers. */
    private fun noisyJpeg(w: Int, h: Int, seed: Long): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(seed)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rnd.nextInt())
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", bos)
        return bos.toByteArray()
    }

    private fun pngWithAlpha(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 255 / w shl 24) or 0x3366CC)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private fun photoReport(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 36.dp)) {
        repeat(3) { day ->
            text("Day ${day + 1}", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            text("Summary row with some wrapped text that spans a few words", TextStyle(fontSize = 8.sp))
            photoGrid(
                List(4) { noisyJpeg(320, 240, seed = day * 10L + it) },
                perRow = 3,
                cellHeight = 72.dp,
                gap = 6.dp,
                fit = PhotoFit.Smart,
            )
            image(pngWithAlpha(64, 64), height = 24.dp)
            spacer(8.dp)
        }
    }

    @Test
    fun streamedBytesAreIdenticalToRenderedBytes() {
        // Build TWO specs from the same generator: image() records into the spec, so the same spec
        // instance must not be rendered twice with different books mutating shared state assumptions.
        val expected = photoReport().render(regular(), bold())

        val streamed = ByteArrayOutputStream(expected.size)
        photoReport().renderTo({ bytes, off, len -> streamed.write(bytes, off, len) }, regular(), bold())

        assertTrue(expected.size > 200_000, "report should be non-trivial, was ${expected.size} B")
        assertContentEquals(expected, streamed.toByteArray(), "renderTo must stream byte-identical output")
    }

    @Test
    fun streamedBytesAreIdenticalWithEmojiFace() {
        val emoji = SyntheticEmojiFont.build()
        val emojiChar = String(Character.toChars(SyntheticEmojiFont.EMOJI_CP))
        fun spec() = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Status $emojiChar done", TextStyle(fontSize = 14.sp))
            image(noisyJpeg(200, 150, seed = 7L), height = 40.dp)
        }
        val expected = spec().render(regular(), bold(), emoji)
        val streamed = ByteArrayOutputStream()
        spec().renderTo({ b, o, l -> streamed.write(b, o, l) }, regular(), bold(), emoji)
        assertContentEquals(expected, streamed.toByteArray())
    }

    @Test
    fun streamedFileIsAWellFormedPdf() {
        val out = ByteArrayOutputStream()
        photoReport().renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold())
        val bytes = out.toByteArray()
        val head = bytes.copyOfRange(0, 8).decodeToString()
        val tail = bytes.copyOfRange(bytes.size - 6, bytes.size).decodeToString()
        assertTrue(head.startsWith("%PDF-1.7"), "must start with the PDF header, was $head")
        assertTrue(tail.contains("%%EOF"), "must end with %%EOF, was $tail")
    }

    @Test
    fun progressReachesOneInStreamingMode() {
        val values = ArrayList<Float>()
        photoReport().renderTo({ _, _, _ -> }, regular(), bold(), onProgress = { values.add(it) })
        assertTrue(values.isNotEmpty() && values.last() == 1f, "onProgress must end at 1f, was $values")
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a }, "onProgress must be monotonic, was $values")
    }
}
