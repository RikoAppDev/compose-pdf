package io.github.rikoappdev.composepdf.pdf

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
internal fun streamObject(dictBody: String, data: ByteArray, compress: Boolean = false): ByteArray {
    val payload = if (compress) zlibCompress(data) else data
    val b = ByteBuf(payload.size + 64)
    b.ascii("<< ")
    if (dictBody.isNotEmpty()) { b.ascii(dictBody); b.ascii(" ") }
    if (compress) b.ascii("/Filter /FlateDecode ")
    b.ascii("/Length ${payload.size} >>\nstream\n")
    b.bytes(payload)
    b.ascii("\nendstream")
    return b.toByteArray()
}

/**
 * Minimal PDF file assembler: collects indirect objects (numbered from 1 in insertion order),
 * then serializes header + bodies + cross-reference table + trailer.
 */
internal class PdfDoc {
    private val objects = ArrayList<ByteArray>()

    fun add(content: ByteArray): Int { objects.add(content); return objects.size }
    fun addDict(s: String): Int = add(asciiBytes(s))

    /** Reserve an object number to be filled later via [set] (for forward references). */
    fun reserve(): Int { objects.add(ByteArray(0)); return objects.size }
    fun set(num: Int, content: ByteArray) { objects[num - 1] = content }
    fun setDict(num: Int, s: String) = set(num, asciiBytes(s))

    fun build(rootObj: Int): ByteArray {
        val out = ByteBuf(64 * 1024)
        out.ascii("%PDF-1.7\n")
        // Binary marker comment so tools treat the file as binary.
        out.u8(0x25); out.u8(0xE2); out.u8(0xE3); out.u8(0xCF); out.u8(0xD3); out.u8(0x0A)

        val offsets = IntArray(objects.size + 1)
        for (i in 1..objects.size) {
            offsets[i] = out.size
            out.ascii("$i 0 obj\n")
            out.bytes(objects[i - 1])
            out.ascii("\nendobj\n")
        }

        val xrefPos = out.size
        out.ascii("xref\n0 ${objects.size + 1}\n")
        out.ascii("0000000000 65535 f \n")               // 20-byte free entry
        for (i in 1..objects.size) {
            out.ascii(offsets[i].toString().padStart(10, '0'))
            out.ascii(" 00000 n \n")                      // 20-byte in-use entry
        }
        out.ascii("trailer\n<< /Size ${objects.size + 1} /Root $rootObj 0 R >>\n")
        out.ascii("startxref\n$xrefPos\n%%EOF\n")
        return out.toByteArray()
    }
}
