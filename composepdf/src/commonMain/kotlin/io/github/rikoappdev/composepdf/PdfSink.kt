package io.github.rikoappdev.composepdf

/**
 * Destination for streamed PDF bytes — the write end [PdfDocumentSpec.renderTo] pushes the document
 * into sequentially, front to back, exactly once.
 *
 * Implementations adapt whatever the platform writes to: a `FileOutputStream` on Android/JVM, an
 * `NSOutputStream`/file handle on iOS, a socket, a digest. The engine never seeks, never rewrites
 * earlier bytes, and calls [write] from the single thread `renderTo` runs on, so a plain unbuffered
 * adapter is correct (buffering is still a good idea for raw file descriptors).
 *
 * The streamed bytes are **byte-for-byte identical** to what `render()` returns for the same
 * document — streaming only changes how the bytes leave the engine, not what they are.
 *
 * This is the write end of the pair that makes documents unbounded: [PdfSink] keeps the finished file
 * from ever existing whole, [PdfImageSource] keeps the photos from ever existing whole. Either alone
 * still leaves the other as a ceiling.
 *
 * If [write] throws, the exception propagates out of `renderTo` and any bytes already delivered
 * stay wherever the sink put them — cleaning up a partial file is the caller's responsibility.
 *
 * Do not retain [bytes] beyond the call: the engine may reuse or free the array afterwards. Honor
 * [write]'s `offset`/`length` rather than writing the whole array — the engine currently always
 * passes full arrays (`offset = 0`), but that is an implementation detail, not a promise.
 */
fun interface PdfSink {
    /** Appends `length` bytes of [bytes] starting at [offset] to the output. */
    fun write(bytes: ByteArray, offset: Int, length: Int)
}
