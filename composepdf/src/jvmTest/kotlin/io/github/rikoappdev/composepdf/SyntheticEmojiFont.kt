package io.github.rikoappdev.composepdf

import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Builds a **minimal, self-contained color-emoji font** (Apple `sbix` flavour) entirely in memory, so
 * the emoji pipeline can be tested without depending on a host emoji font (Windows ships only a `COLR`
 * vector emoji face, which the engine intentionally doesn't use). The font maps a single code point to
 * one glyph whose color bitmap is a solid-color PNG; the test asserts that color reaches the rasterized
 * PDF. Pure big-endian sfnt assembly — only the tables [TrueTypeFont] + [ColorBitmaps] actually read.
 */
internal object SyntheticEmojiFont {

    /** A code point absent from Noto Sans (so it routes to the emoji face): GRINNING FACE U+1F600. */
    const val EMOJI_CP = 0x1F600

    /** Builds the font with one emoji glyph painted [color] (default a vivid green). */
    fun build(color: AwtColor = AwtColor(40, 190, 90)): ByteArray {
        val png = solidPng(128, 128, color)

        val head = bytes(54) { b ->
            u16(b, 18, 1000)                 // unitsPerEm
            s16(b, 36, 0); s16(b, 38, 0)     // xMin, yMin
            s16(b, 40, 1000); s16(b, 42, 1000) // xMax, yMax
            s16(b, 50, 0)                    // indexToLocFormat (short; no loca anyway)
        }
        val hhea = bytes(36) { b ->
            s16(b, 4, 800)                   // ascender
            s16(b, 6, -200)                  // descender
            u16(b, 34, 2)                    // numberOfHMetrics
        }
        val maxp = bytes(6) { b ->
            u32(b, 0, 0x00005000)            // version 0.5
            u16(b, 4, 2)                     // numGlyphs (.notdef + emoji)
        }
        val hmtx = bytes(8) { b ->
            u16(b, 0, 1000); s16(b, 2, 0)    // gid0 advance/lsb
            u16(b, 4, 1000); s16(b, 6, 0)    // gid1 advance/lsb
        }
        val cmap = buildCmap()
        val sbix = buildSbix(png)

        return assembleSfnt(
            listOf(
                "cmap" to cmap,
                "head" to head,
                "hhea" to hhea,
                "hmtx" to hmtx,
                "maxp" to maxp,
                "sbix" to sbix,
            )
        )
    }

    /** cmap with one format-12 (full-Unicode) subtable mapping [EMOJI_CP] → gid 1. */
    private fun buildCmap(): ByteArray {
        val sub = bytes(28) { b ->
            u16(b, 0, 12)                    // format 12
            u16(b, 2, 0)                     // reserved
            u32(b, 4, 28)                    // length
            u32(b, 8, 0)                     // language
            u32(b, 12, 1)                    // numGroups
            u32(b, 16, EMOJI_CP.toLong())    // startCharCode
            u32(b, 20, EMOJI_CP.toLong())    // endCharCode
            u32(b, 24, 1)                    // startGlyphID
        }
        val header = bytes(12) { b ->
            u16(b, 0, 0)                     // version
            u16(b, 2, 1)                     // numTables
            u16(b, 4, 3)                     // platformID (Windows)
            u16(b, 6, 10)                    // encodingID (UCS-4)
            u32(b, 8, 12)                    // offset to subtable
        }
        return header + sub
    }

    /** sbix with one 128-ppem strike: gid 0 empty, gid 1 = a `'png '` graphic. */
    private fun buildSbix(png: ByteArray): ByteArray {
        // Strike: ppem(2) ppi(2) glyphDataOffsets[numGlyphs+1=3](u32). Header = 16 bytes.
        val glyphBlobLen = 8 + png.size // originX(2) originY(2) graphicType(4) + png
        val strike = ByteArray(16 + glyphBlobLen)
        u16(strike, 0, 128)                  // ppem
        u16(strike, 2, 72)                   // ppi
        u32(strike, 4, 16)                   // glyphDataOffsets[0] (gid0 start)
        u32(strike, 8, 16)                   // glyphDataOffsets[1] (gid0 end = gid1 start → gid0 empty)
        u32(strike, 12, (16 + glyphBlobLen).toLong()) // glyphDataOffsets[2] (gid1 end)
        // gid1 blob at strike+16:
        s16(strike, 16, 0)                   // originOffsetX
        s16(strike, 18, 0)                   // originOffsetY
        strike[20] = 'p'.code.toByte(); strike[21] = 'n'.code.toByte()
        strike[22] = 'g'.code.toByte(); strike[23] = ' '.code.toByte() // graphicType 'png '
        png.copyInto(strike, 24)

        val header = ByteArray(12)
        u16(header, 0, 1)                    // version
        u16(header, 2, 1)                    // flags
        u32(header, 4, 1)                    // numStrikes
        u32(header, 8, 12)                   // strikeOffset[0] (from sbix start)
        return header + strike
    }

    private fun solidPng(w: Int, h: Int, color: AwtColor): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, w, h)
        g.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    // --- sfnt assembly + big-endian writers ------------------------------------------------------

    private fun assembleSfnt(tables: List<Pair<String, ByteArray>>): ByteArray {
        val sorted = tables.sortedBy { it.first }
        val n = sorted.size
        var maxPow2 = 1; var entrySelector = 0
        while (maxPow2 * 2 <= n) { maxPow2 *= 2; entrySelector++ }
        val searchRange = maxPow2 * 16

        val records = ByteArray(16 * n)
        var offset = 12 + 16 * n
        val padded = ArrayList<ByteArray>(n)
        for ((i, t) in sorted.withIndex()) {
            val data = t.second
            val pad = (4 - data.size % 4) % 4
            val withPad = if (pad == 0) data else data + ByteArray(pad)
            padded.add(withPad)
            val ro = i * 16
            for (k in 0 until 4) records[ro + k] = t.first[k].code.toByte()
            u32(records, ro + 4, 0)                       // checksum (not validated by the reader)
            u32(records, ro + 8, offset.toLong())
            u32(records, ro + 12, data.size.toLong())     // length = unpadded
            offset += withPad.size
        }

        val header = ByteArray(12)
        u32(header, 0, 0x00010000)                        // sfnt 1.0
        u16(header, 4, n)
        u16(header, 6, searchRange)
        u16(header, 8, entrySelector)
        u16(header, 10, n * 16 - searchRange)             // rangeShift

        val out = ByteArrayOutputStream()
        out.write(header); out.write(records)
        for (p in padded) out.write(p)
        return out.toByteArray()
    }

    private inline fun bytes(size: Int, fill: (ByteArray) -> Unit): ByteArray = ByteArray(size).also(fill)
    private fun u16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    private fun s16(b: ByteArray, o: Int, v: Int) = u16(b, o, v and 0xFFFF)
    private fun u32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
    private fun u32(b: ByteArray, o: Int, v: Int) = u32(b, o, v.toLong() and 0xFFFFFFFFL)
}
