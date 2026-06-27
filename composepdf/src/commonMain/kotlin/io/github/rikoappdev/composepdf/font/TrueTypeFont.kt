package io.github.rikoappdev.composepdf.font

import io.github.rikoappdev.composepdf.util.ByteReader

/**
 * Minimal TrueType/OpenType (glyf-based) font parser.
 *
 * Parses only what the vector PDF pipeline needs: global metrics (head/hhea/maxp),
 * per-glyph advances (hmtx), the Unicode→glyph map (cmap), and glyph outlines (loca/glyf)
 * for subsetting. Everything is integer math in font units, so results are identical on
 * every platform.
 */
internal class TrueTypeFont(val data: ByteArray) {

    private val r = ByteReader(data)
    private val tables = HashMap<String, TableRec>()

    val unitsPerEm: Int
    val indexToLocFormat: Int
    val numGlyphs: Int
    val numberOfHMetrics: Int
    val xMin: Int; val yMin: Int; val xMax: Int; val yMax: Int
    val ascender: Int; val descender: Int

    private val hmtxOffset: Int
    private val glyfOffset: Int
    /** loca[i]..loca[i+1) is the byte range of glyph i inside the glyf table (absolute offsets).
     *  Empty for color-bitmap fonts that ship no outlines (Apple/Noto emoji): they are addressed
     *  through the color-bitmap table, never the outline subsetter. */
    private val loca: IntArray

    private val cmap: CmapLookup?

    /** Color-bitmap strikes (sbix / CBLC+CBDT), present only for color-emoji faces. */
    private val color: ColorBitmaps?

    private class TableRec(val offset: Int, val length: Int)

    init {
        // sfnt header: version(4) numTables(2) searchRange(2) entrySelector(2) rangeShift(2)
        r.skip(4)
        val numTables = r.u16()
        r.skip(6)
        for (i in 0 until numTables) {
            val tag = r.tagAt(r.pos); r.skip(4)
            r.skip(4) // checksum
            val off = r.u32().toInt()
            val len = r.u32().toInt()
            tables[tag] = TableRec(off, len)
        }

        val head = req("head")
        unitsPerEm = r.u16At(head.offset + 18)
        xMin = r.s16At(head.offset + 36)
        yMin = r.s16At(head.offset + 38)
        xMax = r.s16At(head.offset + 40)
        yMax = r.s16At(head.offset + 42)
        indexToLocFormat = r.s16At(head.offset + 50)

        val hhea = req("hhea")
        ascender = r.s16At(hhea.offset + 4)
        descender = r.s16At(hhea.offset + 6)
        numberOfHMetrics = r.u16At(hhea.offset + 34)

        val maxp = req("maxp")
        numGlyphs = r.u16At(maxp.offset + 4)

        hmtxOffset = req("hmtx").offset

        // glyf/loca are required for outline subsetting (the text faces) but legitimately ABSENT from
        // color-bitmap emoji fonts (Apple/Noto), which carry no outlines. Tolerate their absence; the
        // subsetter is only ever run on the text faces, which always have them.
        val glyfRec = tables["glyf"]
        val locaRec = tables["loca"]
        if (glyfRec != null && locaRec != null) {
            glyfOffset = glyfRec.offset
            // loca: numGlyphs+1 offsets, short (×2) or long format.
            loca = IntArray(numGlyphs + 1)
            if (indexToLocFormat == 0) {
                for (i in 0..numGlyphs) loca[i] = glyfOffset + r.u16At(locaRec.offset + i * 2) * 2
            } else {
                for (i in 0..numGlyphs) loca[i] = glyfOffset + r.u32At(locaRec.offset + i * 4).toInt()
            }
        } else {
            glyfOffset = -1
            loca = IntArray(0)
        }

        // cmap is required for code-point lookup but legitimately absent from our subset fonts
        // (which are addressed purely by glyph id), so tolerate its absence.
        cmap = tables["cmap"]?.let { CmapLookup(data, it.offset) }

        color = ColorBitmaps.parse(
            data,
            tables["sbix"]?.let { TableSpan(it.offset, it.length) },
            tables["CBLC"]?.let { TableSpan(it.offset, it.length) },
            tables["CBDT"]?.let { TableSpan(it.offset, it.length) },
            numGlyphs,
        )
    }

