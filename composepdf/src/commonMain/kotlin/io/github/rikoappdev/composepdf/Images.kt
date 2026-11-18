package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.image.decodePng
import io.github.rikoappdev.composepdf.image.isJpeg
import io.github.rikoappdev.composepdf.image.isPng
import io.github.rikoappdev.composepdf.image.parseJpeg
import io.github.rikoappdev.composepdf.pdf.EmbeddedImage
import io.github.rikoappdev.composepdf.pdf.JpegImage
import io.github.rikoappdev.composepdf.pdf.PngImageData
import io.github.rikoappdev.composepdf.pdf.VectorForm
import io.github.rikoappdev.composepdf.pdf.buildVectorForm
import io.github.rikoappdev.composepdf.render.ImageOp
import io.github.rikoappdev.composepdf.render.Page
import io.github.rikoappdev.composepdf.render.VectorOp
import io.github.rikoappdev.composepdf.vector.isVectorXml
import io.github.rikoappdev.composepdf.vector.parseVector

/**
 * The images of one render (or preview): seeded with the document's own images, then extended by
 * whatever the render discovers — color-emoji bitmaps and page-aware band images. Every index an
 * [ImageOp] / [VectorOp] carries points into [all], so indices stay consistent no matter who added
 * the image. The document's own list is copied, never mutated, so a spec can be rendered again.
 */
internal class RenderImageTable(base: List<EmbeddedImage>) {
    private val entries = ArrayList(base)

    /** Live view: the document's images first, then everything [add]ed, in order. */
    val all: List<EmbeddedImage> get() = entries

    /** Appends [image] and returns its index. */
    fun add(image: EmbeddedImage): Int {
        entries.add(image)
        return entries.lastIndex
    }
}

/** An image known to a registry: its index in the image list plus its intrinsic size (pixels / viewport). */
internal class RegisteredImage(val index: Int, val intrinsicW: Int, val intrinsicH: Int)

/** Where the DSL's `image` / `vector` calls put the images they decode. */
internal abstract class ImageRegistry {
    /** Registers the image read from [source]. [identity] is the caller's handle on it (the
     *  `PdfImageSource` or the `ByteArray`), which a registry may use to recognise it again. */
    abstract fun image(source: PdfImageSource, identity: Any): RegisteredImage

    /** Registers a VectorDrawable / SVG compiled from [bytes]. */
    abstract fun vector(bytes: ByteArray): RegisteredImage
}

/** The document's registry: every call appends one image to [list] — no deduplication. */
internal class DocumentImageRegistry(private val list: MutableList<EmbeddedImage>) : ImageRegistry() {
    override fun image(source: PdfImageSource, identity: Any): RegisteredImage {
        // Probe read: alive only for this call. The JPEG branch keeps nothing but four numbers, so a
        // 500-photo grid never holds more than one photo at a time.
        return append(decodeRaster(source.readBytes(), source))
    }

    override fun vector(bytes: ByteArray): RegisteredImage = append(decodeVector(bytes))

    private fun append(image: EmbeddedImage): RegisteredImage {
        list.add(image)
        return RegisteredImage(list.lastIndex, image.width, image.height)
    }
}

/**
 * Images known by identity alone: the caller's handle (the `PdfImageSource` or `ByteArray`) and the
 * image it was registered as. Holds those handles only — never bytes read from a source.
 */
internal class KnownImages(
    val rasters: List<Pair<Any, RegisteredImage>>,
    val vectors: List<Pair<Any, RegisteredImage>>,
)

/**
 * Passes every registration through to [inner] and records it by identity. Every band's build-time
 * probe registers through one, so a page-aware band's render can recognise the same instances later.
 */
internal class RecordingImageRegistry(private val inner: ImageRegistry) : ImageRegistry() {
    private val rasters = ArrayList<Pair<Any, RegisteredImage>>()
    private val vectors = ArrayList<Pair<Any, RegisteredImage>>()

    /** Everything registered so far. */
    fun known() = KnownImages(rasters.toList(), vectors.toList())

    override fun image(source: PdfImageSource, identity: Any): RegisteredImage =
        inner.image(source, identity).also { rasters.add(identity to it) }

    override fun vector(bytes: ByteArray): RegisteredImage =
        inner.vector(bytes).also { vectors.add(bytes to it) }
}

/**
 * A render-scoped registry that embeds each distinct image once, however often it is registered —
 * the same logo built into a band on every page becomes one image object.
 *
 * Identity first: an instance seen before is answered without reading it again. Then content: the
 * bytes are compared (hash bucket, then a full `contentEquals` against the retained bytes, so a
 * hash collision can never alias two different images). Only the first instance of each distinct
 * image is remembered by identity, so fresh arrays produced per page are not retained.
 *
 * [known] seeds the identities: images the bands' build-time probes already put in the table (no
 * bytes are kept for them, so they are recognised by identity only). When an identity is seeded more
 * than once, the first seed answers it.
 */
