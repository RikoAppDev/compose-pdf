package io.github.rikoappdev.composepdf.image

import io.github.rikoappdev.composepdf.compress.zlibInflate
import io.github.rikoappdev.composepdf.util.ByteBuf

/**
 * A decoded PNG ready to embed in a PDF: 8-bit-per-channel RGB pixels (row-major, [width]*[height]*3
 * bytes) and an optional 8-bit alpha plane ([width]*[height] bytes), or null when the image is fully
 * opaque. The RGB plane is embedded as a `/DeviceRGB /FlateDecode` image XObject; the alpha plane,
 * when present, becomes a `/DeviceGray` `/SMask` on it.
 */
internal class PngImage(
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
    val alpha: ByteArray?,
)

private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** True if [bytes] begins with the 8-byte PNG signature. */
internal fun isPng(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    for (i in 0 until 8) if ((bytes[i].toInt() and 0xFF) != PNG_SIGNATURE[i]) return false
    return true
}

/** True if [bytes] begins with the JPEG SOI marker (FF D8 FF). */
internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size > 3 &&
        (bytes[0].toInt() and 0xFF) == 0xFF &&
        (bytes[1].toInt() and 0xFF) == 0xD8 &&
        (bytes[2].toInt() and 0xFF) == 0xFF

/**
 * Decodes a PNG into 8-bit RGB + optional 8-bit alpha. Supports color types 0 (grayscale), 2
 * (truecolor), 3 (palette), 4 (grayscale+alpha) and 6 (truecolor+alpha). Bit depth 8 is supported
 * for all types; palette images additionally accept bit depths 1/2/4. 16-bit and interlaced PNGs are
 * rejected with a clear error. Throws [IllegalArgumentException] on malformed input.
 */
