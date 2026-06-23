package io.github.rikoappdev.composepdf.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A format-agnostic vector image: a [viewportW] × [viewportH] coordinate box and a list of painted
 * [paths]. Produced by reading either an Android VectorDrawable or an SVG; all group transforms are
 * already baked into each path's absolute coordinates, so emission is a flat walk.
 */
internal class VectorModel(
    val viewportW: Double,
    val viewportH: Double,
    val paths: List<VPath>,
)

/** One painted path: geometry plus fill/stroke paint. Colors are ARGB (alpha in the high byte). */
internal class VPath(
    val subpaths: List<SubPath>,
    val fillArgb: Int?,
    val evenOdd: Boolean,
    val strokeArgb: Int?,
    val strokeWidth: Double,
    val fillAlpha: Double,
    val strokeAlpha: Double,
)

/** A 2-D affine transform `[a c e; b d f; 0 0 1]` (SVG/PDF convention). */
internal class Mat(
    val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double,
) {
    /** Returns `this · o` — applying [o] first, then this (correct for nested group transforms). */
    fun mul(o: Mat) = Mat(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f,
    )

    fun apply(p: Pt) = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** Uniform scale magnitude (used to scale stroke widths under a transform). */
    fun scaleMagnitude() = sqrt(abs(a * d - b * c))

    companion object {
        val ID = Mat(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(tx: Double, ty: Double) = Mat(1.0, 0.0, 0.0, 1.0, tx, ty)
        fun scale(sx: Double, sy: Double) = Mat(sx, 0.0, 0.0, sy, 0.0, 0.0)
        fun rotate(deg: Double): Mat {
            val r = deg * PI / 180.0; val cs = cos(r); val sn = sin(r)
            return Mat(cs, sn, -sn, cs, 0.0, 0.0)
        }
    }
}

private fun transform(sub: SubPath, m: Mat): SubPath =
    SubPath(
        m.apply(sub.start),
        sub.segs.map { seg ->
            when (seg) {
                is LineSeg -> LineSeg(m.apply(seg.to))
                is CubicSeg -> CubicSeg(m.apply(seg.c1), m.apply(seg.c2), m.apply(seg.to))
            }
        },
        sub.closed,
    )

/** True if [bytes] look like a VectorDrawable or SVG XML document. */
internal fun isVectorXml(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    val head = bytes.copyOf(minOf(bytes.size, 1024)).decodeToString()
    return head.contains("<svg") || head.contains("<vector")
}

/** Parses VectorDrawable or SVG bytes into a [VectorModel] (auto-detected by the root element). */
internal fun parseVector(bytes: ByteArray): VectorModel {
    var text = bytes.decodeToString()
    if (text.isNotEmpty() && text[0] == '﻿') text = text.substring(1)
    val root = parseXml(text)
    return when (root.name.substringAfter(':')) {
        "vector" -> readVectorDrawable(root)
        "svg" -> readSvg(root)
        else -> throw IllegalArgumentException("Unsupported vector root <${root.name}> (expected <vector> or <svg>)")
    }
}

// --------------------------------------------------------------------------------------------------
// VectorDrawable
// --------------------------------------------------------------------------------------------------

private fun readVectorDrawable(root: XmlNode): VectorModel {
    val vw = root.attr("viewportWidth")?.toDoubleOrNull()
        ?: dimen(root.attr("width")) ?: 24.0
    val vh = root.attr("viewportHeight")?.toDoubleOrNull()
        ?: dimen(root.attr("height")) ?: 24.0
    val paths = ArrayList<VPath>()
    walkVd(root, Mat.ID, paths)
    return VectorModel(vw, vh, paths)
}

private fun walkVd(node: XmlNode, mat: Mat, out: MutableList<VPath>) {
    for (child in node.children) {
        when (child.name.substringAfter(':')) {
            "group" -> walkVd(child, mat.mul(vdGroupMatrix(child)), out)
            "path" -> {
                val d = child.attr("pathData") ?: continue
                val subs = parsePathData(d).map { transform(it, mat) }
                if (subs.isEmpty()) continue
                val fill = parseColor(child.attr("fillColor"), alphaFirst = true)
                val stroke = parseColor(child.attr("strokeColor"), alphaFirst = true)
                val sw = (child.attr("strokeWidth")?.toDoubleOrNull() ?: 0.0) * mat.scaleMagnitude()
                val evenOdd = child.attr("fillType")?.equals("evenOdd", ignoreCase = true) == true
                val fillA = child.attr("fillAlpha")?.toDoubleOrNull() ?: 1.0
                val strokeA = child.attr("strokeAlpha")?.toDoubleOrNull() ?: 1.0
                out.add(VPath(subs, fill, evenOdd, stroke, sw, fillA, strokeA))
            }
            // <clip-path>, <aapt:attr> etc. are ignored.
            else -> {}
        }
    }
}

private fun vdGroupMatrix(node: XmlNode): Mat {
    fun f(name: String, def: Double) = node.attr(name)?.toDoubleOrNull() ?: def
    val tx = f("translateX", 0.0); val ty = f("translateY", 0.0)
    val sx = f("scaleX", 1.0); val sy = f("scaleY", 1.0)
    val rot = f("rotation", 0.0); val px = f("pivotX", 0.0); val py = f("pivotY", 0.0)
    return Mat.translate(tx + px, ty + py)
        .mul(Mat.rotate(rot))
        .mul(Mat.scale(sx, sy))
        .mul(Mat.translate(-px, -py))
}

// --------------------------------------------------------------------------------------------------
// SVG
// --------------------------------------------------------------------------------------------------

/** Inheritable SVG paint state threaded down the element tree. */
private class Paint(
    val fill: Int?,        // ARGB, or null for "none"
    val stroke: Int?,
    val strokeWidth: Double,
    val fillOpacity: Double,
    val strokeOpacity: Double,
    val color: Int,        // the cascading `color` property — the value of `currentColor`
)

private fun readSvg(root: XmlNode): VectorModel {
    val viewBox = root.attr("viewBox")?.let { vb ->
        vb.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }
    }
    val (minx, miny, vw, vh) = if (viewBox != null && viewBox.size == 4) {
        Quad(viewBox[0], viewBox[1], viewBox[2], viewBox[3])
    } else {
        Quad(0.0, 0.0, dimen(root.attr("width")) ?: 100.0, dimen(root.attr("height")) ?: 100.0)
    }
    val rootMat = Mat.translate(-minx, -miny)
    val initial = Paint(
        fill = 0xFF000000.toInt(), stroke = null, strokeWidth = 1.0,
        fillOpacity = 1.0, strokeOpacity = 1.0, color = 0xFF000000.toInt(),
    )
    val paths = ArrayList<VPath>()
    walkSvg(root, rootMat, initial, paths)
    return VectorModel(vw, vh, paths)
}

