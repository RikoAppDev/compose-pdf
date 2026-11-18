package io.github.rikoappdev.composepdf

/**
 * Where an image's bytes come from — the read end the engine pulls a photo through, so a document
 * with any number of photos never has to hold them all at once.
 *
 * Implementations adapt whatever the platform stores images in: a file (`File(path).readBytes()` on
 * Android/JVM, `NSData(contentsOfFile:)` on iOS), a content-provider `InputStream`, a cache entry, a
 * `ByteArray` already in hand. This is the single internal image path — `image(bytes, …)` and
 * `photoGrid(List<ByteArray>, …)` are thin convenience overloads that wrap the array in a source that
 * returns it, so there is one mechanism, not a "streaming mode" beside a normal one.
 *
 * **When it is called.** The engine invokes [readBytes] **more than once** per document:
 *  - once at DSL time (`image(source, …)`), to detect the format and read the intrinsic size —
 *    for a JPEG the bytes are then **dropped immediately**, only `(width, height, components, size)`
 *    is kept;
 *  - once per render pass — `render()` and `renderTo()` each pull the payload exactly once, write it,
 *    and release it before moving to the next image;
 *  - once more per `previewPages()` call, which needs the encoded bytes to draw the photo on screen.
 *
 * So a JPEG costs one photo's worth of memory at a time, not one per photo in the document. PNG and
 * vector images are still decoded eagerly at DSL time (see `image`), so their source is read once and
 * the decoded pixels are what the spec holds.
 *
 * These read counts hold for the document body and for **static** header/footer bands. Inside a
 * **page-aware** band (one that reads `BandScope.page`) an image is read (and, for PNG/vector,
 * decoded) when the band is first built in a render, unless that same [PdfImageSource] / `ByteArray`
 * instance was already seen (the build-time probe counts as seen); a lambda that creates a new source
 * on every run therefore re-reads it. Hoist the bytes or source out of the band lambda. The registry
 * that deduplicates band images keeps the raw bytes of each distinct band image for the duration of
 * layout: fine for a logo, not for a different large photo on every page.
 *
 * **Determinism is a hard requirement.** Every [readBytes] call must return the *same* bytes.
 * The engine measures the payload once and emits `/Length` and the cross-reference offsets from that
 * measurement before the payload is written, so a source that changes between reads would desynchronize
 * the file. A changed **length** is caught and fails loudly; a same-length change is not detected and
 * silently substitutes the image content. Do not point a source at a file that a background job may
 * rewrite, and do not return a buffer you keep mutating.
 *
 * The returned array becomes the engine's for the duration of the call chain and is released as soon
 * as the object is written — do not retain, share or mutate it afterwards, and do not hand out the
 * same mutable buffer to two sources.
 */
fun interface PdfImageSource {
    /** Reads the complete, unmodified image file (JPEG/PNG/vector XML bytes). */
    fun readBytes(): ByteArray
}
