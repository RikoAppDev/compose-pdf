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

internal actual fun readPreviewResourceBytes(path: String): ByteArray {
    val cl = Thread.currentThread().contextClassLoader
        ?: object {}.javaClass.classLoader
        ?: ClassLoader.getSystemClassLoader()
    val stream = cl.getResourceAsStream(path)
        ?: error("Preview resource not found on classpath: $path")
    return stream.use { it.readBytes() }
}