    /** True if this face carries color bitmap glyphs (an emoji font). */
    val hasColorBitmaps: Boolean get() = color != null

    /** Raw PNG bytes of the color bitmap for [gid], or null if this glyph has no color bitmap. */
    fun colorPng(gid: Int): ByteArray? = color?.pngFor(gid)

    private fun req(tag: String): TableRec =
        tables[tag] ?: throw IllegalArgumentException("Font missing required table '$tag'")

    /** Raw bytes of a required table (used by the subsetter to copy head/hhea/maxp/hmtx verbatim). */
    fun rawTable(tag: String): ByteArray {
        val t = req(tag)
        return data.copyOfRange(t.offset, t.offset + t.length)
    }

    /** Glyph id for a Unicode code point, or 0 (.notdef) if unmapped. */
    fun gidForCodePoint(cp: Int): Int =
        (cmap ?: throw IllegalStateException("Font has no cmap table")).gid(cp)

    fun debugCmap(): String = cmap?.debug() ?: "(no cmap)"

    /** Advance width of [gid] in font units. */
    fun advanceWidth(gid: Int): Int {
        // maxOf(1, ...) guards a malformed hhea with numberOfHMetrics == 0 (which would read
        // before the hmtx table); behavior is unchanged for valid fonts (n >= 1, gid in range).
        val n = maxOf(1, numberOfHMetrics)
        val i = minOf(gid, n - 1).coerceAtLeast(0)
        return r.u16At(hmtxOffset + i * 4)
    }

    /** Raw glyph bytes from the glyf table (empty for whitespace/empty glyphs, or outline-less fonts). */
    fun glyphBytes(gid: Int): ByteArray {
        if (loca.isEmpty() || gid < 0 || gid >= numGlyphs) return ByteArray(0)
        val start = loca[gid]; val end = loca[gid + 1]
        return if (end <= start) ByteArray(0) else data.copyOfRange(start, end)
    }

    fun isComposite(gid: Int): Boolean {
        if (loca.isEmpty() || gid < 0 || gid >= numGlyphs) return false
        val start = loca[gid]; val end = loca[gid + 1]
        if (end - start < 2) return false
        return r.s16At(start) < 0 // numberOfContours < 0 => composite
    }

    /** Component glyph ids referenced by a composite glyph (empty for simple glyphs). */
    fun compositeComponents(gid: Int): List<Int> {
        if (!isComposite(gid)) return emptyList()
        val out = ArrayList<Int>(4)
        var p = loca[gid] + 10 // skip numberOfContours + bbox (5 × int16)
        while (true) {
            val flags = r.u16At(p); p += 2
            val glyphIndex = r.u16At(p); p += 2
            out.add(glyphIndex)
            // arg1/arg2: words (4 bytes) or bytes (2 bytes)
            p += if (flags and 0x0001 != 0) 4 else 2
            // transform
            p += when {
                flags and 0x0008 != 0 -> 2          // WE_HAVE_A_SCALE
                flags and 0x0040 != 0 -> 4          // WE_HAVE_AN_X_AND_Y_SCALE
                flags and 0x0080 != 0 -> 8          // WE_HAVE_A_TWO_BY_TWO
                else -> 0
            }
            if (flags and 0x0020 == 0) break        // no MORE_COMPONENTS
        }
        return out
    }
}

/** Resolves Unicode code points to glyph ids from a cmap table (formats 4 and 12). */
private class CmapLookup(private val data: ByteArray, cmapOffset: Int) {
    private val r = ByteReader(data)
    private var format = 0
    private var subOffset = 0

    // format 4 cache
    private var segCount = 0
    private var endCodesAt = 0
    private var startCodesAt = 0
    private var idDeltaAt = 0
    private var idRangeOffsetAt = 0

