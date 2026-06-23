package io.github.rikoappdev.composepdf.preview

private const val FONT_DIR = "composepdf_preview_font"

/**
 * Regular face bytes of a font bundled in this preview artifact (Noto Sans, OFL-1.1), loaded
 * **synchronously** so it works inside the IDE `@Preview` runtime with zero setup. Use these only
 * for previews/screenshots — the core library stays font-agnostic and bundles no font; for the real
 * export pass your app's own `.ttf` to `render()`.
 */
fun previewFontRegular(): ByteArray = readPreviewResourceBytes("$FONT_DIR/NotoSans-Regular.ttf")

/** Bold face bytes of the bundled preview font (see [previewFontRegular]). */
fun previewFontBold(): ByteArray = readPreviewResourceBytes("$FONT_DIR/NotoSans-Bold.ttf")
