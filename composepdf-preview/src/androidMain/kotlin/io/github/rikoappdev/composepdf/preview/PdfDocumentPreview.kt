package io.github.rikoappdev.composepdf.preview

import androidx.compose.ui.tooling.preview.Preview

/**
 * The page width (in dp) that the `@PdfDocumentPreview…` annotations below are sized for. Pass it to
 * [PdfPreview] so the baked-in `widthDp`/`heightDp` match what the preview actually draws:
 *
 * ```
 * @PdfDocumentPreview3Pages
 * @Composable
 * private fun MyReportPreview() =
 *     PdfPreview(buildMyReportSpec(sample, labels), previewFontRegular(), previewFontBold(),
 *         pageWidth = PreviewPageWidthDp.dp)
 * ```
 */
const val PreviewPageWidthDp: Int = 360

/*
 * Ready-made Android Studio multipreview annotations for a whole [PdfPreview] document, one per page
 * count (1–5). Pick the one that matches how many A4 pages your report renders to and the IDE pane is
 * sized to show every page in full — no empty strip below, nothing trimmed off the bottom.
 *
 * Why a fixed set instead of auto-sizing: [PdfPreview] already computes the exact total height itself
 * (sum of the page heights), but Android Studio's static `@Preview` needs `heightDp` as a compile-time
 * CONSTANT — it can't read a value computed at runtime. So the height for each page count is precomputed
 * here for [PreviewPageWidthDp] (360dp) pages with the default 16dp gap:
 *   widthDp  = 360 + 2*16                = 392
 *   heightDp = 16 + pages * (360*842/595 + 16)   // A4 = 595*842 pt
 * Use these with `PdfPreview(…, pageWidth = PreviewPageWidthDp.dp)`. (Changing the page width or gap
 * changes the right height — recompute with the formula above if you override them.)
 */

/** Preview sized for a 1-page document. */
@Preview(name = "PDF · 1 page", showBackground = true, widthDp = 392, heightDp = 542)
annotation class PdfDocumentPreview

/** Preview sized for a 2-page document. */
@Preview(name = "PDF · 2 pages", showBackground = true, widthDp = 392, heightDp = 1067)
annotation class PdfDocumentPreview2Pages

/** Preview sized for a 3-page document. */
@Preview(name = "PDF · 3 pages", showBackground = true, widthDp = 392, heightDp = 1593)
annotation class PdfDocumentPreview3Pages

/** Preview sized for a 4-page document. */
@Preview(name = "PDF · 4 pages", showBackground = true, widthDp = 392, heightDp = 2118)
annotation class PdfDocumentPreview4Pages

/** Preview sized for a 5-page document. */
@Preview(name = "PDF · 5 pages", showBackground = true, widthDp = 392, heightDp = 2644)
annotation class PdfDocumentPreview5Pages
