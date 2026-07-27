package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.preview.previewPages
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PdfImageSource] is the *only* image path — `image(bytes, …)` wraps the array in a source that
 * returns it — so supplying a photo lazily must be indistinguishable in the output from supplying it
 * eagerly. This pins that (byte-for-byte, through both transports), the exact number of [PdfImageSource.readBytes]
 * calls the engine makes (the documented contract callers size their I/O against), and that a source
 * which does *not* return the same bytes twice fails loudly instead of shifting every later xref offset.
 *
 * Synthetic data only — generated noise images, placeholder labels.
 */
class LazyImageSourceTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /** Noisy synthetic JPEG — noise defeats compression so payloads are real multi-KB streams. */
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

    private fun svgMark(): ByteArray = """
        <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
          <rect x="1" y="1" width="30" height="30" fill="#3355BB"/>
          <circle cx="16" cy="16" r="9" fill="#FFDD44"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    private fun stream(spec: PdfDocumentSpec): ByteArray {
        val out = ByteArrayOutputStream()
        spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold())
        return out.toByteArray()
    }

    /** A source that records how often the engine pulled it. */
    private class CountingSource(private val bytes: ByteArray) : PdfImageSource {
        var reads = 0
            private set

        override fun readBytes(): ByteArray {
            reads++
            return bytes
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parity: lazy must be byte-for-byte what eager produces
    // ---------------------------------------------------------------------------------------------

    @Test
    fun lazyJpegProducesTheSameBytesAsTheEagerOverload() {
        val photo = noisyJpeg(240, 180, seed = 11L)

        fun eager() = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Lazy vs eager", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            image(photo, height = 90.dp)
        }

        fun lazy() = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Lazy vs eager", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            image(PdfImageSource { photo.copyOf() }, height = 90.dp)
        }

        val eagerRendered = eager().render(regular(), bold())
        assertTrue(eagerRendered.size > photo.size, "the photo must actually be embedded")

        assertContentEquals(eagerRendered, lazy().render(regular(), bold()), "render(): lazy must equal eager")
        assertContentEquals(eagerRendered, stream(eager()), "renderTo(): eager must equal render()")
        assertContentEquals(eagerRendered, stream(lazy()), "renderTo(): lazy must equal eager render()")
    }

    /**
     * A document mixing both supply styles across every image kind — eager JPEG, lazy JPEG, eager PNG
     * (with alpha, so an /SMask object lands between two lazy payloads), lazy PNG and a vector Form —
     * must equal the all-eager document. Object numbering, `/Length`s and xref offsets all have to
     * line up while lazy and non-lazy objects are interleaved.
     */
    @Test
    fun mixedEagerAndLazyImagesMatchTheAllEagerDocument() {
        val a = noisyJpeg(200, 150, seed = 21L)
        val b = noisyJpeg(160, 160, seed = 22L)
        val c = noisyJpeg(320, 120, seed = 23L)
        val logo = pngWithAlpha(48, 48)
        val mark = svgMark()

        fun doc(lazyOnes: Boolean) = pdfDocument(PageConfig(margin = 36.dp)) {
            header { row { cell(1f) { text("Mixed supply", TextStyle(fontSize = 10.sp)) } }; divider() }
            image(a, height = 70.dp)
            if (lazyOnes) image(PdfImageSource { b.copyOf() }, height = 70.dp) else image(b, height = 70.dp)
            if (lazyOnes) image(PdfImageSource { logo.copyOf() }, height = 24.dp) else image(logo, height = 24.dp)
            vector(mark, height = 24.dp)
            photoGrid(listOf(a, b, c), perRow = 3, cellHeight = 60.dp, fit = PhotoFit.Smart)
            if (lazyOnes) {
                photoGrid(listOf(c, a).map { bytes -> PdfImageSource { bytes.copyOf() } }, perRow = 2, cellHeight = 60.dp)
            } else {
                photoGrid(listOf(c, a), perRow = 2, cellHeight = 60.dp)
            }
            text("Trailing paragraph. ".repeat(20), TextStyle(fontSize = 9.sp))
        }

        val expected = doc(lazyOnes = false).render(regular(), bold())
        assertContentEquals(expected, doc(lazyOnes = true).render(regular(), bold()), "mixed render() parity")
        assertContentEquals(expected, stream(doc(lazyOnes = true)), "mixed renderTo() parity")
        assertContentEquals(expected, stream(doc(lazyOnes = false)), "all-eager renderTo() parity")
    }

    @Test
    fun photoGridOfSourcesMatchesPhotoGridOfByteArrays() {
        val photos = List(5) { noisyJpeg(180, 135, seed = 30L + it) }
        fun doc(lazy: Boolean) = pdfDocument(PageConfig(margin = 36.dp)) {
            if (lazy) {
                photoGrid(photos.map { p -> PdfImageSource { p.copyOf() } }, perRow = 3, cellHeight = 72.dp, fit = PhotoFit.Smart)
            } else {
                photoGrid(photos, perRow = 3, cellHeight = 72.dp, fit = PhotoFit.Smart)
            }
        }
        assertContentEquals(doc(false).render(regular(), bold()), doc(true).render(regular(), bold()))
    }

    // ---------------------------------------------------------------------------------------------
    // The documented call-count contract
    // ---------------------------------------------------------------------------------------------

    /**
     * Pins the numbers `PdfImageSource`'s KDoc promises: one probe read when the DSL records the
     * image, then exactly **one** read per render pass — `render()` included, whose counting pass
     * sizes the file from the measured payload length and must never pull a photo.
     */
    @Test
    fun jpegSourceIsReadOnceAtDslTimeAndOncePerRenderPass() {
        val photo = noisyJpeg(200, 150, seed = 41L)
        val source = CountingSource(photo)

        val spec = pdfDocument(PageConfig(margin = 36.dp)) {
            image(source, height = 80.dp)
        }
        assertEquals(1, source.reads, "building the spec must probe the source exactly once")

        spec.render(regular(), bold())
        assertEquals(2, source.reads, "render() must pull the payload exactly once (counting pass pulls nothing)")

        spec.render(regular(), bold())
        assertEquals(3, source.reads, "a second render() must pull exactly once more")

        stream(spec)
        assertEquals(4, source.reads, "renderTo() must pull the payload exactly once")

        spec.previewPages(regular(), bold())
        assertEquals(5, source.reads, "previewPages() pulls the encoded bytes once per image")
    }

    /** PNG is decoded (and its deflated `/Length` fixed) at DSL time, so its source is read once, ever. */
    @Test
    fun pngSourceIsReadOnlyAtDslTime() {
        val source = CountingSource(pngWithAlpha(40, 40))
        val spec = pdfDocument(PageConfig(margin = 36.dp)) { image(source, height = 20.dp) }
        assertEquals(1, source.reads, "PNG is decoded eagerly: one read")

        spec.render(regular(), bold())
        stream(spec)
        assertEquals(1, source.reads, "a decoded PNG must never be pulled again")
    }

    /** Every photo in a grid gets pulled, once each, per pass — no accidental sharing or caching. */
    @Test
    fun everyPhotoInAGridIsPulledExactlyOncePerPass() {
        val sources = List(7) { CountingSource(noisyJpeg(160, 120, seed = 50L + it)) }
        val spec = pdfDocument(PageConfig(margin = 36.dp)) {
            photoGrid(sources, perRow = 3, cellHeight = 64.dp, fit = PhotoFit.Smart)
        }
        assertTrue(sources.all { it.reads == 1 }, "DSL probe: ${sources.map { it.reads }}")
        stream(spec)
        assertTrue(sources.all { it.reads == 2 }, "one pull per photo per pass: ${sources.map { it.reads }}")
    }

    // ---------------------------------------------------------------------------------------------
    // A non-deterministic source must fail loudly, never corrupt the file
    // ---------------------------------------------------------------------------------------------

    /**
     * `/Length` and every cross-reference offset after an image are computed from the payload size
     * measured at DSL time. A source that later hands back a different number of bytes would slide the
     * whole tail of the file off its recorded offsets, so it must abort with a message that names the
     * culprit — through both transports.
     */
    @Test
    fun aSourceWhoseSecondReadDiffersInSizeFailsLoudly() {
        val photo = noisyJpeg(200, 150, seed = 61L)
        val longer = photo + byteArrayOf(0)

        fun flaky(): PdfImageSource {
            var call = 0
            return PdfImageSource { if (call++ == 0) photo else longer }
        }

        fun spec(source: PdfImageSource) = pdfDocument(PageConfig(margin = 36.dp)) {
            text("Before the image", TextStyle(fontSize = 10.sp))
            image(source, height = 80.dp)
            text("After the image", TextStyle(fontSize = 10.sp))
        }

        val fromRender = assertFailsWith<IllegalArgumentException> { spec(flaky()).render(regular(), bold()) }
        val fromStream = assertFailsWith<IllegalArgumentException> { stream(spec(flaky())) }
        for (e in listOf(fromRender, fromStream)) {
            val msg = e.message ?: ""
            assertTrue("PdfImageSource" in msg, "failure must name PdfImageSource, was: $msg")
            assertTrue("${longer.size}" in msg && "${photo.size}" in msg, "failure must report both sizes, was: $msg")
        }
    }

    /** A source that throws must surface its own exception, not a corrupted document. */
    @Test
    fun aSourceThatThrowsPropagates() {
        val photo = noisyJpeg(120, 90, seed = 71L)
        var call = 0
        val source = PdfImageSource {
            if (call++ == 0) photo else throw IllegalStateException("photo file vanished")
        }
        val spec = pdfDocument(PageConfig(margin = 36.dp)) { image(source, height = 40.dp) }
        val e = assertFailsWith<IllegalStateException> { spec.render(regular(), bold()) }
        assertEquals("photo file vanished", e.message)
    }
}
