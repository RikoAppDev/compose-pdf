package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.PngImageData
import io.github.rikoappdev.composepdf.pdf.buildVectorForm
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.RectOp
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.vector.parseVector
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * The render-scoped image table, the deduplicating image registry and the compaction that drops
 * images the final pages never draw. Synthetic data only — generated swatches and a placeholder mark.
 */
class ImageTableTest {

    private fun png(w: Int, h: Int, rgb: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rgb)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private fun svgMark(): ByteArray = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="16" viewBox="0 0 24 16">
          <rect x="1" y="1" width="22" height="14" fill="#3355BB"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    private fun swatch(side: Int): EmbeddedImage = PngImageData(side, side, ByteArray(side * side * 3), null)

    private fun imageOp(index: Int, x: Int = 10) =
        ImageOp(x, 20, 30, 40, index, 5, 6, PhotoFit.Smart, HorizontalAlignment.End)

    private fun vectorOp(index: Int) =
        VectorOp(11, 21, 31, 41, index, 24, 16, PhotoFit.Contain, HorizontalAlignment.Start)

    private fun rect() = RectOp(0, 0, 1, 1, fill = Color(0xFF000000), stroke = null, strokeWidthPt = 0)

    /** A source that records how often it was pulled. */
    private class CountingSource(private val bytes: ByteArray) : PdfImageSource {
        var reads = 0
            private set

        override fun readBytes(): ByteArray {
            reads++
            return bytes
        }
    }

    @Test
    fun compactionIsANoOpWhenEveryImageIsUsed() {
        val images = listOf(swatch(2), swatch(3), buildVectorForm(parseVector(svgMark())))
        val p1 = Page(100, 100).apply { ops.add(imageOp(1)); ops.add(rect()) }
        val p2 = Page(100, 100).apply { ops.add(imageOp(0)); ops.add(vectorOp(2)); ops.add(imageOp(1)) }
        val before = listOf(p1, p2).map { it.ops.toList() }

        val compacted = compactImages(listOf(p1, p2), images)

        assertSame(images, compacted, "every image is drawn: the very same list comes back")
        listOf(p1, p2).forEachIndexed { i, page ->
            assertEquals(before[i].size, page.ops.size)
            before[i].forEachIndexed { k, op -> assertSame(op, page.ops[k], "ops are left untouched") }
        }
    }

    @Test
    fun compactionDropsUnreferencedAndRemaps() {
        val a = swatch(2)
        val unused = swatch(3)
        val mark = buildVectorForm(parseVector(svgMark()))
        val images = listOf(a, unused, mark)
        val text = rect()
        val p1 = Page(100, 100).apply { ops.add(text); ops.add(vectorOp(2)) }
        val p2 = Page(100, 100).apply { ops.add(imageOp(0, x = 7)); ops.add(vectorOp(2)) }

        val compacted = compactImages(listOf(p1, p2), images)

        assertEquals(listOf(a, mark), compacted, "the unused image is dropped, original order kept")
        assertSame(text, p1.ops[0], "non-image ops are untouched")
        val v1 = assertIs<VectorOp>(p1.ops[1])
        assertEquals(1, v1.imageIndex)
        val img = assertIs<ImageOp>(p2.ops[0])
        assertEquals(0, img.imageIndex)
        assertEquals(1, assertIs<VectorOp>(p2.ops[1]).imageIndex)
        // Everything but the index survives the rewrite.
        assertEquals(listOf(7, 20, 30, 40, 5, 6), listOf(img.xPt, img.yPt, img.wPt, img.hPt, img.intrinsicW, img.intrinsicH))
        assertEquals(PhotoFit.Smart, img.fit)
        assertEquals(HorizontalAlignment.End, img.align)
        assertEquals(listOf(11, 21, 31, 41, 24, 16), listOf(v1.xPt, v1.yPt, v1.wPt, v1.hPt, v1.intrinsicW, v1.intrinsicH))
        assertEquals(PhotoFit.Contain, v1.fit)
        assertEquals(HorizontalAlignment.Start, v1.align)
    }

