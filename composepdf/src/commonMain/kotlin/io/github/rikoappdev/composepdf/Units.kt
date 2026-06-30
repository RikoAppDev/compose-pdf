package io.github.rikoappdev.composepdf

import kotlin.jvm.JvmInline

/**
 * Compose-style value types. Deliberately mirror the shape of `androidx.compose.ui` so authoring
 * a document reads like writing Compose, while the engine stays dependency-free and deterministic.
 *
 * Unit mapping is fixed and device-independent: **1.dp = 1pt** and **1.sp = 1pt** (PDF point =
 * 1/72"). No `LocalDensity`, no system font-scale — the same input always yields the same layout.
 */
@JvmInline
value class Dp(val value: Int)

val Int.dp: Dp get() = Dp(this)

@JvmInline
value class Sp(val value: Int)

val Int.sp: Sp get() = Sp(this)

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

enum class FontWeight { Normal, Bold }

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
    /** Line height as a multiple of font size (1.2 ≈ comfortable). */
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