private fun walkSvg(node: XmlNode, mat: Mat, paint: Paint, out: MutableList<VPath>) {
    for (child in node.children) {
        val local = child.attr("transform")?.let { parseSvgTransform(it) } ?: Mat.ID
        val m = mat.mul(local)
        val p = resolvePaint(child, paint)
        when (child.name.substringAfter(':')) {
            "g", "svg", "a" -> walkSvg(child, m, p, out)
            "path" -> child.attr("d")?.let { addSvgShape(parsePathData(it), child, m, p, out) }
            "rect" -> addSvgShape(rectSubpaths(child), child, m, p, out)
            "circle" -> addSvgShape(ellipseSubpaths(child, circle = true), child, m, p, out)
            "ellipse" -> addSvgShape(ellipseSubpaths(child, circle = false), child, m, p, out)
            "line" -> addSvgShape(lineSubpaths(child), child, m, p, out)
            "polyline" -> addSvgShape(polySubpaths(child, closed = false), child, m, p, out)
            "polygon" -> addSvgShape(polySubpaths(child, closed = true), child, m, p, out)
            else -> if (child.children.isNotEmpty()) walkSvg(child, m, p, out)
        }
    }
}

private fun addSvgShape(rawSubs: List<SubPath>, node: XmlNode, m: Mat, paint: Paint, out: MutableList<VPath>) {
    if (rawSubs.isEmpty()) return
    val subs = rawSubs.map { transform(it, m) }
    val evenOdd = (node.attr("fill-rule") ?: styleProp(node, "fill-rule"))?.equals("evenodd", ignoreCase = true) == true
    val opacity = (node.attr("opacity") ?: styleProp(node, "opacity"))?.toDoubleOrNull() ?: 1.0
    val sw = paint.strokeWidth * m.scaleMagnitude()
    out.add(
        VPath(
            subpaths = subs,
            fillArgb = paint.fill,
            evenOdd = evenOdd,
            strokeArgb = paint.stroke,
            strokeWidth = sw,
            fillAlpha = paint.fillOpacity * opacity,
            strokeAlpha = paint.strokeOpacity * opacity,
        )
    )
}

