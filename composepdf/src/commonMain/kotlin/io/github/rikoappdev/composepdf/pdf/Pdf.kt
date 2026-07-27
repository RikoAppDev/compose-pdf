package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.PdfSink
import io.github.rikoappdev.composepdf.compress.zlibCompress
import io.github.rikoappdev.composepdf.util.ByteBuf

/** PDF syntax is ASCII; map each char to one byte (no UTF-8 expansion). */
internal fun asciiBytes(s: String): ByteArray {
    val b = ByteArray(s.length)
    for (i in s.indices) b[i] = (s[i].code and 0xFF).toByte()
    return b
}

/**
 * Builds a stream object body: `<< [dictBody] [/Filter /FlateDecode] /Length N >> stream … endstream`.
 *
 * When [compress] is true the payload is zlib-deflated and a `/Filter /FlateDecode` entry is added;
 * `/Length` then reflects the compressed size. Any caller-supplied `/Length1` (uncompressed font
 * program size) stays in [dictBody] and is left untouched, as required for embedded font programs.
 * Do not compress already-compressed payloads (e.g. JPEG `/DCTDecode` images).
 */
/** The dict + `stream` opener shared by [streamObject], [PdfDoc.addStreamRef] and
 *  [PdfDoc.addStreamLazy] — one builder so a referenced or lazily pulled payload emits
 *  byte-identically to a copied one. */
private fun streamHead(dictBody: String, payloadSize: Int, compress: Boolean): String = buildString {
    append("<< ")
    if (dictBody.isNotEmpty()) { append(dictBody); append(' ') }
    if (compress) append("/Filter /FlateDecode ")
    append("/Length $payloadSize >>\nstream\n")
}

private const val STREAM_TAIL = "\nendstream"

internal fun streamObject(dictBody: String, data: ByteArray, compress: Boolean = false): ByteArray {
    val payload = if (compress) zlibCompress(data) else data
    val head = streamHead(dictBody, payload.size, compress)
    // Exact-size buffer: a multi-MB image payload must not trigger the ByteBuf doubling copy (a
    // transient 2x-image-size contiguous allocation), and the exact fit makes toByteArray copy-free.
    val b = ByteBuf(head.length + payload.size + STREAM_TAIL.length)
    b.ascii(head)
    b.bytes(payload)
    b.ascii(STREAM_TAIL)
    return b.toByteArray()
}

/**
 * Minimal PDF file assembler: collects indirect objects (numbered from 1 in insertion order),
 * then serializes header + bodies + cross-reference table + trailer.
 */
internal class PdfDoc {
    /** An object body is either one owned array, or head/payload/tail where the payload is a
     *  REFERENCE to the caller's array ([Parts]) or is pulled on demand at write time ([LazyParts]).
     *  The reference form exists so a multi-MB JPEG is not duplicated into the object list — that copy
     *  used to double the peak memory of photo-heavy documents. The lazy form goes one step further:
     *  the payload never has to exist before the byte that precedes it is written, so a document's
     *  photos are held **one at a time** instead of all at once. */
    private sealed interface Obj
    private class Whole(val bytes: ByteArray) : Obj
    private class Parts(val head: ByteArray, val payload: ByteArray, val tail: ByteArray) : Obj
    private class LazyParts(
        val head: ByteArray,
        val payloadSize: Int,
        val payload: () -> ByteArray,
        val tail: ByteArray,
    ) : Obj

    private val objects = ArrayList<Obj>()

    fun add(content: ByteArray): Int { objects.add(Whole(content)); return objects.size }
    fun addDict(s: String): Int = add(asciiBytes(s))

    /** Stream object whose uncompressed [payload] is referenced, not copied — emits byte-identically
     *  to `add(streamObject(dictBody, payload))`. The payload must not be mutated until after
     *  [build]/[writeTo]. */
    fun addStreamRef(dictBody: String, payload: ByteArray): Int {
        objects.add(
            Parts(
                asciiBytes(streamHead(dictBody, payload.size, compress = false)),
                payload,
                asciiBytes("\nendstream"),
            )
        )
        return objects.size
    }

    /**
     * Stream object whose uncompressed payload is **pulled from [payload] at write time** and dropped
     * again immediately — emits byte-identically to `addStreamRef(dictBody, payload())`.
     *
     * [payloadSize] is the payload's exact length, measured beforehand; it is what `/Length` and every
     * downstream cross-reference offset are computed from, which is why the head can be written before
     * a single payload byte exists. [payload] is invoked at most **once per output pass** (the counting
     * pass of [build] uses [payloadSize] and never calls it), and the returned array is not retained.
     *
     * A provider that returns a different number of bytes than measured would shift every later object
     * off its recorded xref offset, so it fails loudly instead.
     */
    fun addStreamLazy(dictBody: String, payloadSize: Int, payload: () -> ByteArray): Int {
        objects.add(
            LazyParts(
                asciiBytes(streamHead(dictBody, payloadSize, compress = false)),
                payloadSize,
                payload,
                asciiBytes("\nendstream"),
            )
        )
        return objects.size
    }

