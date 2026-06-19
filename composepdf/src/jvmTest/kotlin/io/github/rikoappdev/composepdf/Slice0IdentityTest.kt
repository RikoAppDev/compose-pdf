package io.github.rikoappdev.composepdf

import io.github.rikoappdev.composepdf.font.TrueTypeFont
import io.github.rikoappdev.composepdf.font.subsetFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 0 identity gate. Proves the core bet on the JVM:
 *  1. embedded subset font renders selectable text,
 *  2. PDFBox extracts the exact Slovak/Czech/Polish Unicode (searchable),
 *  3. the file is small,
 *  4. composite (accented) glyphs are correctly retained in the subset.
 */
class Slice0IdentityTest {

    private val fontPath = "src/commonMain/resources/font/NotoSans-Regular.ttf"
    // Neutral typographic pangrams that exercise Latin-Extended diacritics (no real names/domain).
    private val text = "Příliš žluťoučký kůň úpěl ďábelské ódy zażółć gęślą jaźń"

    private fun fontBytes(): ByteArray {
        val f = File(fontPath)
        assertTrue(f.exists(), "Bundled font not found at $fontPath (cwd=${File(".").absolutePath})")
        return f.readBytes()
    }

    @Test
    fun allGlyphsPresent() {
        val font = TrueTypeFont(fontBytes())
        val missing = text.filter { it != ' ' }.toSet().filter { font.gidForCodePoint(it.code) == 0 }
        assertTrue(missing.isEmpty(), "Font is missing glyphs for: ${missing.map { "$it U+${it.code.toString(16)}" }}")
    }

    @Test
    fun textIsSearchableAndIdentical() {
        val pdf = ComposePdf.buildSinglePageText(fontBytes(), text)
        // Persist for manual inspection (open in Adobe/Preview to confirm selectability).
        File("build").mkdirs()
        File("build/slice0.pdf").writeBytes(pdf)

        Loader.loadPDF(pdf).use { doc ->
            assertEquals(1, doc.numberOfPages, "expected a single page")
            val extracted = PDFTextStripper().getText(doc).trim()
            assertEquals(text, extracted, "extracted text must equal source Unicode")
        }
    }

    @Test
    fun fileIsSmall() {
        val pdf = ComposePdf.buildSinglePageText(fontBytes(), text)
        assertTrue(pdf.size < 60_000, "Slice 0 PDF should be < 60 KB, was ${pdf.size}")
        println("Slice 0 PDF size = ${pdf.size} bytes")
    }

    @Test
    fun compositeGlyphsAreRetainedInSubset() {
        val original = TrueTypeFont(fontBytes())
        val usedGids = HashSet<Int>()
        for (cp in text.map { it.code }) usedGids.add(original.gidForCodePoint(cp))

        val compositeUsed = usedGids.filter { original.isComposite(it) }
        println("composite glyphs used: ${compositeUsed.size} of ${usedGids.size}")

        val subset = subsetFont(original, usedGids)
        val reparsed = TrueTypeFont(subset.bytes)

        // Every component of every composite glyph we use must have real outline data in the subset.
        var checkedComponents = 0
        for (g in compositeUsed) {
            for (component in original.compositeComponents(g)) {
                assertTrue(
                    reparsed.glyphBytes(component).isNotEmpty() || original.glyphBytes(component).isEmpty(),
                    "composite component glyph $component (of $g) was dropped from the subset",
                )
                checkedComponents++
            }
        }
        // The accented Latin glyphs in Noto Sans are composites — make sure the gate is meaningful.
        assertTrue(compositeUsed.isNotEmpty(), "expected at least one composite glyph among the accented text")
        println("verified $checkedComponents composite components retained")
    }
}
