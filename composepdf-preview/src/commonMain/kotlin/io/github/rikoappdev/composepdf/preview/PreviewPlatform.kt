package io.github.rikoappdev.composepdf.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily

/** Builds a Compose [FontFamily] from Regular + Bold TrueType bytes (platform font-from-bytes API). */
internal expect fun previewFontFamily(regular: ByteArray, bold: ByteArray): FontFamily

/** Decodes a [PreviewImageSource] into a Compose [ImageBitmap] using the platform image pipeline. */
internal expect fun decodePreviewImage(source: PreviewImageSource): ImageBitmap

/**
 * Reads a bundled classpath resource **synchronously** — required for the IDE `@Preview` runtime,
 * where the async Compose-resources API does not work.
 */
internal expect fun readPreviewResourceBytes(path: String): ByteArray