    /** Reserve an object number to be filled later via [set] (for forward references). */
    fun reserve(): Int { objects.add(Whole(ByteArray(0))); return objects.size }
    fun set(num: Int, content: ByteArray) { objects[num - 1] = Whole(content) }
    fun setDict(num: Int, s: String) = set(num, asciiBytes(s))

    /**
     * Where one output pass sends its bytes. Splitting "here is an array" from "here is a payload of
     * known size you may or may not want to pull" is what lets [build]'s counting pass size the file
     * without materializing a single photo.
     */
    private interface Emitter {
        fun bytes(b: ByteArray)
        /** A payload of exactly [size] bytes, obtainable from [payload]. */
        fun lazyPayload(size: Int, payload: () -> ByteArray)
    }

    /** Pulls a lazy payload, checks it against the size the file was laid out for, hands it to [use],
     *  and keeps no reference to it. */
    private inline fun materialize(size: Int, payload: () -> ByteArray, use: (ByteArray) -> Unit) {
        val p = payload()
        require(p.size == size) {
            "PdfImageSource returned ${p.size} bytes but ${size} were measured earlier — a source must " +
                "return identical bytes on every read (the /Length and every xref offset after this " +
                "object were computed from the measured size)"
        }
        use(p)
    }

    /**
     * One strictly sequential pass over the whole file. Objects are written in insertion order with a
     * running byte offset, so the xref table and `startxref` need no back-patching — every transport
     * ([writeTo], both passes of [build]) emits exactly the same byte sequence.
     */
    private fun emit(rootObj: Int, out: Emitter) {
        // Long + explicit ceiling: streaming has no array-size backstop, so past 2 GB an Int would
        // wrap silently and emit 11-char xref entries (a corrupt fixed-width table) instead of failing.
        var pos = 0L
        fun advance(n: Int) {
            pos += n
            require(pos <= Int.MAX_VALUE) { "PDF exceeds the 2 GB xref addressing limit" }
        }
        fun ascii(s: String) { val b = asciiBytes(s); out.bytes(b); advance(b.size) }
        fun raw(b: ByteArray) { out.bytes(b); advance(b.size) }

        ascii("%PDF-1.7\n")
        // Binary marker comment so tools treat the file as binary.
        raw(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        val offsets = LongArray(objects.size + 1)
        for (i in 1..objects.size) {
            offsets[i] = pos
            ascii("$i 0 obj\n")
            when (val o = objects[i - 1]) {
                is Whole -> raw(o.bytes)
                is Parts -> { raw(o.head); raw(o.payload); raw(o.tail) }
                is LazyParts -> {
                    raw(o.head)
                    out.lazyPayload(o.payloadSize, o.payload)
                    advance(o.payloadSize)
                    raw(o.tail)
                }
            }
            ascii("\nendobj\n")
        }

        val xrefPos = pos
        ascii("xref\n0 ${objects.size + 1}\n")
        ascii("0000000000 65535 f \n")                    // 20-byte free entry
        for (i in 1..objects.size) {
            ascii(offsets[i].toString().padStart(10, '0'))
            ascii(" 00000 n \n")                          // 20-byte in-use entry
        }
        ascii("trailer\n<< /Size ${objects.size + 1} /Root $rootObj 0 R >>\n")
        ascii("startxref\n$xrefPos\n%%EOF\n")
    }

    /**
     * Streams the complete file into [sink]. Lazy payloads are pulled one at a time, written and
     * released, so a photo-heavy document costs one photo of memory rather than all of them.
     */
    fun writeTo(rootObj: Int, sink: PdfSink) {
        emit(rootObj, object : Emitter {
            override fun bytes(b: ByteArray) { sink.write(b, 0, b.size) }
            override fun lazyPayload(size: Int, payload: () -> ByteArray) =
                materialize(size, payload) { sink.write(it, 0, it.size) }
        })
    }

    /**
     * Assembles the file as one exact-size array via two [emit] passes: a counting pass sizes the
     * allocation, a copying pass fills it. No growth doublings, no trailing trim copy — the peak here
     * used to be ~3x the file size (doubled backing + final copy), the very allocation that OOM'd
     * photo-heavy documents on Android's 256 MB heap.
     *
     * The counting pass only needs each lazy payload's *size*, so it never pulls one: a document's
     * image sources are read exactly once per `build()`, in the copying pass.
     */
    fun build(rootObj: Int): ByteArray {
        var total = 0
        emit(rootObj, object : Emitter {
            override fun bytes(b: ByteArray) { total += b.size }
            override fun lazyPayload(size: Int, payload: () -> ByteArray) { total += size }
        })
        val out = ByteArray(total)
        var pos = 0
        emit(rootObj, object : Emitter {
            override fun bytes(b: ByteArray) { b.copyInto(out, pos); pos += b.size }
            override fun lazyPayload(size: Int, payload: () -> ByteArray) =
                materialize(size, payload) { it.copyInto(out, pos); pos += size }
        })
        return out
    }
}
