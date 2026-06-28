package io.github.rikoappdev.composepdf.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun previewFontFamily(regular: ByteArray, bold: ByteArray): FontFamily = FontFamily(
    Font("cp-preview-regular", regular, FontWeight.Normal, FontStyle.Normal),
    Font("cp-preview-bold", bold, FontWeight.Bold, FontStyle.Normal),
)

internal actual fun decodePreviewImage(source: PreviewImageSource): ImageBitmap = when (source) {
    is PreviewEncodedImage -> Image.makeFromEncoded(source.bytes).toComposeImageBitmap()
    is PreviewRasterImage -> rasterToImageBitmap(source.pixelWidth, source.pixelHeight, source.argb)
}

private fun rasterToImageBitmap(w: Int, h: Int, argb: IntArray): ImageBitmap {
    val bytes = ByteArray(w * h * 4)
    for (i in 0 until w * h) {
        val c = argb[i]
        bytes[i * 4] = ((c ushr 16) and 0xFF).toByte()
        bytes[i * 4 + 1] = ((c ushr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = (c and 0xFF).toByte()
        bytes[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte()
    }
    val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap()
}

internal actual fun readPreviewResourceBytes(path: String): ByteArray = readBundledResource(path)

/**
 * Reads a resource bundled in this artifact's jar, trying the classloader that loaded THIS class
 * first (it owns the bundled font), then the thread-context and system loaders. Mirrors the Android
 * actual so the IDE/desktop `@Preview` runtimes resolve the font the same robust way.
 */
private fun readBundledResource(path: String): ByteArray {
    val loaders = linkedSetOf<ClassLoader>()
    object {}.javaClass.classLoader?.let { loaders.add(it) }
    Thread.currentThread().contextClassLoader?.let { loaders.add(it) }
    ClassLoader.getSystemClassLoader()?.let { loaders.add(it) }
    for (cl in loaders) {
        (cl.getResourceAsStream(path) ?: cl.getResourceAsStream("/$path"))?.let { s ->
            return s.use { it.readBytes() }
        }
    }
    error("Preview resource not found on classpath: $path")
}