private fun resolvePaint(node: XmlNode, parent: Paint): Paint {
    fun prop(name: String): String? = node.attr(name) ?: styleProp(node, name)
    // The `color` property cascades and is the value `currentColor` resolves to.
    val colorProp = prop("color")?.trim()
    val color = when {
        colorProp == null || colorProp.equals("inherit", true) || colorProp.equals("currentColor", true) -> parent.color
        else -> parseColor(colorProp, alphaFirst = false) ?: parent.color
    }
    val fill = paintValue(prop("fill"), parent.fill, color)
    val stroke = paintValue(prop("stroke"), parent.stroke, color)
    val sw = prop("stroke-width")?.let { dimen(it) } ?: parent.strokeWidth
    val fo = prop("fill-opacity")?.toDoubleOrNull() ?: parent.fillOpacity
    val so = prop("stroke-opacity")?.toDoubleOrNull() ?: parent.strokeOpacity
    return Paint(fill, stroke, sw, fo, so, color)
}

/**
 * Resolves an SVG `fill`/`stroke` value against the inherited paint and the current `color`.
 * `null` (attribute absent) → inherit; `inherit` → inherit; `currentColor` → the cascaded color;
 * `none`/`transparent`/`url(...)`/unparseable → no paint; otherwise the parsed color.
 */
private fun paintValue(v: String?, parentPaint: Int?, currentColor: Int): Int? {
    if (v == null) return parentPaint
    val t = v.trim()
    return when {
        t.equals("inherit", true) -> parentPaint
        t.equals("currentColor", true) -> currentColor
        else -> parseSvgPaint(t)
    }
}

/** SVG paint value: a color, or null for "none"/"transparent"/unsupported (e.g. url(#gradient)). */
private fun parseSvgPaint(v: String): Int? {
    val t = v.trim()
    if (t.isEmpty() || t.equals("none", true) || t.equals("transparent", true) || t.startsWith("url(")) return null
    return parseColor(t, alphaFirst = false)
}

private fun styleProp(node: XmlNode, name: String): String? {
    val style = node.attr("style") ?: return null
    for (decl in style.split(';')) {
        val idx = decl.indexOf(':')
        if (idx > 0 && decl.substring(0, idx).trim() == name) return decl.substring(idx + 1).trim()
    }
    return null
}

private fun parseSvgTransform(s: String): Mat {
    var m = Mat.ID
    var i = 0
    while (i < s.length) {
        while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetter())) i++
        val name = s.substring(nameStart, i)
        if (name.isEmpty()) break
        val open = s.indexOf('(', i); if (open < 0) break
        val close = s.indexOf(')', open); if (close < 0) break
        val args = s.substring(open + 1, close).split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }
        val local = when (name) {
            "translate" -> Mat.translate(args.getOrElse(0) { 0.0 }, args.getOrElse(1) { 0.0 })
            "scale" -> Mat.scale(args.getOrElse(0) { 1.0 }, args.getOrElse(1) { args.getOrElse(0) { 1.0 } })
            "rotate" -> if (args.size >= 3) {
                Mat.translate(args[1], args[2]).mul(Mat.rotate(args[0])).mul(Mat.translate(-args[1], -args[2]))
            } else Mat.rotate(args.getOrElse(0) { 0.0 })
            "matrix" -> if (args.size == 6) Mat(args[0], args[1], args[2], args[3], args[4], args[5]) else Mat.ID
            "skewX" -> { val tn = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); Mat(1.0, 0.0, tn, 1.0, 0.0, 0.0) }
            "skewY" -> { val tn = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); Mat(1.0, tn, 0.0, 1.0, 0.0, 0.0) }
            else -> Mat.ID
        }
        m = m.mul(local)
        i = close + 1
    }
    return m
}

