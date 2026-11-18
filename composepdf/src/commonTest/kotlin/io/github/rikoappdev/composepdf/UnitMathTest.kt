package io.github.rikoappdev.composepdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The millipoint helpers reduce to the 0.10.0 integer formulas for whole inputs — the arithmetic
 * behind the byte-for-byte identity of whole-value documents. Common code, so it runs on JVM, Android
 * and iOS alike.
 */
class UnitMathTest {

    @Test
    fun halfTruncIsTheOldIntegerHalf() {
        for (k in -2000..2000) assertEquals((k / 2) * 1000, halfTrunc(k * 1000), "halfTrunc(${k * 1000})")
    }

    @Test
    fun wholeTruncKeepsWholePointsAndTruncatesTowardZero() {
        for (k in -2000..2000) {
            assertEquals(k * 1000, wholeTrunc(k * 1000), "wholeTrunc(${k * 1000})")
            val sign = if (k < 0) -1 else 1
            for (f in listOf(1, 500, 999)) assertEquals(k * 1000, wholeTrunc(k * 1000 + sign * f), "wholeTrunc(${k * 1000 + sign * f})")
        }
    }

    @Test
    fun fmtMptPrintsTheFewestDecimals() {
        val expected = listOf(
            0 to "0", -1 to "-0.001", -999 to "-0.999", -1000 to "-1", 1000 to "1", 1001 to "1.001",
            1010 to "1.01", 1100 to "1.1", 14600 to "14.6", 750 to "0.75", -500 to "-0.5",
            Int.MIN_VALUE + 1 to "-2147483.647",
        )
        for ((mpt, text) in expected) assertEquals(text, fmtMpt(mpt), "fmtMpt($mpt)")
    }

    @Test
    fun fontUnitsToMptIsTheOldFormulaForWholeSizes() {
        val units = (-3000L..3000L step 7) + listOf(-100_000L, -2048L, -1024L, -513L, -512L, -511L, -1L, 0L, 1L, 499L, 500L, 501L, 511L, 512L, 513L, 1024L, 2047L, 2048L, 65_535L, 1_000_000L)
        for (upm in listOf(1000, 1024, 2048, 1001)) for (fs in 1..72) for (u in units) {
            val old = ((u * fs + upm / 2) / upm) * 1000
            assertEquals(old, fontUnitsToMpt(u, upm, fs * 1000).toLong(), "units=$u upm=$upm size=$fs")
        }
    }

    @Test
    fun lineHeightIsTheOldFloorForWholeSizes() {
        for (s in 1..72) for (m in listOf(1.0, 1.15, 1.2, 1.3, 1.45, 1.5)) {
            assertEquals((s * m).toInt() * 1000, lineHeightMpt(s * 1000, m), "size=$s multiple=$m")
        }
    }

    @Test
    fun weightedShareSeesTheOldFloatForWholeWidths() {
        // RowNode / tableColumnWidths read the width as `mpt / 1000f`; 0.10.0 read `pt.toFloat()`.
        for (w in 0..20_000) assertEquals(w.toFloat(), (w * 1000) / 1000f, "width $w pt")
    }
}