internal class DedupingImageRegistry(
    private val table: RenderImageTable,
    known: List<KnownImages> = emptyList(),
) : ImageRegistry() {
    private val rasters = Known()
    private val vectors = Known()

    init {
        for (k in known) {
            for ((identity, image) in k.rasters) rasters.rememberIdentity(identity, image)
            for ((identity, image) in k.vectors) vectors.rememberIdentity(identity, image)
        }
    }

    override fun image(source: PdfImageSource, identity: Any): RegisteredImage {
        rasters.byIdentity(identity)?.let { return it }
        val bytes = source.readBytes()
        rasters.byContent(bytes)?.let { return it }
        return add(decodeRaster(bytes, source)).also { rasters.remember(identity, bytes, it) }
    }

    override fun vector(bytes: ByteArray): RegisteredImage {
        vectors.byIdentity(bytes)?.let { return it }
        vectors.byContent(bytes)?.let { return it }
        return add(decodeVector(bytes)).also { vectors.remember(bytes, bytes, it) }
    }

    private fun add(image: EmbeddedImage) = RegisteredImage(table.add(image), image.width, image.height)

    /** Images of one kind seen so far, by identity and by content. Kept apart per kind so the same
     *  bytes can never resolve a raster call to a vector form or vice versa. */
    private class Known {
        private val identities = ArrayList<Pair<Any, RegisteredImage>>()
        private val byHash = HashMap<Int, MutableList<Pair<ByteArray, RegisteredImage>>>()

        fun byIdentity(identity: Any): RegisteredImage? = identities.firstOrNull { it.first === identity }?.second

        fun byContent(bytes: ByteArray): RegisteredImage? =
            byHash[bytes.contentHashCode()]?.firstOrNull { it.first.contentEquals(bytes) }?.second

        fun rememberIdentity(identity: Any, image: RegisteredImage) {
            identities.add(identity to image)
        }

        fun remember(identity: Any, bytes: ByteArray, image: RegisteredImage) {
            rememberIdentity(identity, image)
            byHash.getOrPut(bytes.contentHashCode()) { ArrayList(1) }.add(bytes to image)
        }
    }
}

/**
 * Turns the [bytes] read from [source] into an embeddable raster: a PNG is decoded to pixels now, a
 * JPEG keeps only its size facts and is pulled from [source] again when its object is written.
 */
private fun decodeRaster(bytes: ByteArray, source: PdfImageSource): EmbeddedImage = when {
    isPng(bytes) -> decodePng(bytes).let { PngImageData(it.width, it.height, it.rgb, it.alpha) }
    isJpeg(bytes) -> parseJpeg(bytes).let { JpegImage(it.width, it.height, it.components, bytes.size, source) }
    else -> throw IllegalArgumentException("Unsupported image format (expected JPEG or PNG)")
}

/** Compiles VectorDrawable / SVG [bytes] into a Form XObject. */
private fun decodeVector(bytes: ByteArray): VectorForm {
    require(isVectorXml(bytes)) { "Not a vector image (expected VectorDrawable or SVG XML)" }
    return buildVectorForm(parseVector(bytes))
}

/**
 * Drops the [images] no [ImageOp] / [VectorOp] on [pages] draws, so the output carries no unused
 * image objects — those a page-aware band's probe or provisional passes registered but the final
 * pages never draw, and those of a band replaced by a second `header {}` / `footer {}` call.
 * Returns [images] itself when every entry is drawn (the usual case without page-aware bands, whose
 * output is then unchanged). Otherwise the kept images stay in their original order and the ops in
 * `page.ops` are rewritten in place to the new indices.
 */
internal fun compactImages(pages: List<Page>, images: List<EmbeddedImage>): List<EmbeddedImage> {
    val used = BooleanArray(images.size)
    var usedCount = 0
    for (page in pages) for (op in page.ops) {
        val index = when (op) {
            is ImageOp -> op.imageIndex
            is VectorOp -> op.imageIndex
            else -> continue
        }
        if (!used[index]) {
            used[index] = true
            usedCount++
        }
    }
    if (usedCount == images.size) return images

    val newIndex = IntArray(images.size)
    val kept = ArrayList<EmbeddedImage>(usedCount)
    for (i in images.indices) if (used[i]) {
        newIndex[i] = kept.size
        kept.add(images[i])
    }
    for (page in pages) {
        val ops = page.ops
        for (k in ops.indices) when (val op = ops[k]) {
            is ImageOp -> if (newIndex[op.imageIndex] != op.imageIndex) ops[k] = op.withImageIndex(newIndex[op.imageIndex])
            is VectorOp -> if (newIndex[op.imageIndex] != op.imageIndex) ops[k] = op.withImageIndex(newIndex[op.imageIndex])
            else -> {}
        }
    }
    return kept
}