private const val KAPPA = 0.5522847498307936

private fun rectSubpaths(node: XmlNode): List<SubPath> {
    val x = node.attr("x")?.toDoubleOrNull() ?: 0.0
    val y = node.attr("y")?.toDoubleOrNull() ?: 0.0
    val w = node.attr("width")?.toDoubleOrNull() ?: return emptyList()
    val h = node.attr("height")?.toDoubleOrNull() ?: return emptyList()
    if (w <= 0 || h <= 0) return emptyList()
    var rx = node.attr("rx")?.toDoubleOrNull() ?: node.attr("ry")?.toDoubleOrNull() ?: 0.0
    var ry = node.attr("ry")?.toDoubleOrNull() ?: node.attr("rx")?.toDoubleOrNull() ?: 0.0
    rx = rx.coerceIn(0.0, w / 2); ry = ry.coerceIn(0.0, h / 2)
    if (rx == 0.0 || ry == 0.0) {
        val segs = listOf(LineSeg(Pt(x + w, y)), LineSeg(Pt(x + w, y + h)), LineSeg(Pt(x, y + h)))
        return listOf(SubPath(Pt(x, y), segs, closed = true))
    }
    val cx = rx * KAPPA; val cy = ry * KAPPA
    val segs = ArrayList<Seg>()
    // start at (x+rx, y), clockwise
    segs.add(LineSeg(Pt(x + w - rx, y)))
    segs.add(CubicSeg(Pt(x + w - rx + cx, y), Pt(x + w, y + ry - cy), Pt(x + w, y + ry)))
    segs.add(LineSeg(Pt(x + w, y + h - ry)))
    segs.add(CubicSeg(Pt(x + w, y + h - ry + cy), Pt(x + w - rx + cx, y + h), Pt(x + w - rx, y + h)))
    segs.add(LineSeg(Pt(x + rx, y + h)))
    segs.add(CubicSeg(Pt(x + rx - cx, y + h), Pt(x, y + h - ry + cy), Pt(x, y + h - ry)))
    segs.add(LineSeg(Pt(x, y + ry)))
    segs.add(CubicSeg(Pt(x, y + ry - cy), Pt(x + rx - cx, y), Pt(x + rx, y)))
    return listOf(SubPath(Pt(x + rx, y), segs, closed = true))
}

private fun ellipseSubpaths(node: XmlNode, circle: Boolean): List<SubPath> {
    val cx = node.attr("cx")?.toDoubleOrNull() ?: 0.0
    val cy = node.attr("cy")?.toDoubleOrNull() ?: 0.0
    val rx: Double; val ry: Double
    if (circle) {
        val r = node.attr("r")?.toDoubleOrNull() ?: return emptyList(); rx = r; ry = r
    } else {
        rx = node.attr("rx")?.toDoubleOrNull() ?: return emptyList()
        ry = node.attr("ry")?.toDoubleOrNull() ?: return emptyList()
    }
    if (rx <= 0 || ry <= 0) return emptyList()
    val ox = rx * KAPPA; val oy = ry * KAPPA
    val segs = listOf(
        CubicSeg(Pt(cx + rx, cy + oy), Pt(cx + ox, cy + ry), Pt(cx, cy + ry)),
        CubicSeg(Pt(cx - ox, cy + ry), Pt(cx - rx, cy + oy), Pt(cx - rx, cy)),
        CubicSeg(Pt(cx - rx, cy - oy), Pt(cx - ox, cy - ry), Pt(cx, cy - ry)),
        CubicSeg(Pt(cx + ox, cy - ry), Pt(cx + rx, cy - oy), Pt(cx + rx, cy)),
    )
    return listOf(SubPath(Pt(cx + rx, cy), segs, closed = true))
}

