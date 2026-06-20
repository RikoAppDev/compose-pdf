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
- **Compose-style DSL**: `text`, `spacer`, `divider`, `row { cell(weight) { } }`, `column`, `box(padding, border, background)`, `keyValue(label, value)`, `image` / `photoGrid` (JPEG `/DCTDecode` pass-through; `cover` crops to fill or preserves the original aspect ratio), `table` (weighted columns, repeating header, total rows, optional `zebra` striping).
- **Header / footer / page numbers**: repeating `header`/`footer` bands and an auto page-number line whose space is reserved (content never overlaps it). `PageConfig` controls it all — `repeatHeader` (every page vs. first page only, like a title block), `pageNumberFormat`, `pageNumberStyle`, `pageNumbers`.
- Familiar value types: `TextStyle` (with `copy`), `PdfColor`/`Color(0xFF…)`, `Dp`/`.dp`, `Sp`/`.sp`, `FontWeight`, `TextAlign`.
- **Automatic pagination**: paragraphs split by line; tables split by row (repeating the header); bordered **boxes and columns split across pages** with the border/background redrawn per fragment; rows and images stay atomic (never cut). Optional keep-together moves a block whole instead of leaving a sliver.
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

- GPOS kerning / ligatures (v1 uses advance-width shaping).
- More image formats (currently JPEG `/DCTDecode` only).
- Complex scripts / RTL / bidi.
- Long-word breaking inside narrow columns.