    init {
        // cmap header: version(2) numTables(2), then records platformID(2) encodingID(2) offset(4)
        val numSub = r.u16At(cmapOffset + 2)
        var best = -1; var bestScore = -1
        for (i in 0 until numSub) {
            val rec = cmapOffset + 4 + i * 8
            val plat = r.u16At(rec)
            val enc = r.u16At(rec + 2)
            val off = r.u32At(rec + 4).toInt()
            val fmt = r.u16At(cmapOffset + off)
            // Prefer full Unicode (3,10/0,4–6 fmt12) then BMP Unicode (3,1/0,3 fmt4).
            val score = when {
                fmt == 12 && (plat == 3 && enc == 10 || plat == 0) -> 100
                fmt == 4 && (plat == 3 && enc == 1 || plat == 0) -> 90
                fmt == 4 || fmt == 12 -> 50
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = cmapOffset + off }
        }
        require(best >= 0) { "Font has no usable Unicode cmap subtable" }
        subOffset = best
        format = r.u16At(best)
        if (format == 4) {
            val segX2 = r.u16At(best + 6)
            segCount = segX2 / 2
            endCodesAt = best + 14
            startCodesAt = endCodesAt + segX2 + 2 // +2 reservedPad
            idDeltaAt = startCodesAt + segX2
            idRangeOffsetAt = idDeltaAt + segX2
        }
    }

    fun gid(cp: Int): Int = when (format) {
        4 -> gid4(cp)
        12 -> gid12(cp)
        else -> 0
    }

    fun debug(): String = buildString {
        append("format=$format subOffset=$subOffset")
        if (format == 4) {
            append(" segCount=$segCount\n")
            for (i in 0 until minOf(segCount, 8)) {
                append("  seg$i end=${r.u16At(endCodesAt + i * 2)} start=${r.u16At(startCodesAt + i * 2)} ")
                append("delta=${r.s16At(idDeltaAt + i * 2)} rangeOff=${r.u16At(idRangeOffsetAt + i * 2)}\n")
            }
        } else if (format == 12) {
            val numGroups = r.u32At(subOffset + 12).toInt()
            append(" numGroups=$numGroups\n")
            for (i in 0 until minOf(numGroups, 8)) {
                val g = subOffset + 16 + i * 12
                append("  grp$i start=${r.u32At(g).toInt()} end=${r.u32At(g + 4).toInt()} startGid=${r.u32At(g + 8).toInt()}\n")
            }
        }
        append("  gid('a'=0x61)=${gid(0x61)} gid('P'=0x50)=${gid(0x50)} gid('š'=0x161)=${gid(0x161)} gid(' '=0x20)=${gid(0x20)}")
    }

    private fun gid4(cp: Int): Int {
        if (cp > 0xFFFF) return 0
        for (i in 0 until segCount) {
            val end = r.u16At(endCodesAt + i * 2)
            if (cp <= end) {
                val start = r.u16At(startCodesAt + i * 2)
                if (cp < start) return 0
                val idDelta = r.s16At(idDeltaAt + i * 2)
                val idRangeOffsetPos = idRangeOffsetAt + i * 2
                val idRangeOffset = r.u16At(idRangeOffsetPos)
                return if (idRangeOffset == 0) {
                    (cp + idDelta) and 0xFFFF
                } else {
                    val glyphAddr = idRangeOffsetPos + idRangeOffset + (cp - start) * 2
                    val g = r.u16At(glyphAddr)
                    if (g == 0) 0 else (g + idDelta) and 0xFFFF
                }
            }
        }
        return 0
    }

    private fun gid12(cp: Int): Int {
        // format 12: format(2) reserved(2) length(4) language(4) numGroups(4) then groups(12 each)
        val numGroups = r.u32At(subOffset + 12).toInt()
        var lo = 0; var hi = numGroups - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val g = subOffset + 16 + mid * 12
            val startC = r.u32At(g).toInt()
            val endC = r.u32At(g + 4).toInt()
            when {
                cp < startC -> hi = mid - 1
                cp > endC -> lo = mid + 1
                else -> return (r.u32At(g + 8).toInt() + (cp - startC))
            }
        }
        return 0
    }
}
