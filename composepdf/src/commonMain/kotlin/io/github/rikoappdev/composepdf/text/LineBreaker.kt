package io.github.rikoappdev.composepdf.text

import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.font.TextMetrics

/** Greedy word-wrap. Honors explicit `\n` as hard breaks; a word wider than the column is
 *  placed alone (it may overflow — long-word breaking is a later refinement). Deterministic:
 *  decisions come only from integer font-unit measurements. */
internal object LineBreaker {

    fun wrap(text: String, weight: FontWeight, fontSizePt: Int, maxWidthPt: Int, book: TextMetrics): List<String> {
        val out = ArrayList<String>()
        for (hardLine in text.split('\n')) {
            if (hardLine.isEmpty()) { out.add(""); continue }
            val words = hardLine.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || book.measureWidthPt(candidate, weight, fontSizePt) <= maxWidthPt) {
                    line = StringBuilder(candidate)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }
}
