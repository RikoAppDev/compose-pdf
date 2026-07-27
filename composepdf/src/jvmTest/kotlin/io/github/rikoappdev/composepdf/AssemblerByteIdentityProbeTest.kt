package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.compress.zlibCompress
import io.github.rikoappdev.composepdf.pdf.PdfDoc
import io.github.rikoappdev.composepdf.pdf.streamObject
import io.github.rikoappdev.composepdf.util.ByteBuf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-identity gate for the file assembler itself. The library's hard invariant is that a given
 * document always serializes to the same bytes, so the exact-size two-pass [PdfDoc.build] and the
 * exact-size [streamObject] must emit what the previous single-pass, growable-[ByteBuf]
 * implementations emitted. Those previous implementations are reproduced verbatim below (`legacy*`)
 * and diffed against the current ones, and the streamed file's own xref offsets are re-derived from
 * the bytes so a mis-counted header/marker/padding byte cannot pass unnoticed.
 *
 * Synthetic data only.
 */
class AssemblerByteIdentityProbeTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    // --- verbatim pre-change implementations ---------------------------------------------------

    /** The assembler as it stood before the exact-size rewrite (single pass over a growable buffer). */
    private fun legacyBuild(objects: List<ByteArray>, rootObj: Int): ByteArray {
        val out = ByteBuf(64 * 1024)
        out.ascii("%PDF-1.7\n")
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
        out.ascii("0000000000 65535 f \n")
        for (i in 1..objects.size) {
            out.ascii(offsets[i].toString().padStart(10, '0'))
            out.ascii(" 00000 n \n")
        }
        out.ascii("trailer\n<< /Size ${objects.size + 1} /Root $rootObj 0 R >>\n")
        out.ascii("startxref\n$xrefPos\n%%EOF\n")
        return out.toByteArray()
    }

    /** [streamObject] as it stood before the exact-size rewrite. */
    private fun legacyStreamObject(dictBody: String, data: ByteArray, compress: Boolean = false): ByteArray {
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

    // --- probes --------------------------------------------------------------------------------

    /**
     * Drives both assemblers over the same object lists, including the shapes that stress offset
     * formatting: an empty object, objects with embedded NULs and high bytes, and enough bulk to push
     * xref offsets from 2 to 7 decimal digits (the zero-padding is what makes every entry 20 bytes).
     */
    @Test
    fun buildMatchesLegacySinglePassAssembler() {
        val rnd = Random(42)
        val shapes: List<List<ByteArray>> = listOf(
            listOf(ByteArray(0)),
            listOf("<< /Type /Catalog /Pages 2 0 R >>".encodeToByteArray()),
            List(12) { i -> ByteArray(i * 37) { (it * 31 + i).toByte() } },
            // Bulk: several objects large enough that later offsets need 6-7 digits.
            List(9) { i -> ByteArray(200_000 + i * 1_111) { rnd.nextInt().toByte() } },
            // Byte values that must survive verbatim: 0x00, 0x0A, 0x25, 0xFF.
            listOf(byteArrayOf(0, 10, 0x25, -1), ByteArray(0), byteArrayOf(-1, -1, 0, 0)),
        )
        for ((n, objects) in shapes.withIndex()) {
            val doc = PdfDoc()
            for (o in objects) doc.add(o)
            val fresh = doc.build(rootObj = 1)
            assertContentEquals(legacyBuild(objects, 1), fresh, "assembler shape #$n must be byte-identical")
            // build() must also be repeatable: the two-pass design mutates nothing between passes.
            assertContentEquals(fresh, doc.build(rootObj = 1), "build() must be repeatable for shape #$n")
        }
    }

    /** The exact-size stream-object header/tail must lay out exactly as the growable version did. */
    @Test
    fun streamObjectMatchesLegacyLayout() {
        val rnd = Random(7)
        val payloads = listOf(
            ByteArray(0),
            byteArrayOf(0, 1, 2, 3),
            ByteArray(5000) { rnd.nextInt().toByte() },
            ByteArray(300_000) { (it % 251).toByte() },
        )
        val dicts = listOf(
            "",
            "/Length1 12345",
            "/Type /XObject /Subtype /Image /Width 640 /Height 480 /ColorSpace /DeviceRGB /BitsPerComponent 8",
        )
        for (d in dicts) for (p in payloads) for (c in listOf(false, true)) {
            assertContentEquals(
                legacyStreamObject(d, p, c),
                streamObject(d, p, c),
                "streamObject(dict='${d.take(20)}', payload=${p.size}, compress=$c) must match",
            )
        }
    }

    /**
     * Re-derives the cross-reference table from the streamed bytes: every xref entry must point at the
     * first byte of its `N 0 obj` marker, `startxref` must point at the literal `xref`, and every entry
     * must be exactly 20 bytes. This is what an off-by-one in the running `pos` would break.
     */
    @Test
    fun streamedXrefOffsetsResolveToTheirObjects() {
        val spec = pdfDocument(PageConfig(margin = 36.dp, pageNumbers = true)) {
            header { text("Band", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)) }
            table(columns = listOf(PdfColumn(2f, "Code"), PdfColumn(1f, "N", TextAlign.End))) {
                repeat(120) { row("ROW-$it", "$it") }
            }
            text("Trailing paragraph. ".repeat(50), TextStyle(fontSize = 9.sp))
        }
        val out = ByteArrayOutputStream()
        spec.renderTo({ b, o, l -> out.write(b, o, l) }, regular(), bold())
        val pdf = out.toByteArray()

        // Latin-1 keeps one char per byte, so string indices ARE byte offsets (UTF-8 would not).
        val text = String(pdf, Charsets.ISO_8859_1)
        val startxrefIdx = text.lastIndexOf("startxref\n")
        assertTrue(startxrefIdx > 0, "no startxref")
        val xrefPos = text.substring(startxrefIdx + 10).takeWhile { it.isDigit() }.toInt()
        assertEquals("xref\n", text.substring(xrefPos, xrefPos + 5), "startxref must point at the xref keyword")

        var p = xrefPos + 5
        val headerEnd = text.indexOf('\n', p)
        val (first, count) = text.substring(p, headerEnd).split(" ").map { it.toInt() }
        assertEquals(0, first)
        assertTrue(count > 10, "probe doc must have enough objects to be meaningful, had $count")
        p = headerEnd + 1
        assertEquals("0000000000 65535 f \n", text.substring(p, p + 20), "free entry must be 20 bytes")
        p += 20
        for (i in 1 until count) {
            val entry = text.substring(p, p + 20)
            assertEquals(20, entry.length)
            assertTrue(entry.endsWith(" 00000 n \n"), "entry $i malformed: '$entry'")
            val off = entry.substring(0, 10).toInt()
            assertEquals("$i 0 obj\n", text.substring(off, off + "$i 0 obj\n".length), "xref entry $i mis-points")
            p += 20
        }
        assertEquals("%PDF-1.7\n", text.substring(0, 9), "header must be 9 bytes before the binary marker")
        assertEquals(
            listOf(0x25, 0xE2, 0xE3, 0xCF, 0xD3, 0x0A),
            (9 until 15).map { pdf[it].toInt() and 0xFF },
            "binary marker comment must be the next 6 bytes",
        )
    }

    /**
     * The trim-free [ByteBuf.toByteArray] hands out the backing array when the buffer was sized
     * exactly. Pins that contract so a future edit that writes to a ByteBuf after handing it out gets
     * caught here rather than as a corrupted PDF.
     */
    @Test
    fun exactlySizedByteBufHandsOutItsBackingArray() {
        val exact = ByteBuf(32)
        repeat(32) { exact.u8(it) }
        val a = exact.toByteArray()
        val b = exact.toByteArray()
        assertEquals(32, a.size)
        assertTrue(a === b, "an exactly filled ByteBuf must hand out the same (backing) array")

        val slack = ByteBuf(64)
        repeat(32) { slack.u8(it) }
        val c = slack.toByteArray()
        assertEquals(32, c.size)
        assertTrue(c !== slack.toByteArray(), "a partially filled ByteBuf must still copy")

        // streamObject is always exactly sized, so its result aliases the buffer — the callers must
        // therefore treat it as owned. Verify the size arithmetic really is exact.
        val payload = ByteArray(1000) { it.toByte() }
        val obj = streamObject("/Type /Probe", payload)
        assertEquals("<< /Type /Probe /Length 1000 >>\nstream\n".length + 1000 + "\nendstream".length, obj.size)
    }
}
