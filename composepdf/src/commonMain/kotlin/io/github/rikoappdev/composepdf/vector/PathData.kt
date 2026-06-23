package io.github.rikoappdev.composepdf.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A 2-D point in vector (Y-down) coordinate space. */
internal class Pt(val x: Double, val y: Double)

/** One drawing segment of a subpath, in absolute coordinates. */
internal sealed interface Seg
internal class LineSeg(val to: Pt) : Seg
internal class CubicSeg(val c1: Pt, val c2: Pt, val to: Pt) : Seg

/** A contiguous subpath: a start point, a run of line/cubic segments, and whether it is closed. */
internal class SubPath(val start: Pt, val segs: List<Seg>, val closed: Boolean)

/**
 * Parses the SVG/VectorDrawable path mini-language (identical syntax in both) into absolute
 * subpaths of move/line/cubic segments. Supports the full command set — M/m L/l H/h V/v C/c S/s
 * Q/q T/t A/a Z/z — converting quadratics and smooth curves to cubics and elliptical arcs to
 * cubic-bezier approximations, so downstream emission only deals with lines and cubics.
 */
internal fun parsePathData(d: String): List<SubPath> {
    val sc = Scanner(d)
    val out = ArrayList<SubPath>()

    var curX = 0.0; var curY = 0.0
    var startX = 0.0; var startY = 0.0
    var subStart: Pt? = null
    var segs = ArrayList<Seg>()
    var lastCmd = ' '
    var prevCubicC2: Pt? = null // reflection control for S
    var prevQuadC: Pt? = null   // reflection control for T

    fun flush(closed: Boolean) {
        val st = subStart
        if (st != null) out.add(SubPath(st, segs, closed))
        segs = ArrayList()
        subStart = null
    }
    fun ensureSub() { if (subStart == null) { subStart = Pt(curX, curY) } }

    while (!sc.eof()) {
        var cmd = sc.peekCmd()
        if (cmd != null) { sc.advance(); lastCmd = cmd }
        else {
            // No command letter next. Continue only if a number follows (an implicit coordinate
            // repeat); a non-number, non-command token (e.g. a stray '%') would otherwise spin
            // forever because num() can't advance past it — so stop on malformed input.
            if (!sc.hasNum()) break
            // Implicit command repeat: after M/m, extra coordinate pairs are L/l. Numbers after a
            // closepath (lastCmd Z/z) or before any command are invalid — stop rather than re-run
            // Z, which consumes no input and would loop forever.
            cmd = when (lastCmd) { 'M' -> 'L'; 'm' -> 'l'; 'Z', 'z', ' ' -> break; else -> lastCmd }
        }
        val rel = cmd in 'a'..'z'
        when (cmd.uppercaseChar()) {
            'M' -> {
                flush(false)
                var x = sc.num(); var y = sc.num()
                if (rel) { x += curX; y += curY }
                curX = x; curY = y; startX = x; startY = y
                subStart = Pt(x, y)
                prevCubicC2 = null; prevQuadC = null
            }
            'L' -> {
                var x = sc.num(); var y = sc.num(); if (rel) { x += curX; y += curY }
                ensureSub(); segs.add(LineSeg(Pt(x, y))); curX = x; curY = y
                prevCubicC2 = null; prevQuadC = null
            }
            'H' -> {
                var x = sc.num(); if (rel) x += curX
                ensureSub(); segs.add(LineSeg(Pt(x, curY))); curX = x
                prevCubicC2 = null; prevQuadC = null
            }
            'V' -> {
                var y = sc.num(); if (rel) y += curY
                ensureSub(); segs.add(LineSeg(Pt(curX, y))); curY = y
                prevCubicC2 = null; prevQuadC = null
            }
            'C' -> {
                var x1 = sc.num(); var y1 = sc.num(); var x2 = sc.num(); var y2 = sc.num(); var x = sc.num(); var y = sc.num()
                if (rel) { x1 += curX; y1 += curY; x2 += curX; y2 += curY; x += curX; y += curY }
                ensureSub(); segs.add(CubicSeg(Pt(x1, y1), Pt(x2, y2), Pt(x, y)))
                prevCubicC2 = Pt(x2, y2); prevQuadC = null; curX = x; curY = y
            }
            'S' -> {
                var x2 = sc.num(); var y2 = sc.num(); var x = sc.num(); var y = sc.num()
                if (rel) { x2 += curX; y2 += curY; x += curX; y += curY }
                val c1 = prevCubicC2?.let { Pt(2 * curX - it.x, 2 * curY - it.y) } ?: Pt(curX, curY)
                ensureSub(); segs.add(CubicSeg(c1, Pt(x2, y2), Pt(x, y)))
                prevCubicC2 = Pt(x2, y2); prevQuadC = null; curX = x; curY = y
            }
            'Q' -> {
                var qx = sc.num(); var qy = sc.num(); var x = sc.num(); var y = sc.num()
                if (rel) { qx += curX; qy += curY; x += curX; y += curY }
                ensureSub(); segs.add(quadToCubic(curX, curY, qx, qy, x, y))
                prevQuadC = Pt(qx, qy); prevCubicC2 = null; curX = x; curY = y
            }
            'T' -> {
                var x = sc.num(); var y = sc.num()
                if (rel) { x += curX; y += curY }
                val q = prevQuadC?.let { Pt(2 * curX - it.x, 2 * curY - it.y) } ?: Pt(curX, curY)
                ensureSub(); segs.add(quadToCubic(curX, curY, q.x, q.y, x, y))
                prevQuadC = q; prevCubicC2 = null; curX = x; curY = y
            }
            'A' -> {
                val rx = sc.num(); val ry = sc.num(); val rot = sc.num()
                val large = sc.flag() == 1; val sweep = sc.flag() == 1
                var x = sc.num(); var y = sc.num()
                if (rel) { x += curX; y += curY }
                ensureSub()
                for (cubic in arcToCubics(curX, curY, rx, ry, rot, large, sweep, x, y)) segs.add(cubic)
                prevCubicC2 = null; prevQuadC = null; curX = x; curY = y
            }
            'Z' -> {
                ensureSub()
                curX = startX; curY = startY
                flush(true)
                prevCubicC2 = null; prevQuadC = null
            }
            else -> break // unknown command: stop rather than loop
        }
    }
    flush(false)
    return out
}