    @Test
    fun dedupingRegistryReturnsOneIndexForSameInstanceAndForEqualContent() {
        val base = swatch(4)
        val table = RenderImageTable(listOf(base))
        val registry = DedupingImageRegistry(table)
        val logo = png(8, 6, 0x3366CC)

        val first = registry.image(PdfImageSource { logo }, logo)
        val sameInstance = registry.image(PdfImageSource { logo }, logo)
        val copy = logo.copyOf()
        val equalContent = registry.image(PdfImageSource { copy }, copy)

        assertEquals(1, first.index, "appended after the document's own image")
        assertEquals(8, first.intrinsicW)
        assertEquals(6, first.intrinsicH)
        assertEquals(first.index, sameInstance.index)
        assertEquals(first.index, equalContent.index)
        assertEquals(2, table.all.size, "one table entry for the logo")

        val other = registry.image(PdfImageSource { png(8, 6, 0xCC6633) }, Any())
        assertNotEquals(first.index, other.index)
        assertEquals(2, other.index)
        assertEquals(3, table.all.size)

        val mark = svgMark()
        val v1 = registry.vector(mark)
        val v2 = registry.vector(mark)
        val v3 = registry.vector(mark.copyOf())
        assertEquals(3, v1.index)
        assertEquals(listOf(3, 3), listOf(v2.index, v3.index), "a vector dedupes the same way")
        assertEquals(listOf(24, 16), listOf(v1.intrinsicW, v1.intrinsicH))
        assertEquals(4, table.all.size)
        assertSame(base, table.all[0], "the document's own image stays first")
    }

    @Test
    fun dedupingRegistryReadsAHoistedSourceOnce() {
        val table = RenderImageTable(emptyList())
        val registry = DedupingImageRegistry(table)
        val source = CountingSource(png(10, 10, 0x22AA44))

        val indices = List(5) { registry.image(source, source).index }

        assertEquals(1, source.reads, "a hoisted source is pulled once, then recognised by identity")
        assertEquals(listOf(0, 0, 0, 0, 0), indices)
        assertEquals(1, table.all.size)
    }

    @Test
    fun dedupingRegistryRecognisesAByteArrayIdentityWithoutReading() {
        val table = RenderImageTable(emptyList())
        val registry = DedupingImageRegistry(table)
        val logo = png(8, 6, 0x3366CC)
        // Two distinct sources around the same array, as `image(bytes)` creates on every call.
        val first = CountingSource(logo)
        val second = CountingSource(logo)

        val a = registry.image(first, logo)
        val b = registry.image(second, logo)

        assertEquals(1, first.reads)
        assertEquals(0, second.reads, "the array is recognised by identity before its source is pulled")
        assertEquals(a.index, b.index)
        assertEquals(1, table.all.size)
    }

    @Test
    fun seededIdentitiesAreKnownWithoutReading() {
        val mark = svgMark()
        val form = buildVectorForm(parseVector(mark))
        val pixels = swatch(10)
        val table = RenderImageTable(listOf(form, pixels))
        val source = CountingSource(png(10, 10, 0x22AA44))
        val seed = KnownImages(
            rasters = listOf(source to RegisteredImage(1, 10, 10)),
            vectors = listOf(mark to RegisteredImage(0, 24, 16)),
        )
        val registry = DedupingImageRegistry(table, listOf(seed))

        val raster = registry.image(source, source)
        val vector = registry.vector(mark)

        assertEquals(0, source.reads, "a seeded source is never pulled")
        assertEquals(listOf(1, 10, 10), listOf(raster.index, raster.intrinsicW, raster.intrinsicH))
        assertEquals(listOf(0, 24, 16), listOf(vector.index, vector.intrinsicW, vector.intrinsicH))
        assertEquals(2, table.all.size, "nothing appended")

        // A seed is identity only (no bytes retained): an equal copy is registered anew.
        val copy = registry.vector(mark.copyOf())
        assertEquals(2, copy.index)
        assertEquals(3, table.all.size)
    }
}
