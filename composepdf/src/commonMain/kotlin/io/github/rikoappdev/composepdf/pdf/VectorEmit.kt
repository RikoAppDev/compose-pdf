package io.github.rikoappdev.composepdf.pdf

import io.github.rikoappdev.composepdf.util.fmtNum
import io.github.rikoappdev.composepdf.vector.CubicSeg
import io.github.rikoappdev.composepdf.vector.LineSeg
import io.github.rikoappdev.composepdf.vector.VectorModel
import io.github.rikoappdev.composepdf.vector.alphaOf
import io.github.rikoappdev.composepdf.vector.blueOf
import io.github.rikoappdev.composepdf.vector.greenOf
import io.github.rikoappdev.composepdf.vector.redOf
import kotlin.math.ceil

/**
 * Renders a [VectorModel] into a Form XObject content stream + its resource dictionary, ready to be
 * registered once and drawn (`Do`) on any page. The form's BBox is the viewport; an initial
 * `1 0 0 -1 0 vh cm` flips the model's Y-down space into PDF's Y-up space, so paths are emitted in
 * raw viewport coordinates. Per-path opacity (< 1) is handled with `/ExtGState` `/ca` `/CA`
 * dictionaries; fully opaque paths emit none. All numbers use the deterministic [fmtNum] formatter.
 */
internal fun buildVectorForm(model: VectorModel): VectorForm {
    val vw = ceil(model.viewportW).toInt().coerceAtLeast(1)
    val vh = ceil(model.viewportH).toInt().coerceAtLeast(1)

    val gstates = LinkedHashMap<String, String>() // dict body -> name
    val sb = StringBuilder()
    // Flip Y-down model space to PDF Y-up. Pivot on the TRUE viewport height (not the ceiled BBox
    // height) so content anchors at the form origin and registers correctly; for the common integer
    // viewport (e.g. 24×24, 44×63) this is identical to the BBox height, so output is unchanged.
    sb.append("1 0 0 -1 0 ").append(fmtNum(model.viewportH)).append(" cm\n")

    for (path in model.paths) {
        val fill = path.fillArgb
        val stroke = path.strokeArgb
        val fillEff = if (fill != null) (alphaOf(fill) / 255.0) * path.fillAlpha else 0.0
        val strokeEff = if (stroke != null) (alphaOf(stroke) / 255.0) * path.strokeAlpha else 0.0
        val doFill = fill != null && fillEff > 0.0
        val doStroke = stroke != null && strokeEff > 0.0 && path.strokeWidth > 0.0
        if (!doFill && !doStroke) continue
        if (path.subpaths.isEmpty()) continue

        sb.append("q\n")
        if ((doFill && fillEff < 1.0) || (doStroke && strokeEff < 1.0)) {
            val body = "/ca ${fmtNum(if (doFill) fillEff else 1.0)} /CA ${fmtNum(if (doStroke) strokeEff else 1.0)}"
            val name = gstates.getOrPut(body) { "GS${gstates.size}" }
            sb.append('/').append(name).append(" gs\n")
        }
        if (doFill) {
            sb.append(comp01(redOf(fill))).append(' ').append(comp01(greenOf(fill))).append(' ')
                .append(comp01(blueOf(fill))).append(" rg\n")
        }
        if (doStroke) {
            sb.append(comp01(redOf(stroke))).append(' ').append(comp01(greenOf(stroke))).append(' ')
                .append(comp01(blueOf(stroke))).append(" RG\n")
            sb.append(fmtNum(path.strokeWidth)).append(" w\n")
        }
        for (sub in path.subpaths) {
            sb.append(fmtNum(sub.start.x)).append(' ').append(fmtNum(sub.start.y)).append(" m\n")
            for (seg in sub.segs) when (seg) {
                is LineSeg -> sb.append(fmtNum(seg.to.x)).append(' ').append(fmtNum(seg.to.y)).append(" l\n")
                is CubicSeg -> sb.append(fmtNum(seg.c1.x)).append(' ').append(fmtNum(seg.c1.y)).append(' ')
                    .append(fmtNum(seg.c2.x)).append(' ').append(fmtNum(seg.c2.y)).append(' ')
                    .append(fmtNum(seg.to.x)).append(' ').append(fmtNum(seg.to.y)).append(" c\n")
            }
            if (sub.closed) sb.append("h\n")
        }
        sb.append(
            when {
                doFill && doStroke -> if (path.evenOdd) "B*\n" else "B\n"
                doFill -> if (path.evenOdd) "f*\n" else "f\n"
                else -> "S\n"
            }
        )
        sb.append("Q\n")
    }

    val resources = if (gstates.isEmpty()) "<< >>"
    else "<< /ExtGState << " + gstates.entries.joinToString(" ") { "/${it.value} << ${it.key} >>" } + " >> >>"

    return VectorForm(vw, vh, asciiBytes(sb.toString()), resources, model)
}

/** A color component (0..255) as a deterministic 0..1 decimal string. */
private fun comp01(c: Int): String = fmtNum(c / 255.0)
