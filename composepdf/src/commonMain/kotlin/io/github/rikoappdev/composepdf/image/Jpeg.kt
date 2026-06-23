package io.github.rikoappdev.composepdf.image

/** Dimensions + component count parsed from a baseline/progressive JPEG's SOF marker. */
internal class JpegInfo(val width: Int, val height: Int, val components: Int)

/**
 * Reads width/height/components from a JPEG's Start-Of-Frame marker without decoding pixels — the
 * bytes are embedded verbatim via /DCTDecode. Only baseline (C0), extended-sequential (C1) and
 * progressive (C2) Huffman frames are recognized; lossless/arithmetic frames — which /DCTDecode
 * cannot render — are ignored (yielding "no SOF" rather than an unrenderable image). Throws on
 * non-JPEG or CMYK (4-component), which needs a Decode array (out of scope for v1).
 */
internal fun parseJpeg(bytes: ByteArray): JpegInfo {
    require(bytes.size > 4 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) {
        "Not a JPEG (missing SOI marker)"
    }
    var i = 2
    while (i + 1 < bytes.size) {
        if ((bytes[i].toInt() and 0xFF) != 0xFF) { i++; continue }
        val marker = bytes[i + 1].toInt() and 0xFF
        i += 2
        // Standalone markers (no length payload).
        if (marker == 0x01 || marker in 0xD0..0xD9) continue
        if (i + 1 >= bytes.size) break
        val len = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        // SOF markers carry frame geometry. Only the Huffman frame types /DCTDecode can render:
        // baseline (C0), extended-sequential (C1), progressive (C2). Lossless (C3), differential
        // (C5-C7) and arithmetic-coded (C9-CB, CD-CF) frames are skipped like any other segment.
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
            require(i + 7 < bytes.size) { "Truncated JPEG SOF" }
            val height = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
            val width = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
            val components = bytes[i + 7].toInt() and 0xFF
            require(components == 1 || components == 3) {
                "Unsupported JPEG with $components components (CMYK not supported in v1)"
            }
            return JpegInfo(width, height, components)
        }
        i += len
    }
    throw IllegalArgumentException("No SOF marker found in JPEG")
}
