package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.util.ByteReader

/**
 * Extracts per-glyph **color bitmap** PNGs from a color-emoji font. Supports the two bitmap formats
 * real emoji fonts actually ship:
 *
 *  - **`sbix`** — Apple Color Emoji: one or more PNG "strikes" per ppem.
 *  - **`CBLC` + `CBDT`** — Google/Noto Color Emoji (the classic large `NotoColorEmoji.ttf`): embedded
 *    bitmap locator + data, image formats 17/18/19 carry PNG.
 *
 * It returns the **raw PNG bytes** for a glyph id; the caller decodes them with the engine's existing
 * PNG pipeline and embeds the result as a normal image XObject. Color **vector** formats (`COLR`/`CPAL`,
 * e.g. Segoe UI Emoji and Noto's newer COLRv1 build) are intentionally not handled here — they need an
 * outline rasterizer; documents using a vector-color emoji font fall back to the text `.notdef`.
 *
 * Everything is defensive: any malformed or unsupported structure yields `null` for that glyph (or for
 * the whole table) rather than throwing, so a hostile font degrades to "no emoji", never a crash.
 */
internal class ColorBitmaps private constructor(
    private val resolve: (Int) -> ByteArray?,
) {
    /** Raw PNG bytes for [gid], or null if this glyph has no color bitmap. */
    fun pngFor(gid: Int): ByteArray? = if (gid <= 0) null else runCatching { resolve(gid) }.getOrNull()

    companion object {
        private const val PNG_TAG = 0x706E6720 // 'png ' graphicType used by sbix glyph data

        /** Builds a resolver from whichever color-bitmap table the font has, or null if none/usable. */
        fun parse(
            data: ByteArray,
            sbix: TableSpan?,
            cblc: TableSpan?,
            cbdt: TableSpan?,
            numGlyphs: Int,
        ): ColorBitmaps? {
            if (sbix != null) fromSbix(data, sbix, numGlyphs)?.let { return it }
            if (cblc != null && cbdt != null) fromCbdt(data, cblc, cbdt, numGlyphs)?.let { return it }
            return null
        }

        // --- Apple sbix ------------------------------------------------------------------------

        private fun fromSbix(data: ByteArray, sbix: TableSpan, numGlyphs: Int): ColorBitmaps? = runCatching {
            val r = ByteReader(data)
            val base = sbix.offset
            val end = sbix.offset + sbix.length
            // sbix header: version(2) flags(2) numStrikes(4) then numStrikes × strikeOffset(4) from base.
            val numStrikes = r.u32At(base + 4).toInt()
            if (numStrikes <= 0 || numStrikes > 256) return null
            // Largest ppem first → best quality; later strikes are fallbacks if a glyph is absent.
            val strikes = (0 until numStrikes)
                .mapNotNull { i ->
                    val so = base + r.u32At(base + 8 + i * 4).toInt()
                    if (so < base || so + 4 > end) null else so to r.u16At(so) // (strikeBase, ppem)
                }
                .sortedByDescending { it.second }
            if (strikes.isEmpty()) return null
            ColorBitmaps { gid ->
                if (gid < 0 || gid >= numGlyphs) return@ColorBitmaps null
                for ((sb, _) in strikes) {
                    // glyphDataOffsets: (numGlyphs+1) × u32 from strike base, starting after ppem+ppi.
                    val o1 = sb + 4 + gid * 4
                    if (o1 + 8 > data.size) continue
                    val gStart = sb + r.u32At(o1).toInt()
                    val gEnd = sb + r.u32At(o1 + 4).toInt()
                    if (gEnd <= gStart || gStart < 0 || gEnd > data.size) continue
                    // glyph record: originOffsetX(2) originOffsetY(2) graphicType(4) data…
                    if (gStart + 8 > gEnd) continue
                    if (r.u32At(gStart + 4).toInt() != PNG_TAG) continue // skip 'dupe'/'jpg '/'tiff'
                    return@ColorBitmaps data.copyOfRange(gStart + 8, gEnd)
                }
                null
            }
        }.getOrNull()

        // --- Google CBLC + CBDT ----------------------------------------------------------------

        private fun fromCbdt(data: ByteArray, cblc: TableSpan, cbdt: TableSpan, numGlyphs: Int): ColorBitmaps? =
            runCatching {
                val r = ByteReader(data)
                val cblcOff = cblc.offset
                // CBLC header: version(4) numSizes(4) then bitmapSizeTable[numSizes] × 48 bytes.
                val numSizes = r.u32At(cblcOff + 4).toInt()
                if (numSizes <= 0 || numSizes > 256) return null
                val sizes = ArrayList<Triple<Int, Int, Int>>() // (indexSubTableArrayOffset, numIndexSubTables, ppem)
                val ranges = ArrayList<Pair<Int, Int>>()        // (startGid, endGid) parallel to sizes
                for (i in 0 until numSizes) {
                    val b = cblcOff + 8 + i * 48
                    if (b + 48 > data.size) break
                    val istArray = cblcOff + r.u32At(b).toInt()
                    val numIst = r.u32At(b + 8).toInt()
                    val startGid = r.u16At(b + 40)
                    val endGid = r.u16At(b + 42)
                    val ppem = data[b + 44].toInt() and 0xFF // ppemX
                    if (numIst in 1..0xFFFF) {
                        sizes.add(Triple(istArray, numIst, ppem))
                        ranges.add(startGid to endGid)
                    }
                }
                if (sizes.isEmpty()) return null
                val order = sizes.indices.sortedByDescending { sizes[it].third } // largest ppem first
                ColorBitmaps { gid ->
                    if (gid < 0 || gid >= numGlyphs) return@ColorBitmaps null
                    for (idx in order) {
                        val (istArray, numIst, _) = sizes[idx]
                        val (sg, eg) = ranges[idx]
                        if (gid < sg || gid > eg) continue
                        cbdtPng(data, r, istArray, numIst, gid, cbdt.offset)?.let { return@ColorBitmaps it }
                    }
                    null
                }
            }.getOrNull()

        private fun cbdtPng(data: ByteArray, r: ByteReader, istArray: Int, numIst: Int, gid: Int, cbdtOff: Int): ByteArray? {
            // IndexSubTableArray: numIst × (firstGlyph u16, lastGlyph u16, additionalOffset u32 from istArray).
            for (i in 0 until numIst) {
                val e = istArray + i * 8
                if (e + 8 > data.size) return null
                val first = r.u16At(e); val last = r.u16At(e + 2)
                if (gid < first || gid > last) continue
                val istOff = istArray + r.u32At(e + 4).toInt()
                return readIndexSubTable(data, r, istOff, first, gid, cbdtOff)
            }
            return null
        }

        private fun readIndexSubTable(data: ByteArray, r: ByteReader, istOff: Int, firstGlyph: Int, gid: Int, cbdtOff: Int): ByteArray? {
            if (istOff < 0 || istOff + 8 > data.size) return null
            val indexFormat = r.u16At(istOff)
            val imageFormat = r.u16At(istOff + 2)
            val imageDataOffset = cbdtOff + r.u32At(istOff + 4).toInt()
            val rel = gid - firstGlyph
            val (glyphOff, glyphLen) = when (indexFormat) {
                1 -> { // variable metrics, u32 offset array
                    val o = istOff + 8 + rel * 4
                    if (o + 8 > data.size) return null
                    val a = r.u32At(o).toInt(); val b = r.u32At(o + 4).toInt()
                    (imageDataOffset + a) to (b - a)
                }
                2 -> { // constant metrics: imageSize(4) bigMetrics(8) then back-to-back images
                    val imageSize = r.u32At(istOff + 8).toInt()
                    (imageDataOffset + rel * imageSize) to imageSize
                }
                3 -> { // variable metrics, u16 offset array
                    val o = istOff + 8 + rel * 2
                    if (o + 4 > data.size) return null
                    val a = r.u16At(o); val b = r.u16At(o + 2)
                    (imageDataOffset + a) to (b - a)
                }
                else -> return null // formats 4/5 (sparse) unsupported
            }
            if (glyphLen <= 0 || glyphOff < 0 || glyphOff + glyphLen > data.size) return null
            return extractCbdtPng(data, glyphOff, glyphLen, imageFormat)
        }

        /** CBDT glyph image formats that carry PNG: 17 (small metrics), 18 (big metrics), 19 (none). */
        private fun extractCbdtPng(data: ByteArray, off: Int, len: Int, imageFormat: Int): ByteArray? {
            val headerLen = when (imageFormat) {
                17 -> 5  // smallGlyphMetrics
                18 -> 8  // bigGlyphMetrics
                19 -> 0
                else -> return null
            }
            val p = off + headerLen
            if (p + 4 > off + len || p + 4 > data.size) return null
            val r = ByteReader(data)
            val dataLen = r.u32At(p).toInt()
            val pngStart = p + 4
            if (dataLen <= 0 || pngStart + dataLen > data.size || pngStart + dataLen > off + len) return null
            return data.copyOfRange(pngStart, pngStart + dataLen)
        }
    }
}

/** Byte span (offset + length) of an sfnt table, used to locate color-bitmap tables in the file. */
internal class TableSpan(val offset: Int, val length: Int)