internal fun decodePng(bytes: ByteArray): PngImage {
    require(isPng(bytes)) { "Not a PNG (bad signature)" }

    var width = 0
    var height = 0
    var bitDepth = 0
    var colorType = -1
    var interlace = 0
    var palette: IntArray? = null      // packed 0xRRGGBB per index
    var paletteAlpha: IntArray? = null // alpha per palette index (tRNS for color type 3)
    var trnsGray = -1                  // single transparent gray sample (tRNS for color type 0)
    var trnsRgb = -1                   // single transparent 0xRRGGBB (tRNS for color type 2)
    val idat = ByteBuf(bytes.size)

    var p = 8 // skip signature
    while (p + 8 <= bytes.size) {
        val length = readU32(bytes, p)
        val type = chunkType(bytes, p + 4)
        val dataStart = p + 8
        require(dataStart + length <= bytes.size) { "Truncated PNG chunk $type" }
        when (type) {
            "IHDR" -> {
                width = readU32(bytes, dataStart)
                height = readU32(bytes, dataStart + 4)
                bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                colorType = bytes[dataStart + 9].toInt() and 0xFF
                interlace = bytes[dataStart + 12].toInt() and 0xFF
                require(width > 0 && height > 0) { "PNG has non-positive dimensions" }
                require(interlace == 0) { "Interlaced (Adam7) PNG is not supported" }
                require(bitDepth != 16) { "16-bit PNG is not supported (reduce to 8-bit)" }
            }
            "PLTE" -> {
                val n = length / 3
                palette = IntArray(n) { i ->
                    val o = dataStart + i * 3
                    ((bytes[o].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        (bytes[o + 2].toInt() and 0xFF)
                }
            }
            "tRNS" -> when (colorType) {
                3 -> paletteAlpha = IntArray(length) { bytes[dataStart + it].toInt() and 0xFF }
                0 -> trnsGray = readU16(bytes, dataStart) // 16-bit sample; low byte is the 8-bit value
                2 -> {
                    val r = readU16(bytes, dataStart)
                    val g = readU16(bytes, dataStart + 2)
                    val b = readU16(bytes, dataStart + 4)
                    trnsRgb = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                }
            }
            "IDAT" -> idat.bytes(bytes.copyOfRange(dataStart, dataStart + length))
            "IEND" -> return finishDecode(
                width, height, bitDepth, colorType, idat.toByteArray(),
                palette, paletteAlpha, trnsGray, trnsRgb,
            )
        }
        p = dataStart + length + 4 // skip data + 4-byte CRC
    }
    // Some encoders omit a strict IEND; decode with whatever IDAT we gathered.
    require(colorType >= 0) { "PNG missing IHDR" }
    return finishDecode(
        width, height, bitDepth, colorType, idat.toByteArray(),
        palette, paletteAlpha, trnsGray, trnsRgb,
    )
}

private fun finishDecode(
    width: Int,
    height: Int,
    bitDepth: Int,
    colorType: Int,
    idatBytes: ByteArray,
    palette: IntArray?,
    paletteAlpha: IntArray?,
    trnsGray: Int,
    trnsRgb: Int,
): PngImage {
    require(idatBytes.isNotEmpty()) { "PNG has no IDAT data" }

    // Channels per pixel by color type: 0=G, 2=RGB, 3=palette index, 4=GA, 6=RGBA.
    val channels = when (colorType) {
        0 -> 1
        2 -> 3
        3 -> 1
        4 -> 2
        6 -> 4
        else -> throw IllegalArgumentException("Unsupported PNG color type $colorType")
    }
    if (colorType == 3) {
        require(bitDepth in intArrayOf(1, 2, 4, 8)) { "Unsupported palette bit depth $bitDepth" }
        requireNotNull(palette) { "Palette PNG (color type 3) missing PLTE chunk" }
    } else {
        require(bitDepth == 8) { "Unsupported bit depth $bitDepth for color type $colorType (only 8 supported)" }
    }

    val raw = zlibInflate(idatBytes)
    val bitsPerPixel = channels * bitDepth
    val bytesPerScanline = (width * bitsPerPixel + 7) / 8
    // bytesPerPixel for the filter (rounded up to a whole byte; 1 for sub-byte packed depths).
    val bpp = (bitsPerPixel + 7) / 8
    val stride = bytesPerScanline + 1 // +1 filter-type byte per scanline
    require(raw.size >= stride * height) {
        "PNG inflated data too short: ${raw.size} < ${stride * height}"
    }

    // Un-filter into a tightly packed scanline buffer (no filter bytes).
    val unfiltered = ByteArray(bytesPerScanline * height)
    var rawPos = 0
    for (row in 0 until height) {
        val filter = raw[rawPos++].toInt() and 0xFF
        val outRow = row * bytesPerScanline
        val prevRow = outRow - bytesPerScanline
        for (i in 0 until bytesPerScanline) {
            val x = raw[rawPos++].toInt() and 0xFF
            val a = if (i >= bpp) unfiltered[outRow + i - bpp].toInt() and 0xFF else 0
            val b = if (row > 0) unfiltered[prevRow + i].toInt() and 0xFF else 0
            val c = if (row > 0 && i >= bpp) unfiltered[prevRow + i - bpp].toInt() and 0xFF else 0
            val value = when (filter) {
                0 -> x
                1 -> x + a
                2 -> x + b
                3 -> x + (a + b) / 2
                4 -> x + paeth(a, b, c)
                else -> throw IllegalArgumentException("Unknown PNG filter type $filter")
            } and 0xFF
            unfiltered[outRow + i] = value.toByte()
        }
    }

    val pixelCount = width * height
    val rgb = ByteArray(pixelCount * 3)
    var alpha: ByteArray? = null
    var sawTransparency = false

    fun ensureAlpha(): ByteArray {
        val a = alpha ?: ByteArray(pixelCount) { 0xFF.toByte() }.also { alpha = it }
        return a
    }

    when (colorType) {
        0 -> { // grayscale (bit depth 8)
            for (i in 0 until pixelCount) {
                val g = unfiltered[i].toInt() and 0xFF
                rgb[i * 3] = g.toByte(); rgb[i * 3 + 1] = g.toByte(); rgb[i * 3 + 2] = g.toByte()
                if (trnsGray >= 0 && g == (trnsGray and 0xFF)) { ensureAlpha()[i] = 0; sawTransparency = true }
            }
        }
        2 -> { // truecolor RGB (bit depth 8)
            for (i in 0 until pixelCount) {
                val r = unfiltered[i * 3].toInt() and 0xFF
                val g = unfiltered[i * 3 + 1].toInt() and 0xFF
                val b = unfiltered[i * 3 + 2].toInt() and 0xFF
                rgb[i * 3] = r.toByte(); rgb[i * 3 + 1] = g.toByte(); rgb[i * 3 + 2] = b.toByte()
                if (trnsRgb >= 0 && ((r shl 16) or (g shl 8) or b) == trnsRgb) {
                    ensureAlpha()[i] = 0; sawTransparency = true
                }
            }
        }
        3 -> { // palette (bit depth 1/2/4/8)
            val pal = palette!!
            for (i in 0 until pixelCount) {
                val row = i / width
                val col = i % width
                val index = readPackedIndex(unfiltered, row * bytesPerScanline, col, bitDepth)
                val color = if (index < pal.size) pal[index] else 0
                rgb[i * 3] = ((color shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((color shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (color and 0xFF).toByte()
                if (paletteAlpha != null && index < paletteAlpha.size) {
                    val av = paletteAlpha[index]
                    ensureAlpha()[i] = av.toByte()
                    if (av != 0xFF) sawTransparency = true
                }
            }
        }
        4 -> { // grayscale + alpha (bit depth 8)
            for (i in 0 until pixelCount) {
                val g = unfiltered[i * 2].toInt() and 0xFF
                val av = unfiltered[i * 2 + 1].toInt() and 0xFF
                rgb[i * 3] = g.toByte(); rgb[i * 3 + 1] = g.toByte(); rgb[i * 3 + 2] = g.toByte()
                ensureAlpha()[i] = av.toByte()
                if (av != 0xFF) sawTransparency = true
            }
        }
        6 -> { // truecolor + alpha (bit depth 8)
            for (i in 0 until pixelCount) {
                rgb[i * 3] = unfiltered[i * 4]
                rgb[i * 3 + 1] = unfiltered[i * 4 + 1]
                rgb[i * 3 + 2] = unfiltered[i * 4 + 2]
                val av = unfiltered[i * 4 + 3].toInt() and 0xFF
                ensureAlpha()[i] = av.toByte()
                if (av != 0xFF) sawTransparency = true
            }
        }
    }

    // Drop a fully-opaque alpha plane so opaque PNGs don't carry a pointless SMask.
    return PngImage(width, height, rgb, if (sawTransparency) alpha else null)
}

/** Reads palette index [col] from a packed scanline of [bitDepth]-bit indices (MSB-first). */
private fun readPackedIndex(data: ByteArray, rowStart: Int, col: Int, bitDepth: Int): Int {
    if (bitDepth == 8) return data[rowStart + col].toInt() and 0xFF
    val perByte = 8 / bitDepth
    val byteIndex = rowStart + col / perByte
    val withinByte = col % perByte
    val shift = 8 - bitDepth * (withinByte + 1)
    val mask = (1 shl bitDepth) - 1
    return (data[byteIndex].toInt() ushr shift) and mask
}

/** The Paeth predictor (RFC 2083 §6.6): picks a/b/c whichever is closest to the linear estimate. */
private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = if (p - a < 0) a - p else p - a
    val pb = if (p - b < 0) b - p else p - b
    val pc = if (p - c < 0) c - p else p - c
    return when {
        pa <= pb && pa <= pc -> a
        pb <= pc -> b
        else -> c
    }
}

private fun readU32(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

private fun readU16(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun chunkType(b: ByteArray, o: Int): String =
    buildString { for (i in 0 until 4) append((b[o + i].toInt() and 0xFF).toChar()) }
