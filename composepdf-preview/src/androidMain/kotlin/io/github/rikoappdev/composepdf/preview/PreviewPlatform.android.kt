package io.github.rikoappdev.composepdf.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import java.io.File

internal actual fun previewFontFamily(regular: ByteArray, bold: ByteArray): FontFamily = FontFamily(
    Font(tempTtf(regular, "cp-preview-regular"), FontWeight.Normal, FontStyle.Normal),
    Font(tempTtf(bold, "cp-preview-bold"), FontWeight.Bold, FontStyle.Normal),
)

private fun tempTtf(bytes: ByteArray, name: String): File {
    val f = File.createTempFile(name, ".ttf")
    f.deleteOnExit()
    f.writeBytes(bytes)
    return f
}

internal actual fun decodePreviewImage(source: PreviewImageSource): ImageBitmap = when (source) {
    is PreviewEncodedImage ->
        BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size).asImageBitmap()
    is PreviewRasterImage ->
        Bitmap.createBitmap(source.argb, source.pixelWidth, source.pixelHeight, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
}

internal actual fun readPreviewResourceBytes(path: String): ByteArray {
    val cl = Thread.currentThread().contextClassLoader
        ?: object {}.javaClass.classLoader
        ?: ClassLoader.getSystemClassLoader()
    val stream = cl.getResourceAsStream(path)
        ?: error("Preview resource not found on classpath: $path")
    return stream.use { it.readBytes() }
}
