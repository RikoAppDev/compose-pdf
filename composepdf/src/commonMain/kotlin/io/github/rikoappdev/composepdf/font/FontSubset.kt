package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.util.ByteBuf

/** Result of subsetting: a standalone TrueType font + the glyph ids it retains. */
internal class FontSubset(
    val bytes: ByteArray,
    val retainedGids: Set<Int>,
)

/**
 * Produces a subset TrueType font that keeps the ORIGINAL glyph ids (so composite glyph
 * references and the PDF's Identity CIDToGIDMap stay valid) but includes glyph outlines only
 * for [requestedGids] plus their composite components plus .notdef. `loca`/`glyf` are rebuilt;
 * `head`/`hhea`/`maxp`/`hmtx` are copied verbatim.
 */
internal fun subsetFont(font: TrueTypeFont, requestedGids: Set<Int>): FontSubset {
    // 1. Closure over composite components (always retain .notdef = gid 0).
    val used = HashSet<Int>()
    val stack = ArrayDeque<Int>()
    stack.addLast(0)
    for (g in requestedGids) stack.addLast(g)
    while (stack.isNotEmpty()) {
        val g = stack.removeLast()
        if (g < 0 || g >= font.numGlyphs) continue
        if (!used.add(g)) continue
        if (font.isComposite(g)) for (c in font.compositeComponents(g)) stack.addLast(c)
    }

    val numGlyphs = font.numGlyphs

    // 2. Rebuild glyf + long loca, preserving glyph ids (unused glyphs become zero-length).
    val glyf = ByteBuf(8192)
    val locaOffsets = IntArray(numGlyphs + 1)
    for (gid in 0 until numGlyphs) {
        locaOffsets[gid] = glyf.size
        if (gid in used) {
            val gb = font.glyphBytes(gid)
            if (gb.isNotEmpty()) {
                glyf.bytes(gb)
                glyf.padTo(2) // 2-byte glyph alignment
            }
        }
    }
    locaOffsets[numGlyphs] = glyf.size
    val glyfBytes = glyf.toByteArray()

    val locaBuf = ByteBuf((numGlyphs + 1) * 4)
    for (off in locaOffsets) locaBuf.u32(off)

    // 3. Copy/patch the remaining required tables.
    val head = font.rawTable("head").copyOf().also {
        it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0   // checkSumAdjustment = 0
        it[50] = 0; it[51] = 1                          // indexToLocFormat = long
    }

    // 4. Assemble the sfnt (table directory entries must be sorted by tag).
    val outTables = mapOf(
        "glyf" to glyfBytes,
        "head" to head,
        "hhea" to font.rawTable("hhea"),
        "hmtx" to font.rawTable("hmtx"),
        "loca" to locaBuf.toByteArray(),
        "maxp" to font.rawTable("maxp"),
    )
    return FontSubset(assembleSfnt(outTables), used)
}

private fun assembleSfnt(tables: Map<String, ByteArray>): ByteArray {
    val tags = tables.keys.sorted()
    val n = tags.size
    var maxPow2 = 1; var entrySelector = 0
    while (maxPow2 * 2 <= n) { maxPow2 *= 2; entrySelector++ }
    val searchRange = maxPow2 * 16
    val rangeShift = n * 16 - searchRange

    val header = ByteBuf(12 + 16 * n)
    header.u32(0x00010000L)        // sfnt 1.0 = TrueType outlines
    header.u16(n)
    header.u16(searchRange)
    header.u16(entrySelector)
    header.u16(rangeShift)

    var offset = 12 + 16 * n
    val records = ByteBuf(16 * n)
    val padded = LinkedHashMap<String, ByteArray>()
    for (tag in tags) {
        val data = tables.getValue(tag)
        val padCount = (4 - data.size % 4) % 4
        val withPad = if (padCount == 0) data else data + ByteArray(padCount)
        padded[tag] = withPad
        records.ascii(tag)
        records.u32(checksum(withPad))
        records.u32(offset.toLong())
        records.u32(data.size.toLong())  // length = unpadded size
        offset += withPad.size
    }

    val out = ByteBuf(offset)
    out.bytes(header.toByteArray())
    out.bytes(records.toByteArray())
    for (tag in tags) out.bytes(padded.getValue(tag))
    return out.toByteArray()
}

private fun checksum(padded: ByteArray): Long {
    var sum = 0L
    var i = 0
    while (i < padded.size) {
        val w = ((padded[i].toLong() and 0xFF) shl 24) or
            ((padded[i + 1].toLong() and 0xFF) shl 16) or
            ((padded[i + 2].toLong() and 0xFF) shl 8) or
            (padded[i + 3].toLong() and 0xFF)
        sum = (sum + w) and 0xFFFFFFFFL
        i += 4
    }
    return sum
}
