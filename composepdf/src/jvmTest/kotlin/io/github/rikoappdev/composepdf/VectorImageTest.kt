package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.vector.parsePathData
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end checks for the vector importer (VectorDrawable + SVG → PDF Form XObject vector paths).
 * Renders documents with PDFBox at 150 DPI and inspects pixels — no golden, just behavioral asserts:
 * a synthetic badge renders red and its even-odd counter is a hole (proved by comparing against the
 * same path filled nonzero, which has none), and a hand-written SVG renders its rect / circle / arc
 * in the right colors and left-to-right order (proving shapes, transforms, arcs and color parsing).
 */
class VectorImageTest {

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    /**
     * A square with a round counter drawn as four cubic curves. The two subpaths wind the same way
     * (clockwise on screen), so nonzero fills the counter and only even-odd punches it out.
     */
    private val badgePath =
        "M 4 4 L 44 4 L 44 44 L 4 44 Z " +
            "M 34 24 C 34 29.523 29.523 34 24 34 C 18.477 34 14 29.523 14 24 " +
            "C 14 18.477 18.477 14 24 14 C 29.523 14 34 18.477 34 24 Z"

    private fun badgeXml(fillType: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="48dp" android:height="48dp"
            android:viewportWidth="48" android:viewportHeight="48">
            <path android:pathData="$badgePath" android:fillColor="#d32f2f" android:fillType="$fillType"/>
        </vector>
    """.trimIndent().encodeToByteArray()

    private fun render(bytes: ByteArray, name: String): BufferedImage {
        val pdf = pdfDocument(PageConfig(margin = 36.dp)) {
            vector(bytes, height = 60.dp)
        }.render(regular(), bold())
        File("build").mkdirs()
        File("build/$name.pdf").writeBytes(pdf)
        return Loader.loadPDF(pdf).use { d ->
            PDFRenderer(d).renderImageWithDPI(0, 150f).also { ImageIO_write(it, "build/$name.png") }
        }
    }

    private fun ImageIO_write(img: BufferedImage, path: String) {
        javax.imageio.ImageIO.write(img, "png", File(path))
    }

    private fun isRed(c: AwtColor) = c.red > 170 && c.green < 90 && c.blue < 90
    private fun isWhite(c: AwtColor) = c.red > 200 && c.green > 200 && c.blue > 200

    private fun countRed(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (isRed(AwtColor(img.getRGB(x, y)))) n++
        return n
    }

    /**
     * Counts white pixels fully enclosed by red on all four sides — i.e. inside the shape's red area
     * but not painted. The badge's only such region is the round counter punched by the second
     * subpath, so a non-trivial count proves the even-odd hole actually rendered (a single contour
     * can never produce an enclosed hole).
     */
    private fun countEnclosedWhite(img: BufferedImage): Int {
        val w = img.width; val h = img.height
        val minRedX = IntArray(h) { Int.MAX_VALUE }; val maxRedX = IntArray(h) { -1 }
        val minRedY = IntArray(w) { Int.MAX_VALUE }; val maxRedY = IntArray(w) { -1 }
        for (y in 0 until h) for (x in 0 until w) if (isRed(AwtColor(img.getRGB(x, y)))) {
            if (x < minRedX[y]) minRedX[y] = x; if (x > maxRedX[y]) maxRedX[y] = x
            if (y < minRedY[x]) minRedY[x] = y; if (y > maxRedY[x]) maxRedY[x] = y
        }
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x > minRedX[y] && x < maxRedX[y] && y > minRedY[x] && y < maxRedY[x] &&
                isWhite(AwtColor(img.getRGB(x, y)))
            ) n++
        }
        return n
    }

    @Test
    fun vectorRendersRedWithEvenOddHole() {
        val img = render(badgeXml("evenOdd"), "vector-badge-evenodd")
        // The vector renders in its fill color.
        assertTrue(countRed(img) > 2000, "Expected a sizable red region from the badge, got ${countRed(img)} px")
        // The even-odd counter is a true hole: a cluster of white pixels enclosed by red on all sides.
        val enclosed = countEnclosedWhite(img)
        assertTrue(enclosed > 30, "Even-odd counter hole not visible (enclosed-white=$enclosed px)")
        // The same path filled nonzero paints the counter, so the hole above comes from the fill rule.
        val nonZero = countEnclosedWhite(render(badgeXml("nonZero"), "vector-badge-nonzero"))
        assertTrue(nonZero == 0, "Nonzero fill left a hole in the counter (enclosed-white=$nonZero px)")
    }

    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <rect x="10" y="10" width="30" height="30" fill="#0000ff"/>
            <circle cx="70" cy="30" r="15" fill="#00ff00"/>
            <g transform="translate(0,40)">
                <path d="M10 20 a 20 20 0 0 1 40 0 l -40 0 z" fill="#ff00ff"/>
            </g>
        </svg>
    """.trimIndent().encodeToByteArray()

    @Test
    fun svgRendersShapesColorsAndOrder() {
        val pdf = pdfDocument(PageConfig(margin = 36.dp)) {
            vector(svg, width = 200.dp, height = 200.dp, fit = PhotoFit.Contain)
        }.render(regular(), bold())
        File("build").mkdirs()
        File("build/vector-svg.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { d ->
            val img = PDFRenderer(d).renderImageWithDPI(0, 150f)
            ImageIO_write(img, "build/vector-svg.png")

            var blue = 0; var blueXSum = 0L
            var green = 0; var greenXSum = 0L
            var magenta = 0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val c = AwtColor(img.getRGB(x, y))
                if (c.blue > 180 && c.red < 90 && c.green < 90) { blue++; blueXSum += x }
                if (c.green > 150 && c.red < 90 && c.blue < 90) { green++; greenXSum += x }
                if (c.red > 150 && c.blue > 150 && c.green < 90) magenta++
            }
            assertTrue(blue > 1000, "SVG <rect> (blue) did not render: $blue px")
            assertTrue(green > 1000, "SVG <circle> (green) did not render: $green px")
            assertTrue(magenta > 1000, "SVG <path> with arc (magenta) did not render: $magenta px")
            // The blue rect (x≈10–40) sits left of the green circle (cx≈70): proves geometry + no mirroring.
            assertTrue(blueXSum / blue < greenXSum / green, "Blue rect should be left of green circle")
        }
    }

