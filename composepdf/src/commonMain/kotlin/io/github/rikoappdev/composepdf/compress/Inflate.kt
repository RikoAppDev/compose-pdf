package io.github.rikoappdev.composepdf.compress

import io.github.rikoappdev.composepdf.util.ByteBuf

/**
 * Pure-Kotlin INFLATE (RFC 1951) plus a zlib (RFC 1950) unwrapper, the counterpart to
 * [zlibCompress]. The library deflates its own streams; PNG IDAT data is zlib-compressed by the
 * encoder that produced the PNG, so embedding a PNG requires decompressing it here first.
 *
 * Supports the three DEFLATE block types: stored (BTYPE=00), fixed Huffman (BTYPE=01) and dynamic
 * Huffman (BTYPE=10, with the code-length / literal-length / distance code tables). Everything is
 * integer math in shared code, so a given PNG decodes to byte-identical pixels on every platform.
 */

/** Unwraps a zlib stream (2-byte header, raw DEFLATE body, 4-byte Adler-32 trailer) and inflates it. */
internal fun zlibInflate(data: ByteArray): ByteArray {
    require(data.size >= 2) { "zlib stream too short" }
    val cmf = data[0].toInt() and 0xFF
    val flg = data[1].toInt() and 0xFF
    require((cmf and 0x0F) == 8) { "Unsupported zlib compression method ${cmf and 0x0F} (expected DEFLATE)" }
    require((flg and 0x20) == 0) { "zlib preset dictionary not supported" }
    require(((cmf shl 8) or flg) % 31 == 0) { "Invalid zlib header checksum" }
    // Body starts after the 2-byte header; the trailing 4-byte Adler-32 is ignored (PDF re-checksums).
    return inflateRaw(data, 2)
}

/** A canonical Huffman decode table built from per-symbol code lengths (RFC 1951 §3.2.2). */
private class HuffmanTable(lengths: IntArray) {
    private val counts: IntArray
    private val symbols: IntArray
    val maxBits: Int

    init {
        var max = 0
        for (l in lengths) if (l > max) max = l
        maxBits = max
        val cnt = IntArray(max + 1)
        for (l in lengths) if (l > 0) cnt[l]++
        // Build the sorted symbol list grouped by code length, in symbol order within each length.
        val offsets = IntArray(max + 2)
        for (len in 1..max) offsets[len + 1] = offsets[len] + cnt[len]
        val syms = IntArray(lengths.count { it > 0 })
        for (sym in lengths.indices) {
            val l = lengths[sym]
            if (l > 0) { syms[offsets[l]] = sym; offsets[l]++ }
        }
        counts = cnt
        symbols = syms
    }

    /** Decodes one symbol from [br] using the canonical Huffman algorithm. */
    fun decode(br: BitReader): Int {
        var code = 0
        var first = 0
        var index = 0
        var len = 1
        while (len <= maxBits) {
            code = code or br.readBit()
            val count = counts[len]
            if (code - first < count) return symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
            len++
        }
        throw IllegalStateException("Invalid Huffman code in DEFLATE stream")
    }
}

/** Reads bits least-significant-bit first within each byte (the DEFLATE bit order). */
private class BitReader(private val data: ByteArray, var bytePos: Int) {
    private var bitBuf = 0
    private var bitCount = 0

    fun readBit(): Int {
        if (bitCount == 0) {
            require(bytePos < data.size) { "Unexpected end of DEFLATE stream" }
            bitBuf = data[bytePos++].toInt() and 0xFF
            bitCount = 8
        }
        val bit = bitBuf and 1
        bitBuf = bitBuf ushr 1
        bitCount--
        return bit
    }

    /** Reads [n] bits LSB-first into an integer (n in 0..16, as DEFLATE never needs more at once). */
    fun readBits(n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or (readBit() shl i)
        return v
    }

    /** Drops any partial byte so the next read is byte-aligned (used before stored blocks). */
    fun alignToByte() { bitCount = 0; bitBuf = 0 }

    fun readByte(): Int {
        require(bytePos < data.size) { "Unexpected end of DEFLATE stream" }
        return data[bytePos++].toInt() and 0xFF
    }
}

// RFC 1951 §3.2.5 length/distance code tables (shared with the deflate side but duplicated here so
// the inflate path is self-contained and the file reads top-to-bottom).
private val LEN_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LEN_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
)
private val DIST_BASE_T = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DIST_EXTRA_T = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)
// Order in which code-length code lengths appear in a dynamic block (RFC 1951 §3.2.7).
private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