private fun quadToCubic(x0: Double, y0: Double, qx: Double, qy: Double, x: Double, y: Double): CubicSeg {
    val c1 = Pt(x0 + 2.0 / 3.0 * (qx - x0), y0 + 2.0 / 3.0 * (qy - y0))
    val c2 = Pt(x + 2.0 / 3.0 * (qx - x), y + 2.0 / 3.0 * (qy - y))
    return CubicSeg(c1, c2, Pt(x, y))
}

/** Converts an SVG elliptical-arc command to a sequence of cubic beziers (≤ 90° each). */
private fun arcToCubics(
    x0: Double, y0: Double, rxIn: Double, ryIn: Double, phiDeg: Double,
    largeArc: Boolean, sweep: Boolean, x: Double, y: Double,
): List<CubicSeg> {
    if (rxIn == 0.0 || ryIn == 0.0 || (x0 == x && y0 == y)) {
        return listOf(CubicSeg(Pt(x0, y0), Pt(x, y), Pt(x, y))) // degenerate → straight line
    }
    var rx = abs(rxIn); var ry = abs(ryIn)
    val phi = phiDeg * PI / 180.0
    val cosP = cos(phi); val sinP = sin(phi)

    val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
    val x1p = cosP * dx + sinP * dy
    val y1p = -sinP * dx + cosP * dy

    val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if (lambda > 1.0) { val s = sqrt(lambda); rx *= s; ry *= s }

    val sign = if (largeArc != sweep) 1.0 else -1.0
    var num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    if (num < 0.0) num = 0.0
    val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    val co = sign * sqrt(if (den == 0.0) 0.0 else num / den)
    val cxp = co * (rx * y1p / ry)
    val cyp = co * (-ry * x1p / rx)
    val cx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
    val cy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0

    fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        var a = acos((if (len == 0.0) 0.0 else dot / len).coerceIn(-1.0, 1.0))
        if (ux * vy - uy * vx < 0) a = -a
        return a
    }
    val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if (!sweep && dtheta > 0) dtheta -= 2 * PI
    if (sweep && dtheta < 0) dtheta += 2 * PI

    val segments = ceil(abs(dtheta) / (PI / 2)).toInt().coerceAtLeast(1)
    val delta = dtheta / segments
    val t = 4.0 / 3.0 * tan(delta / 4.0)

    fun pt(theta: Double): Pt {
        val ex = rx * cos(theta); val ey = ry * sin(theta)
        return Pt(cx + cosP * ex - sinP * ey, cy + sinP * ex + cosP * ey)
    }
    fun deriv(theta: Double): Pt {
        val ex = -rx * sin(theta); val ey = ry * cos(theta)
        return Pt(cosP * ex - sinP * ey, sinP * ex + cosP * ey)
    }

    val result = ArrayList<CubicSeg>(segments)
    var ang = theta1
    for (k in 0 until segments) {
        val a0 = ang; val a1 = ang + delta
        val p0 = pt(a0); val d0 = deriv(a0); val p1 = pt(a1); val d1 = deriv(a1)
        result.add(
            CubicSeg(
                Pt(p0.x + t * d0.x, p0.y + t * d0.y),
                Pt(p1.x - t * d1.x, p1.y - t * d1.y),
                p1,
            )
        )
        ang = a1
    }
    return result
}

/** Tokenizer over a path-data string: numbers, single-char commands and arc flags. */
private class Scanner(val s: String) {
    var i = 0

    private fun skipSep() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    fun eof(): Boolean { skipSep(); return i >= s.length }

    /** Returns the next command letter without consuming it, or null if a number comes next. */
    fun peekCmd(): Char? {
        skipSep()
        if (i >= s.length) return null
        val c = s[i]
        return if ((c in 'A'..'Z' || c in 'a'..'z') && c != 'e' && c != 'E') c else null
    }

    fun advance() { i++ }

    /** True if the next token starts a number (digit, sign or dot). Lets the parser detect
     *  malformed trailing input and stop instead of looping on a non-advancing [num]. */
    fun hasNum(): Boolean {
        skipSep()
        if (i >= s.length) return false
        val c = s[i]
        return c.isDigit() || c == '+' || c == '-' || c == '.'
    }

    fun num(): Double {
        skipSep()
        val start = i
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        while (i < s.length && s[i].isDigit()) i++
        if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i].isDigit()) i++
        }
        val tok = s.substring(start, i)
        return tok.toDoubleOrNull() ?: 0.0
    }

    /** An arc flag is a single '0' or '1', optionally separated. */
    fun flag(): Int {
        skipSep()
        if (i >= s.length) return 0
        val c = s[i]; i++
        return if (c == '1') 1 else 0
    }
}