private fun lineSubpaths(node: XmlNode): List<SubPath> {
    val x1 = node.attr("x1")?.toDoubleOrNull() ?: 0.0
    val y1 = node.attr("y1")?.toDoubleOrNull() ?: 0.0
    val x2 = node.attr("x2")?.toDoubleOrNull() ?: 0.0
    val y2 = node.attr("y2")?.toDoubleOrNull() ?: 0.0
    return listOf(SubPath(Pt(x1, y1), listOf(LineSeg(Pt(x2, y2))), closed = false))
}

private fun polySubpaths(node: XmlNode, closed: Boolean): List<SubPath> {
    val nums = node.attr("points")?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }?.mapNotNull { it.toDoubleOrNull() }
        ?: return emptyList()
    if (nums.size < 4) return emptyList()
    val start = Pt(nums[0], nums[1])
    val segs = ArrayList<Seg>()
    var i = 2
    while (i + 1 < nums.size) { segs.add(LineSeg(Pt(nums[i], nums[i + 1]))); i += 2 }
    return listOf(SubPath(start, segs, closed))
}

// --------------------------------------------------------------------------------------------------
// Color parsing
// --------------------------------------------------------------------------------------------------

/**
 * Parses a color to an ARGB int, or null for "none"/unsupported. [alphaFirst] selects the 8/4-digit
 * hex byte order: VectorDrawable is `#AARRGGBB` (alpha first), SVG is `#RRGGBBAA` (alpha last).
 */
