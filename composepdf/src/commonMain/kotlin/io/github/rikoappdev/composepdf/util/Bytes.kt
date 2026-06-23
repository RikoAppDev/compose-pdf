package io.github.rikoappdev.composepdf.util

/** Big-endian reader over a [ByteArray]. All multi-byte reads are big-endian (sfnt/PDF convention). */
internal class ByteReader(val data: ByteArray, var pos: Int = 0) {
    fun u8(): Int = data[pos++].toInt() and 0xFF
    fun u16(): Int {
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2; return v
    }
    fun s16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
    fun u32(): Long {
        val v = ((data[pos].toLong() and 0xFF) shl 24) or
            ((data[pos + 1].toLong() and 0xFF) shl 16) or
            ((data[pos + 2].toLong() and 0xFF) shl 8) or
            (data[pos + 3].toLong() and 0xFF)
        pos += 4; return v
    }
    fun skip(n: Int) { pos += n }
    fun seek(p: Int) { pos = p }

    fun u16At(p: Int): Int = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
    fun s16At(p: Int): Int { val v = u16At(p); return if (v >= 0x8000) v - 0x10000 else v }
    fun u32At(p: Int): Long = ((data[p].toLong() and 0xFF) shl 24) or
        ((data[p + 1].toLong() and 0xFF) shl 16) or
        ((data[p + 2].toLong() and 0xFF) shl 8) or
        (data[p + 3].toLong() and 0xFF)
    fun tagAt(p: Int): String = buildString { for (i in 0 until 4) append((data[p + i].toInt() and 0xFF).toChar()) }
}

/** Growable big-endian byte buffer for assembling sfnt tables and PDF bytes. */
internal class ByteBuf(initial: Int = 1024) {
    private var buf = ByteArray(if (initial < 16) 16 else initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) {
            var ns = buf.size * 2
            while (ns < size + n) ns *= 2
            buf = buf.copyOf(ns)
        }
    }
    fun u8(v: Int) { ensure(1); buf[size++] = (v and 0xFF).toByte() }
    fun u16(v: Int) { u8(v ushr 8); u8(v) }
    fun u32(v: Long) { u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt()) }
    fun u32(v: Int) { u32(v.toLong() and 0xFFFFFFFFL) }
    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
    /** Reads a previously written byte (unsigned 0..255). Used by INFLATE's LZ77 back-references. */
    fun byteAt(index: Int): Int = buf[index].toInt() and 0xFF
    /** Writes [s] as one byte per char (Latin-1/ASCII) — correct for PDF syntax tokens. */
    fun ascii(s: String) { ensure(s.length); for (c in s) buf[size++] = (c.code and 0xFF).toByte() }
    fun padTo(align: Int) { while (size % align != 0) u8(0) }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Lowercase not used; PDF hex strings are conventionally uppercase. */
internal fun Int.toHex4(): String {
    val h = "0123456789ABCDEF"
    return buildString {
        append(h[(this@toHex4 ushr 12) and 0xF])
        append(h[(this@toHex4 ushr 8) and 0xF])
        append(h[(this@toHex4 ushr 4) and 0xF])
        append(h[this@toHex4 and 0xF])
    }
}

/**
 * Deterministic, locale-independent decimal formatter for vector path coordinates and colors.
 * Fixed rounding (half-to-even via [kotlin.math.round]) → identical output on every platform; do
 * NOT use `Double.toString()` / platform formatting, which varies by platform and locale.
 */
internal fun fmtNum(value: Double, decimals: Int = 3): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val neg = value < 0.0
    val mag = kotlin.math.abs(value)
    // Guard against Long overflow when scaling: such coordinates are far outside any real
    // page/viewport (and get clipped anyway). Fall back to the rounded integer, never a saturated
    // Long that would print as a garbage number.
    if (mag * scale >= 9.0e18) {
        if (mag >= 9.0e18) return "0"
        val i = kotlin.math.round(mag).toLong()
        return if (neg && i != 0L) "-$i" else "$i"
    }
    val scaled = kotlin.math.round(mag * scale).toLong()
    val intPart = scaled / scale
    val frac = scaled % scale
    val sb = StringBuilder()
    if (neg && (intPart != 0L || frac != 0L)) sb.append('-')
    sb.append(intPart)
    if (frac > 0L) {
        val fracStr = frac.toString().padStart(decimals, '0').trimEnd('0')
        if (fracStr.isNotEmpty()) sb.append('.').append(fracStr)
    }
    return sb.toString()
}

/** Iterates Unicode code points of a string, combining surrogate pairs.
 *  Named `toCodePoints` to avoid colliding with Java's `CharSequence.codePoints()` on JVM. */
internal fun String.toCodePoints(): List<Int> {
    val out = ArrayList<Int>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            out.add(cp); i += 2
        } else {
            out.add(c.code); i += 1
        }
    }
    return out
}
