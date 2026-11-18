package io.github.rikoappdev.composepdf

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compose-style value types. Deliberately mirror the shape of `androidx.compose.ui` so authoring
 * a document reads like writing Compose, while the engine stays dependency-free and deterministic.
 *
 * Unit mapping is fixed and device-independent: **1.dp = 1pt** and **1.sp = 1pt** (PDF point =
 * 1/72"). No `LocalDensity`, no system font-scale — the same input always yields the same layout.
 *
 * Values may be fractional (`0.75.dp`, `14.6.dp`, `7.5.sp`). Lengths and font sizes you give are
 * honoured to 0.001 pt. What the engine derives from them — text widths, ascent, line height
 * (`floor(fontSize × lineHeightMultiple)`), centring offsets, weighted row/column widths — is
 * rounded to whole points, exactly as before, so documents using whole values lay out unchanged.
 */
@JvmInline
value class Dp(val value: Float) {
    /** Whole-point convenience: `Dp(12)` is `12.dp`. */
    constructor(value: Int) : this(value.toFloat())
}

val Int.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(this.toFloat())

@JvmInline
value class Sp(val value: Float) {
    /** Whole-point convenience: `Sp(8)` is `8.sp`. */
    constructor(value: Int) : this(value.toFloat())
}

val Int.sp: Sp get() = Sp(this.toFloat())
val Float.sp: Sp get() = Sp(this)
val Double.sp: Sp get() = Sp(this.toFloat())

/** Engine resolution: lengths are fixed-point integers in millipoints (1 pt = 1000 mpt). */
internal const val MPT_PER_PT = 1000

/**
 * Largest magnitude, in points, a [Dp] / [Sp] may have. It guarantees that each single value fits
 * an `Int` millipoint count (at most 1e9 mpt, under `Int.MAX_VALUE` ≈ 2.147e9) with room to double it
 * (`2 × padding`). Sums of many values can still pass `Int`: the layout forms those in `Long` and
 * saturates ([satAdd], [satMul], [saturatedToInt]), so such a height reads as taller than any page.
 */
private const val MAX_ABS_PT = 1_000_000f

private fun toMpt(value: Float, what: String): Int {
    require(!value.isNaN() && !value.isInfinite() && abs(value) <= MAX_ABS_PT) {
        "$what must be a finite value within ±1,000,000, was $value"
    }
    return (value.toDouble() * MPT_PER_PT).roundToInt()
}

/** This length in millipoints. */
internal val Dp.mpt: Int get() = toMpt(value, "A dp length")

/** This font size in millipoints. */
internal val Sp.mpt: Int get() = toMpt(value, "A font size")

/** Truncates [mpt] toward zero to whole points — the 0.10.0 integer-division result. */
internal fun wholeTrunc(mpt: Int): Int = (mpt / MPT_PER_PT) * MPT_PER_PT

/** Half of [mpt], truncated toward zero to whole points: the 0.10.0 centring offset `(a - b) / 2`. */
internal fun halfTrunc(mpt: Int): Int = wholeTrunc(mpt / 2)

/** [this] clamped to the `Int` range: a length past it saturates instead of wrapping around. */
internal fun Long.saturatedToInt(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** `a + b` saturated to the `Int` range (identical to `a + b` whenever that does not overflow). */
internal fun satAdd(a: Int, b: Int): Int = (a.toLong() + b).saturatedToInt()

/** `a * b` saturated to the `Int` range (identical to `a * b` whenever that does not overflow). */
internal fun satMul(a: Int, b: Int): Int = (a.toLong() * b).saturatedToInt()

/** Largest whole-point count whose millipoints fit an `Int`. */
private const val MAX_WHOLE_PT = (Int.MAX_VALUE / MPT_PER_PT).toLong()

/** [wholePt] whole points in millipoints, saturated to the largest whole point that fits an `Int`. */
private fun wholePtToMpt(wholePt: Long): Int = (wholePt.coerceIn(-MAX_WHOLE_PT, MAX_WHOLE_PT) * MPT_PER_PT).toInt()

/** Line height for a font of [fontSizeMpt]: `floor(fontSize × multiple)` in whole points, as in 0.10.0. */
internal fun lineHeightMpt(fontSizeMpt: Int, lineHeightMultiple: Double): Int =
    wholePtToMpt(((fontSizeMpt / MPT_PER_PT.toDouble()) * lineHeightMultiple).toLong())

/**
 * Scales [units] (font units of a face with [unitsPerEm]) to a font of [fontSizeMpt], rounded half up
 * to whole points and returned in millipoints. For a whole font size this is exactly the 0.10.0
 * `(units * size + upm / 2) / upm`.
 */
internal fun fontUnitsToMpt(units: Long, unitsPerEm: Int, fontSizeMpt: Int): Int =
    wholePtToMpt((units * fontSizeMpt + (unitsPerEm / 2) * MPT_PER_PT.toLong()) / (unitsPerEm.toLong() * MPT_PER_PT))

/**
 * Deterministic PDF number for a millipoint length: a whole point prints as a plain integer (`36`,
 * `-5`), anything else with the fewest decimals (`14.6`, `0.75`, `-0.5`). Locale-independent.
 */
internal fun fmtMpt(mpt: Int): String {
    if (mpt % MPT_PER_PT == 0) return (mpt / MPT_PER_PT).toString()
    val neg = mpt < 0
    val mag = abs(mpt.toLong())
    val frac = (mag % MPT_PER_PT).toString().padStart(3, '0').trimEnd('0')
    return (if (neg) "-" else "") + (mag / MPT_PER_PT) + "." + frac
}

/** Opaque RGB color. `Color(0xFF212529)` and `Color(0x212529)` are equivalent (alpha ignored). */
class PdfColor(val r: Int, val g: Int, val b: Int) {
    companion object {
        val Black = PdfColor(0, 0, 0)
        val White = PdfColor(255, 255, 255)
    }
}

@Suppress("FunctionName")
fun Color(rgb: Long): PdfColor =
    PdfColor(((rgb ushr 16) and 0xFF).toInt(), ((rgb ushr 8) and 0xFF).toInt(), (rgb and 0xFF).toInt())

/**
 * Font weight of a text run. [Normal] and [Bold] use the Regular and Bold faces passed to `render`.
 * [Medium] uses the optional Medium face (`mediumFontBytes`); without one it is set in the Regular
 * face — as CSS font matching does for weight 500 (try 500, then lighter, then heavier) — and the
 * output is then identical to the same text in [Normal]. ([Medium] is declared last so the
 * ordinals of [Normal] and [Bold] are unchanged.)
 */
enum class FontWeight { Normal, Bold, Medium }

enum class TextAlign { Start, Center, End }

/** Horizontal placement of a fitted image/vector inside its box (mirrors Compose `Alignment.Horizontal`). */
enum class HorizontalAlignment { Start, Center, End }

/** Vertical placement of a [row]'s cells within the row's height — the tallest cell (mirrors `Alignment.Vertical`). */
enum class VerticalAlignment { Top, Center, Bottom }

/**
 * How an image fills its box. [Cover] crops to fill; [Contain] fits the whole image preserving its
 * aspect ratio; [Smart] preserves aspect like [Contain] but crops-to-fill when an extreme aspect
 * ratio would otherwise shrink the image to a thin sliver (so a photo grid never shows tiny strips).
 */
enum class PhotoFit { Cover, Contain, Smart }

class TextStyle(
    val fontSize: Sp = 12.sp,
    val fontWeight: FontWeight = FontWeight.Normal,
    val color: PdfColor = PdfColor.Black,
    val align: TextAlign = TextAlign.Start,
    /** Line height as a multiple of font size (1.2 ≈ comfortable). The resulting line height is
     *  truncated to whole points: `floor(fontSize × lineHeightMultiple)`. */
    val lineHeightMultiple: Double = 1.3,
) {
    /** Returns a copy with the given fields overridden (mirrors a data class `copy`). */
    fun copy(
        fontSize: Sp = this.fontSize,
        fontWeight: FontWeight = this.fontWeight,
        color: PdfColor = this.color,
        align: TextAlign = this.align,
        lineHeightMultiple: Double = this.lineHeightMultiple,
    ): TextStyle = TextStyle(fontSize, fontWeight, color, align, lineHeightMultiple)
}