/** Inflates raw DEFLATE data beginning at [start] until the final block is consumed. */
internal fun inflateRaw(data: ByteArray, start: Int = 0): ByteArray {
    val br = BitReader(data, start)
    val out = ByteBuf(data.size * 4 + 64)
    var final = false
    while (!final) {
        final = br.readBit() == 1
        when (val type = br.readBits(2)) {
            0 -> inflateStored(br, out)
            1 -> inflateBlock(br, out, fixedLitLenTable, fixedDistTable)
            2 -> {
                val (lit, dist) = readDynamicTables(br)
                inflateBlock(br, out, lit, dist)
            }
            else -> throw IllegalStateException("Invalid DEFLATE block type $type")
        }
    }
    return out.toByteArray()
}

/** A stored (uncompressed) block: align, read LEN/NLEN, then copy LEN literal bytes. */
private fun inflateStored(br: BitReader, out: ByteBuf) {
    br.alignToByte()
    val len = br.readByte() or (br.readByte() shl 8)
    br.readByte(); br.readByte() // NLEN (one's complement of LEN) — not validated
    repeat(len) { out.u8(br.readByte()) }
}

/** Decodes a single Huffman-coded block (fixed or dynamic) into [out], resolving LZ77 back-refs. */
private fun inflateBlock(br: BitReader, out: ByteBuf, lit: HuffmanTable, dist: HuffmanTable) {
    while (true) {
        val sym = lit.decode(br)
        when {
            sym == 256 -> return // end of block
            sym < 256 -> out.u8(sym)
            else -> {
                val li = sym - 257
                require(li < LEN_BASE.size) { "Invalid length symbol $sym" }
                val length = LEN_BASE[li] + br.readBits(LEN_EXTRA[li])
                val ds = dist.decode(br)
                val distance = DIST_BASE_T[ds] + br.readBits(DIST_EXTRA_T[ds])
                copyBackReference(out, distance, length)
            }
        }
    }
}

/** Copies [length] bytes from [distance] back in the already-decoded output (LZ77; may overlap). */
private fun copyBackReference(out: ByteBuf, distance: Int, length: Int) {
    val from = out.size - distance
    require(from >= 0) { "Invalid back-reference distance $distance" }
    for (i in 0 until length) out.u8(out.byteAt(from + i))
}

/** Reads the dynamic-block code-length, literal/length and distance Huffman tables (RFC 1951 §3.2.7). */
private fun readDynamicTables(br: BitReader): Pair<HuffmanTable, HuffmanTable> {
    val hlit = br.readBits(5) + 257
    val hdist = br.readBits(5) + 1
    val hclen = br.readBits(4) + 4

    val clLengths = IntArray(19)
    for (i in 0 until hclen) clLengths[CL_ORDER[i]] = br.readBits(3)
    val clTable = HuffmanTable(clLengths)

    // The code-length code RLE-encodes the combined literal+distance length list.
    val all = IntArray(hlit + hdist)
    var i = 0
    while (i < all.size) {
        when (val code = clTable.decode(br)) {
            in 0..15 -> { all[i++] = code }
            16 -> { // copy previous length 3..6 times
                require(i > 0) { "Repeat code 16 with no previous length" }
                val prev = all[i - 1]
                val rep = 3 + br.readBits(2)
                repeat(rep) { if (i < all.size) all[i++] = prev }
            }
            17 -> { val rep = 3 + br.readBits(3); repeat(rep) { if (i < all.size) all[i++] = 0 } }
            18 -> { val rep = 11 + br.readBits(7); repeat(rep) { if (i < all.size) all[i++] = 0 } }
            else -> throw IllegalStateException("Invalid code-length code $code")
        }
    }
    val litLengths = all.copyOfRange(0, hlit)
    val distLengths = all.copyOfRange(hlit, hlit + hdist)
    return HuffmanTable(litLengths) to HuffmanTable(distLengths)
}

// Fixed Huffman tables (RFC 1951 §3.2.6): literal/length lengths are 8/9/7/8 across fixed ranges,
// and all 30 distance codes are 5 bits. Built once and reused.
private val fixedLitLenTable: HuffmanTable = run {
    val lengths = IntArray(288)
    for (s in 0..143) lengths[s] = 8
    for (s in 144..255) lengths[s] = 9
    for (s in 256..279) lengths[s] = 7
    for (s in 280..287) lengths[s] = 8
    HuffmanTable(lengths)
}
private val fixedDistTable: HuffmanTable = HuffmanTable(IntArray(30) { 5 })