internal fun parseColor(s0: String?, alphaFirst: Boolean): Int? {
    val s = s0?.trim() ?: return null
    if (s.isEmpty() || s.equals("none", true) || s.equals("transparent", true)) return null
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        fun h(i: Int) = hex[i].digitToIntOrNull(16) ?: 0
        return when (hex.length) {
            3 -> argb(255, h(0) * 17, h(1) * 17, h(2) * 17)
            4 -> if (alphaFirst) argb(h(0) * 17, h(1) * 17, h(2) * 17, h(3) * 17)
            else argb(h(3) * 17, h(0) * 17, h(1) * 17, h(2) * 17)
            6 -> argb(255, h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
            8 -> if (alphaFirst) argb(h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5), h(6) * 16 + h(7))
            else argb(h(6) * 16 + h(7), h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
            else -> null
        }
    }
    if (s.startsWith("rgb")) {
        val inside = s.substringAfter('(', "").substringBefore(')')
        val parts = inside.split(',').map { it.trim() }
        if (parts.size >= 3) {
            fun ch(p: String): Int =
                if (p.endsWith("%")) ((p.dropLast(1).toDoubleOrNull() ?: 0.0) * 255 / 100).toInt().coerceIn(0, 255)
                else (p.toDoubleOrNull() ?: 0.0).toInt().coerceIn(0, 255)
            val a = if (parts.size >= 4) ((parts[3].toDoubleOrNull() ?: 1.0) * 255).toInt().coerceIn(0, 255) else 255
            return argb(a, ch(parts[0]), ch(parts[1]), ch(parts[2]))
        }
    }
    return NAMED_COLORS[s.lowercase()]
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

internal fun alphaOf(argb: Int) = (argb ushr 24) and 0xFF
internal fun redOf(argb: Int) = (argb ushr 16) and 0xFF
internal fun greenOf(argb: Int) = (argb ushr 8) and 0xFF
internal fun blueOf(argb: Int) = argb and 0xFF

/** Strips a CSS/Android length unit (px, dp, pt, …) and parses the leading number. */
private fun dimen(s: String?): Double? {
    if (s == null) return null
    val t = s.trim()
    val end = t.indexOfFirst { !(it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E') }
    val numPart = if (end < 0) t else t.substring(0, end)
    return numPart.toDoubleOrNull()
}

private class Quad(val a: Double, val b: Double, val c: Double, val d: Double)
private operator fun Quad.component1() = a
private operator fun Quad.component2() = b
private operator fun Quad.component3() = c
private operator fun Quad.component4() = d

/** The full CSS3 / SVG named-color set (so `fill="rebeccapurple"`, `"dodgerblue"`, … resolve). */
private val NAMED_COLORS: Map<String, Int> = mapOf(
    "aliceblue" to argb(255, 240, 248, 255), "antiquewhite" to argb(255, 250, 235, 215),
    "aqua" to argb(255, 0, 255, 255), "aquamarine" to argb(255, 127, 255, 212),
    "azure" to argb(255, 240, 255, 255), "beige" to argb(255, 245, 245, 220),
    "bisque" to argb(255, 255, 228, 196), "black" to argb(255, 0, 0, 0),
    "blanchedalmond" to argb(255, 255, 235, 205), "blue" to argb(255, 0, 0, 255),
    "blueviolet" to argb(255, 138, 43, 226), "brown" to argb(255, 165, 42, 42),
    "burlywood" to argb(255, 222, 184, 135), "cadetblue" to argb(255, 95, 158, 160),
    "chartreuse" to argb(255, 127, 255, 0), "chocolate" to argb(255, 210, 105, 30),
    "coral" to argb(255, 255, 127, 80), "cornflowerblue" to argb(255, 100, 149, 237),
    "cornsilk" to argb(255, 255, 248, 220), "crimson" to argb(255, 220, 20, 60),
    "cyan" to argb(255, 0, 255, 255), "darkblue" to argb(255, 0, 0, 139),
    "darkcyan" to argb(255, 0, 139, 139), "darkgoldenrod" to argb(255, 184, 134, 11),
    "darkgray" to argb(255, 169, 169, 169), "darkgreen" to argb(255, 0, 100, 0),
    "darkgrey" to argb(255, 169, 169, 169), "darkkhaki" to argb(255, 189, 183, 107),
    "darkmagenta" to argb(255, 139, 0, 139), "darkolivegreen" to argb(255, 85, 107, 47),
    "darkorange" to argb(255, 255, 140, 0), "darkorchid" to argb(255, 153, 50, 204),
    "darkred" to argb(255, 139, 0, 0), "darksalmon" to argb(255, 233, 150, 122),
    "darkseagreen" to argb(255, 143, 188, 143), "darkslateblue" to argb(255, 72, 61, 139),
    "darkslategray" to argb(255, 47, 79, 79), "darkslategrey" to argb(255, 47, 79, 79),
    "darkturquoise" to argb(255, 0, 206, 209), "darkviolet" to argb(255, 148, 0, 211),
    "deeppink" to argb(255, 255, 20, 147), "deepskyblue" to argb(255, 0, 191, 255),
    "dimgray" to argb(255, 105, 105, 105), "dimgrey" to argb(255, 105, 105, 105),
    "dodgerblue" to argb(255, 30, 144, 255), "firebrick" to argb(255, 178, 34, 34),
    "floralwhite" to argb(255, 255, 250, 240), "forestgreen" to argb(255, 34, 139, 34),
    "fuchsia" to argb(255, 255, 0, 255), "gainsboro" to argb(255, 220, 220, 220),
    "ghostwhite" to argb(255, 248, 248, 255), "gold" to argb(255, 255, 215, 0),
    "goldenrod" to argb(255, 218, 165, 32), "gray" to argb(255, 128, 128, 128),
    "green" to argb(255, 0, 128, 0), "greenyellow" to argb(255, 173, 255, 47),
    "grey" to argb(255, 128, 128, 128), "honeydew" to argb(255, 240, 255, 240),
    "hotpink" to argb(255, 255, 105, 180), "indianred" to argb(255, 205, 92, 92),
    "indigo" to argb(255, 75, 0, 130), "ivory" to argb(255, 255, 255, 240),
    "khaki" to argb(255, 240, 230, 140), "lavender" to argb(255, 230, 230, 250),
    "lavenderblush" to argb(255, 255, 240, 245), "lawngreen" to argb(255, 124, 252, 0),
    "lemonchiffon" to argb(255, 255, 250, 205), "lightblue" to argb(255, 173, 216, 230),
    "lightcoral" to argb(255, 240, 128, 128), "lightcyan" to argb(255, 224, 255, 255),
    "lightgoldenrodyellow" to argb(255, 250, 250, 210), "lightgray" to argb(255, 211, 211, 211),
    "lightgreen" to argb(255, 144, 238, 144), "lightgrey" to argb(255, 211, 211, 211),
    "lightpink" to argb(255, 255, 182, 193), "lightsalmon" to argb(255, 255, 160, 122),
    "lightseagreen" to argb(255, 32, 178, 170), "lightskyblue" to argb(255, 135, 206, 250),
    "lightslategray" to argb(255, 119, 136, 153), "lightslategrey" to argb(255, 119, 136, 153),
    "lightsteelblue" to argb(255, 176, 196, 222), "lightyellow" to argb(255, 255, 255, 224),
    "lime" to argb(255, 0, 255, 0), "limegreen" to argb(255, 50, 205, 50),
    "linen" to argb(255, 250, 240, 230), "magenta" to argb(255, 255, 0, 255),
    "maroon" to argb(255, 128, 0, 0), "mediumaquamarine" to argb(255, 102, 205, 170),
    "mediumblue" to argb(255, 0, 0, 205), "mediumorchid" to argb(255, 186, 85, 211),
    "mediumpurple" to argb(255, 147, 112, 219), "mediumseagreen" to argb(255, 60, 179, 113),
    "mediumslateblue" to argb(255, 123, 104, 238), "mediumspringgreen" to argb(255, 0, 250, 154),
    "mediumturquoise" to argb(255, 72, 209, 204), "mediumvioletred" to argb(255, 199, 21, 133),
    "midnightblue" to argb(255, 25, 25, 112), "mintcream" to argb(255, 245, 255, 250),
    "mistyrose" to argb(255, 255, 228, 225), "moccasin" to argb(255, 255, 228, 181),
    "navajowhite" to argb(255, 255, 222, 173), "navy" to argb(255, 0, 0, 128),
    "oldlace" to argb(255, 253, 245, 230), "olive" to argb(255, 128, 128, 0),
    "olivedrab" to argb(255, 107, 142, 35), "orange" to argb(255, 255, 165, 0),
    "orangered" to argb(255, 255, 69, 0), "orchid" to argb(255, 218, 112, 214),
    "palegoldenrod" to argb(255, 238, 232, 170), "palegreen" to argb(255, 152, 251, 152),
    "paleturquoise" to argb(255, 175, 238, 238), "palevioletred" to argb(255, 219, 112, 147),
    "papayawhip" to argb(255, 255, 239, 213), "peachpuff" to argb(255, 255, 218, 185),
    "peru" to argb(255, 205, 133, 63), "pink" to argb(255, 255, 192, 203),
    "plum" to argb(255, 221, 160, 221), "powderblue" to argb(255, 176, 224, 230),
    "purple" to argb(255, 128, 0, 128), "rebeccapurple" to argb(255, 102, 51, 153),
    "red" to argb(255, 255, 0, 0), "rosybrown" to argb(255, 188, 143, 143),
    "royalblue" to argb(255, 65, 105, 225), "saddlebrown" to argb(255, 139, 69, 19),
    "salmon" to argb(255, 250, 128, 114), "sandybrown" to argb(255, 244, 164, 96),
    "seagreen" to argb(255, 46, 139, 87), "seashell" to argb(255, 255, 245, 238),
    "sienna" to argb(255, 160, 82, 45), "silver" to argb(255, 192, 192, 192),
    "skyblue" to argb(255, 135, 206, 235), "slateblue" to argb(255, 106, 90, 205),
    "slategray" to argb(255, 112, 128, 144), "slategrey" to argb(255, 112, 128, 144),
    "snow" to argb(255, 255, 250, 250), "springgreen" to argb(255, 0, 255, 127),
    "steelblue" to argb(255, 70, 130, 180), "tan" to argb(255, 210, 180, 140),
    "teal" to argb(255, 0, 128, 128), "thistle" to argb(255, 216, 191, 216),
    "tomato" to argb(255, 255, 99, 71), "turquoise" to argb(255, 64, 224, 208),
    "violet" to argb(255, 238, 130, 238), "wheat" to argb(255, 245, 222, 179),
    "white" to argb(255, 255, 255, 255), "whitesmoke" to argb(255, 245, 245, 245),
    "yellow" to argb(255, 255, 255, 0), "yellowgreen" to argb(255, 154, 205, 50),
)
