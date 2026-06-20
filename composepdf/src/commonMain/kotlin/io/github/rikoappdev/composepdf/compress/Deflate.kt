package io.github.rikoappdev.composepdf.compress

import io.github.rikoappdev.composepdf.util.ByteBuf

/**
 * Pure-Kotlin DEFLATE (RFC 1951) wrapped as a zlib stream (RFC 1950), suitable for a PDF
 * `/Filter /FlateDecode` stream.
 *
 * Uses a single fixed-Huffman block with LZ77 back-references found via a hash chain. Fixed
 * Huffman (no dynamic table) keeps the encoder small and — because everything is integer math in
 * shared code — the compressed bytes are **identical on every platform**, which strengthens the
 * library's cross-platform identity guarantee (even raw bytes match, not only glyph positions).
 *
 * This is not a maximal-ratio compressor; it targets the streams compose-pdf emits (subset font
 * programs, content streams, ToUnicode CMaps), which are small and compress well with fixed Huffman.
 */
internal fun zlibCompress(data: ByteArray): ByteArray {
    val out = ByteBuf(data.size / 2 + 16)
    // zlib header: CM=8/CINFO=7 (0x78), FLEVEL=default + FCHECK so (CMF*256+FLG) % 31 == 0 → 0x9C.
    out.u8(0x78)
    out.u8(0x9C)
    out.bytes(deflateFixed(data))
    val adler = adler32(data)
    out.u32(adler) // Adler-32 is stored big-endian
    return out.toByteArray()
}

// --- DEFLATE fixed-Huffman body -------------------------------------------------------------

private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val WSIZE = 32768
private const val WMASK = WSIZE - 1
private const val HASH_SIZE = 1 shl 15
private const val MAX_CHAIN = 128

// RFC 1951 §3.2.5 length codes 257..285 and distance codes 0..29.
private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
)
private val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)

/** Per-length lookup (index = length 3..258) into the code index, extra-bit count and code base. */
private val LEN_CODE_INDEX = IntArray(MAX_MATCH + 1)
private val LEN_CODE_EXTRA = IntArray(MAX_MATCH + 1)
private val LEN_CODE_BASE = IntArray(MAX_MATCH + 1)

private val deflateTablesInit: Unit = run {
    for (idx in LENGTH_BASE.indices) {
        val base = LENGTH_BASE[idx]
        val last = if (idx == LENGTH_BASE.size - 1) MAX_MATCH else LENGTH_BASE[idx + 1] - 1
        for (len in base..last) {
            LEN_CODE_INDEX[len] = idx
            LEN_CODE_EXTRA[len] = LENGTH_EXTRA[idx]
            LEN_CODE_BASE[len] = base
        }
    }
}

/** Accumulates bits LSB-first into bytes (the DEFLATE bit order). */
private class BitWriter(initial: Int) {
    val out = ByteBuf(initial)
    private var bitBuf = 0
    private var bitCount = 0

    /** Writes the low [count] bits of [value], least-significant bit first. */
    fun writeBits(value: Int, count: Int) {
        bitBuf = bitBuf or ((value and ((1 shl count) - 1)) shl bitCount)
        bitCount += count
        while (bitCount >= 8) {
            out.u8(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    /** Writes a Huffman [code] of [count] bits most-significant bit first (bit-reversed then LSB-first). */
    fun writeHuff(code: Int, count: Int) {
        var rev = 0
        var c = code
        for (i in 0 until count) { rev = (rev shl 1) or (c and 1); c = c ushr 1 }
        writeBits(rev, count)
    }

    fun finish(): ByteArray {
        if (bitCount > 0) { out.u8(bitBuf and 0xFF); bitBuf = 0; bitCount = 0 }
        return out.toByteArray()
    }
}

private fun deflateTables() = deflateTablesInit

private fun deflateFixed(data: ByteArray): ByteArray {
    deflateTables()
    val n = data.size
    val bw = BitWriter(n / 2 + 16)

    // Single final block, BTYPE=01 (fixed Huffman): BFINAL=1 then BTYPE=01, LSB-first.
    bw.writeBits(1, 1)
    bw.writeBits(1, 2)

    val head = IntArray(HASH_SIZE) { -1 }
    val prev = IntArray(WSIZE) { -1 }

    fun hash3(i: Int): Int {
        val a = data[i].toInt() and 0xFF
        val b = data[i + 1].toInt() and 0xFF
        val c = data[i + 2].toInt() and 0xFF
        return ((a shl 10) xor (b shl 5) xor c) and (HASH_SIZE - 1)
    }

    fun insert(pos: Int) {
        if (pos + MIN_MATCH <= n) {
            val h = hash3(pos)
            prev[pos and WMASK] = head[h]
            head[h] = pos
        }
    }

    fun writeLitLen(sym: Int) = when {
        sym <= 143 -> bw.writeHuff(0x30 + sym, 8)
        sym <= 255 -> bw.writeHuff(0x190 + (sym - 144), 9)
        sym <= 279 -> bw.writeHuff(sym - 256, 7)
        else -> bw.writeHuff(0xC0 + (sym - 280), 8)
    }

    fun writeMatch(len: Int, dist: Int) {
        val li = LEN_CODE_INDEX[len]
        writeLitLen(257 + li)
        val leb = LEN_CODE_EXTRA[len]
        if (leb > 0) bw.writeBits(len - LEN_CODE_BASE[len], leb)
        var di = DIST_BASE.size - 1
        while (DIST_BASE[di] > dist) di--
        bw.writeHuff(di, 5) // fixed distance codes are 5 bits, value == code index
        val deb = DIST_EXTRA[di]
        if (deb > 0) bw.writeBits(dist - DIST_BASE[di], deb)
    }

    var i = 0
    while (i < n) {
        var bestLen = 0
        var bestDist = 0
        if (i + MIN_MATCH <= n) {
            val maxLen = if (n - i < MAX_MATCH) n - i else MAX_MATCH
            var cand = head[hash3(i)]
            var chain = MAX_CHAIN
            while (cand >= 0 && chain-- > 0) {
                val dist = i - cand
                if (dist > WSIZE) break
                var l = 0
                while (l < maxLen && data[cand + l] == data[i + l]) l++
                if (l > bestLen) {
                    bestLen = l
                    bestDist = dist
                    if (l >= maxLen) break
                }
                cand = prev[cand and WMASK]
            }
        }
        if (bestLen >= MIN_MATCH) {
            writeMatch(bestLen, bestDist)
            val end = i + bestLen
            while (i < end) { insert(i); i++ }
        } else {
            writeLitLen(data[i].toInt() and 0xFF)
            insert(i)
            i++
        }
    }

    writeLitLen(256) // end-of-block
    return bw.finish()
}

// --- Adler-32 (RFC 1950) --------------------------------------------------------------------

private fun adler32(data: ByteArray): Int {
    val mod = 65521L
    val nmax = 5552 // largest run before a modulo is needed (zlib's constant)
    // Long accumulators: across a 5552-byte batch (b) can exceed the signed 32-bit range.
    var a = 1L
    var b = 0L
    var i = 0
    while (i < data.size) {
        val end = if (data.size - i < nmax) data.size else i + nmax
        while (i < end) { a += data[i].toInt() and 0xFF; b += a; i++ }
        a %= mod
        b %= mod
    }
    return ((b shl 16) or a).toInt()
}
