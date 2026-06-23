# compose-pdf

A **pure-Kotlin Kotlin Multiplatform** library that generates **vector** PDFs whose output is
**identical across devices**, with **selectable/searchable text**, **small files**, automatic
**pagination**, authored with a **Compose-style DSL**. Output does not depend on the device
font-scale or the host UI lifecycle.

Coordinates: `io.github.rikoappdev:compose-pdf` · Targets: **Android + iOS + JVM** · License: **Apache-2.0**.

The published artifact bundles **no font** — you pass your own (see [Fonts](#fonts)).

## Why a custom engine

To be *identical to the dot* **and** vector/searchable at once, **no per-platform text engine may
touch layout**, and the PDF must contain real text operators with an embedded font (so it can't be
rasterized — and no public Kotlin Multiplatform PDF backend exists for vector output on iOS). So
all layout, text shaping, glyph positioning, TrueType subsetting and PDF serialization run in
shared `commonMain` **integer** math.

**"Identical"** = every glyph's `(x,y)` origin and the extracted Unicode match across platforms
(engineered to exact integer equality). Raw file bytes may differ (compression/float) — invisible
to users.

## Features

- Embedded **subset Type0/CIDFontType2 (Identity-H) + ToUnicode** → selectable & searchable text, including Latin diacritics (composite glyphs subset correctly).
- **Compose-style DSL**: `text`, `spacer`, `divider`, `row { cell(weight) { } }`, `column`, `box(padding, border, background)`, `keyValue(label, value)`, `image` / `photoGrid` (JPEG `/DCTDecode` pass-through **and PNG** — decoded in pure Kotlin to a `/FlateDecode` image with an `/SMask` for transparency; `PhotoFit.Cover` / `Contain` / `Smart` — smart preserves aspect but crops extreme strips), `table` (weighted columns, repeating header, total rows, optional `zebra` striping).
- **Vector images (SVG + Android VectorDrawable)**: `vector(bytes, …)` imports both formats (auto-detected) into native, resolution-independent PDF **vector paths** — the full SVG/VectorDrawable path grammar (incl. elliptical arcs), basic shapes (`rect`/`circle`/`ellipse`/`line`/`poly…`), `<group>`/`transform`, nonzero & even-odd fill, stroke, per-element opacity, `currentColor` and the full CSS named-color set. Embedded as a reusable **Form XObject**, so a logo repeated in a header costs a single object. Pure-Kotlin, dependency-free (no XML library).
- **Header / footer / page numbers**: repeating `header`/`footer` bands and an auto page-number line whose space is reserved (content never overlaps it). `PageConfig` controls it all — `repeatHeader` (every page vs. first page only, like a title block), `pageNumberFormat`, `pageNumberStyle`, `pageNumbers`.
- Familiar value types: `TextStyle` (with `copy`), `PdfColor`/`Color(0xFF…)`, `Dp`/`.dp`, `Sp`/`.sp`, `FontWeight`, `TextAlign`.
- **Automatic pagination**: paragraphs split by line; tables split by row (repeating the header); bordered **boxes and columns split across pages** with the border/background redrawn per fragment; rows and images stay atomic (never cut). Optional keep-together moves a block whole instead of leaving a sliver.
- **Progress reporting**: `render(regular, bold, onProgress)` calls the optional `onProgress: (Float) -> Unit` with `0f`→`1f` as pages are laid out and serialized — drive a real determinate progress bar. Omit it and output is byte-for-byte unchanged.
- **FlateDecode compression**: content streams, the subset font program and the ToUnicode CMap are deflated by a pure-Kotlin encoder (deterministic on every platform).
- Regular + Bold faces (bundled).

```kotlin
val pdf: ByteArray = pdfDocument(PageConfig(margin = 36.dp)) {
    header { row { cell(1f) { text("ACME Inc.", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)) } }; divider() }
    text("Report", TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
    table(columns = listOf(PdfColumn(3f, "Item"), PdfColumn(1f, "Qty", TextAlign.End))) {
        row("Item A", "3"); row("Item B", "7"); totalRow("Total", "10")
    }
    photoGrid(jpegBytesList, perRow = 3, cellHeight = 80.dp)
}.render(regularFontBytes, boldFontBytes)
```

## Gallery

A set of ready-made example documents ships in `commonMain` under
[`examples/ExampleDocuments.kt`](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt);
[`GalleryExportTest`](composepdf/src/jvmTest/kotlin/io/github/rikoappdev/composepdf/GalleryExportTest.kt)
renders each one. Regenerate with `./gradlew :composepdf:jvmTest --tests "*GalleryExportTest"`.
**Every entry below links its source code → the exported PDF** (the preview image opens the PDF too).
Two of them embed a logo via `vector()` — the field-service report uses an Android **VectorDrawable**,
the catalogue an **SVG**.

> Every value in these documents is invented, generic placeholder data — the fictitious
> Contoso / Northwind / Fabrikam companies and reserved `.example` addresses. They exist only to
> demonstrate the engine (text flow, weighted rows, nested rounded boxes, tables with zebra
> striping / totals / repeating headers, automatic pagination, page numbers, vector + image layout).

| | |
|---|---|
| **Field service report** — repeating header (VectorDrawable badge) + footer + page numbers, bordered record cards with 3-column summaries, totals, photo grid &amp; signatures; **5 pages**.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L453) · [PDF](samples/field-service-report.pdf)<br><a href="samples/field-service-report.pdf"><img src="samples/field-service-report.png" width="330" alt="Field service report example"></a> | **Annual report** — title + metric cards, 120-row ledger with a repeating header, zebra striping, periodic subtotals and a grand total; **5 pages**.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L598) · [PDF](samples/annual-report.pdf)<br><a href="samples/annual-report.pdf"><img src="samples/annual-report.png" width="330" alt="Annual report example"></a> |
| **Product catalogue** — SVG brand mark in the header, categorized tables interleaved with photo grids; **3 pages**.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L667) · [PDF](samples/product-catalog.pdf)<br><a href="samples/product-catalog.pdf"><img src="samples/product-catalog.png" width="330" alt="Product catalogue example"></a> | **Service agreement** — 16 numbered sections of wrapped paragraphs (keep-together) + a signatures block; **6 pages**.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L758) · [PDF](samples/service-agreement.pdf)<br><a href="samples/service-agreement.pdf"><img src="samples/service-agreement.png" width="330" alt="Service agreement example"></a> |
| **Invoice** — weighted header columns, line-item table, stacked totals.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L78) · [PDF](samples/invoice.pdf)<br><a href="samples/invoice.pdf"><img src="samples/invoice.png" width="330" alt="Invoice example"></a> | **Business letter** — letterhead and automatically wrapped body paragraphs.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L155) · [PDF](samples/business-letter.pdf)<br><a href="samples/business-letter.pdf"><img src="samples/business-letter.png" width="330" alt="Business letter example"></a> |
| **Price list** — repeating header band, multiple categorized tables.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L205) · [PDF](samples/price-list.pdf)<br><a href="samples/price-list.pdf"><img src="samples/price-list.png" width="330" alt="Price list example"></a> | **Status report** — summary box, metric cards, milestones table.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L268) · [PDF](samples/status-report.pdf)<br><a href="samples/status-report.pdf"><img src="samples/status-report.png" width="330" alt="Status report example"></a> |
| **Transaction ledger** — 90 rows over **3 pages**, repeating table header + page numbers.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L344) · [PDF](samples/transaction-ledger.pdf)<br><a href="samples/transaction-ledger.pdf"><img src="samples/transaction-ledger.png" width="330" alt="Transaction ledger example"></a> | **Photo gallery** — mixed aspect ratios laid out with `PhotoFit.Smart` / `Contain`.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L391) · [PDF](samples/photo-gallery.pdf)<br><a href="samples/photo-gallery.pdf"><img src="samples/photo-gallery.png" width="330" alt="Photo gallery example"></a> |
| **Résumé** — weighted two-column CV, section headings, a skills table; 1 page.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L833) · [PDF](samples/resume.pdf)<br><a href="samples/resume.pdf"><img src="samples/resume.png" width="330" alt="Résumé example"></a> | **Event program** — header band + agenda schedule tables; 1 page.<br>[code](composepdf/src/commonMain/kotlin/io/github/rikoappdev/composepdf/examples/ExampleDocuments.kt#L919) · [PDF](samples/event-program.pdf)<br><a href="samples/event-program.pdf"><img src="samples/event-program.png" width="330" alt="Event program example"></a> |

## Fonts

Fonts are supplied by **your application**, not bundled in the library. `render` takes the Regular
and Bold face bytes; the engine subsets and embeds only the glyphs a document uses. This keeps the
library font-agnostic and dependency-free, and gives identical output on every platform — the app
reads its own `.ttf` (via Compose Resources, Android assets, a file, the network, …) and passes the
bytes in.

A typical app keeps one **default** face and optionally lets the user pick another for export:

```kotlin
val regular: ByteArray = loadFont(selectedFont ?: defaultFont)        // your resource mechanism
val bold: ByteArray = loadFont((selectedFont ?: defaultFont).bold)
val pdf = document.render(regular, bold)
```

Any TrueType font works. For Latin diacritics (e.g. Czech/Slovak/Polish) pick a face that covers
Latin Extended-A/B, such as Noto Sans or DejaVu Sans.

## Building & testing

```
./gradlew :composepdf:jvmTest                          # identity + feature gates (incl. cross-platform golden)
./gradlew :composepdf:compileCommonMainKotlinMetadata  # shared-code purity check
./gradlew :composepdf:compileAndroidMain               # Android target
./gradlew :composepdf:iosSimulatorArm64Test            # runs the golden on iOS (macOS only)
```
Requires JDK 17+ (CI uses 21). The cross-platform golden test runs the layout engine over a fixed
document with deterministic metrics and asserts identical integer glyph origins on every platform.
Generated test PDFs/PNGs are written under `composepdf/build/`.

## Roadmap

- Live `@Composable` preview bridge — draw the engine's computed glyph/box positions onto a Compose `Canvas` for an on-screen preview (the PDF stays the source of truth).
- GPOS kerning / ligatures (v1 uses advance-width shaping).
- More image formats (JPEG + PNG today; WebP/others later).
- Complex scripts / RTL / bidi.
- Emoji & color fonts — the engine subsets a single monochrome outline (`glyf`) TrueType face, so emoji and color-glyph fonts (COLR/CPAL, CBDT, sbix) are **not rendered yet** (codepoints with no outline in the supplied face fall back to a missing glyph); needs color-glyph support or an emoji fallback face.
- Long-word breaking inside narrow columns.
