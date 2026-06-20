package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.compress.zlibCompress
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Independent verification that the pure-Kotlin DEFLATE encoder produces valid zlib streams:
 * each compressed payload is round-tripped through the JVM's [Inflater] (a different, reference
 * implementation) and must reproduce the original bytes exactly.
 */
class DeflateTest {

    private fun inflate(zlib: ByteArray): ByteArray {
        val inflater = Inflater() // expects a zlib header, which zlibCompress emits
        inflater.setInput(zlib)
        val out = ArrayList<Byte>()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            for (i in 0 until n) out.add(buf[i])
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun roundTrip(data: ByteArray) {
        val compressed = zlibCompress(data)
        assertContentEquals(data, inflate(compressed), "deflate round-trip mismatch (size=${data.size})")
    }

    @Test
    fun roundTripsEdgeCases() {
        roundTrip(ByteArray(0))
        roundTrip(byteArrayOf(0))
        roundTrip("a".encodeToByteArray())
        roundTrip("ab".encodeToByteArray())
        roundTrip("abc".encodeToByteArray())
    }

    @Test
    fun roundTripsRepetitiveText() {
        val text = ("The quick brown fox jumps over the lazy dog. ").repeat(200).encodeToByteArray()
        roundTrip(text)
        // Highly repetitive input must compress well below half its size.
        assertTrue(
            zlibCompress(text).size < text.size / 2,
            "repetitive text should compress to <50%: was ${zlibCompress(text).size}/${text.size}",
        )
    }

    @Test
    fun roundTripsPseudoRandomBytes() {
        // Deterministic LCG so the test is reproducible; exercises the literal path.
        var seed = 0x1234_5678
        val data = ByteArray(20_000) {
            seed = seed * 1_103_515_245 + 12_345
            (seed ushr 16).toByte()
        }
        roundTrip(data)
    }

    @Test
    fun roundTripsLongRunsAndMaxMatches() {
        // A single repeated byte exercises max-length (258) matches and long back-references.
        roundTrip(ByteArray(50_000) { 0x41 })
        // Block of zeros then a long ascending pattern.
        roundTrip(ByteArray(40_000) { (it % 251).toByte() })
    }
}
