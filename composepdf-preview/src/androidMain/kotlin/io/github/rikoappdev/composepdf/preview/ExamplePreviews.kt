package io.github.rikoappdev.composepdf.preview

import androidx.compose.runtime.Composable
import io.github.rikoappdev.composepdf.examples.ExampleDocuments
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Ready-to-open design-time previews. Open this file in Android Studio and the preview pane renders
 * these live — no app run, no export. Copy the pattern for your own document: wrap any
 * `pdfDocument { … }` spec in a `@Preview @Composable` that calls [PdfPreview] with
 * [previewFontRegular] / [previewFontBold] (or your own font bytes). Editing the builder re-renders.
 */
@Preview
@Composable
fun InvoicePreview() =
    PdfPreview(ExampleDocuments.invoice(), previewFontRegular(), previewFontBold())

@Preview
@Composable
fun AnnualReportPreview() =
    PdfPreview(ExampleDocuments.annualReport(), previewFontRegular(), previewFontBold())

@Preview
@Composable
fun ServiceAgreementPreview() =
    PdfPreview(ExampleDocuments.serviceAgreement(), previewFontRegular(), previewFontBold())

@Preview
@Composable
fun ResumePreview() =
    PdfPreview(ExampleDocuments.resume(), previewFontRegular(), previewFontBold())

/** Multi-page report whose header embeds an Android VectorDrawable badge via `vector()`. */
@Preview
@Composable
fun FieldServiceReportPreview() =
    PdfPreview(ExampleDocuments.fieldServiceReport(emptyList()), previewFontRegular(), previewFontBold())
