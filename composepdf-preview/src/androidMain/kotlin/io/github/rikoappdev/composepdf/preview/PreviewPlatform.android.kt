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

internal actual fun readPreviewResourceBytes(path: String): ByteArray = readBundledResource(path)

/**
 * Reads a resource bundled in this artifact's jar across the classloaders that may be in play in
 * Android Studio's `@Preview` (layoutlib) runtime. The loader that loaded THIS class is tried first:
 * when the artifact is consumed as an AAR, the font lives in that classloader's `classes.jar`, while
 * the thread context classloader is often layoutlib's and cannot see it (which caused the previous
 * "Preview resource not found on classpath" failure in downstream `@Preview`s).
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