    /** Runs [block] on a daemon thread and fails if it doesn't finish — catches parser infinite loops
     *  without hanging the whole test run. */
    private fun assertCompletes(timeoutMs: Long = 5000, block: () -> Unit) {
        val t = Thread { block() }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
        assertTrue(!t.isAlive, "Path parsing did not terminate within ${timeoutMs}ms — likely an infinite loop")
    }

    @Test
    fun malformedPathsTerminate() {
        // Each of these previously hung the parser (numbers after Z re-ran the no-op Z forever; a
        // non-number token left num() unable to advance). They must now parse and return promptly.
        assertCompletes {
            parsePathData("M0 0 L1 1 Z 2 2")
            parsePathData("M0 0Z5 5")
            parsePathData("M0 0 L %")
            parsePathData("L%")
            parsePathData("99 88 77")      // starts with bare numbers, no command
            parsePathData("M0 0 C @ ! ?")  // garbage where numbers are expected
        }
    }

    private fun isBlack(c: AwtColor) = c.red < 60 && c.green < 60 && c.blue < 60

    /** `currentColor` (defaults to black) must paint; a CSS named color (rebeccapurple = 102,51,153)
     *  must resolve — both previously rendered invisible (resolved to null = no paint). */
    @Test
    fun currentColorAndNamedColorsRender() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                <rect x="0" y="0" width="50" height="100" fill="currentColor"/>
                <rect x="50" y="0" width="50" height="100" fill="rebeccapurple"/>
            </svg>
        """.trimIndent().encodeToByteArray()
        val img = render(svg, "vector-colorkeywords")
        var black = 0; var purple = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = AwtColor(img.getRGB(x, y))
            if (isBlack(c)) black++
            if (c.red in 70..130 && c.green in 25..80 && c.blue in 120..185) purple++
        }
        assertTrue(black > 1000, "fill=currentColor did not paint (black=$black px)")
        assertTrue(purple > 1000, "fill=rebeccapurple did not resolve (purple=$purple px)")
    }

    /** A DOCTYPE with an internal subset has '>' inside the brackets; the reader must skip to "]>"
     *  and still find the <svg> root (previously truncated → "No XML root"). */
    @Test
    fun svgWithDoctypeInternalSubsetParses() {
        val svg = (
            "<?xml version=\"1.0\"?>\n" +
                "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"svg11.dtd\" [ <!ENTITY foo \"bar\"> ]>\n" +
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">" +
                "<rect x=\"10\" y=\"10\" width=\"80\" height=\"80\" fill=\"#0000ff\"/></svg>"
            ).encodeToByteArray()
        val img = render(svg, "vector-doctype")
        var blue = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = AwtColor(img.getRGB(x, y))
            if (c.blue > 180 && c.red < 90 && c.green < 90) blue++
        }
        assertTrue(blue > 1000, "SVG with DOCTYPE internal subset did not render (blue=$blue px)")
    }
}
