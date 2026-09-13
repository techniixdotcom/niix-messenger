package app.niix.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Decodes images with a bound on the decoded result, not just on the file.
 *
 * Compressed size says nothing about decoded size. A few hundred kilobytes of PNG can declare
 * dimensions that expand to gigabytes in memory once decoded -- a "decompression bomb". Every
 * image here arrives from another person, so calling BitmapFactory directly on one means letting
 * a remote party choose an allocation size. The result is an out-of-memory kill, which for this
 * app means the messenger dies whenever it displays an image someone sent.
 *
 * The fix is the standard two-pass decode: read the header alone to learn the dimensions, work
 * out a downsample factor, then decode at that reduced size. Nothing is allocated at full size
 * at any point.
 */
object SafeBitmap {

    /** Roughly 4096x4096. Well beyond any phone display, and about 67MB decoded at 4 bytes per
     * pixel -- large enough never to affect a real photo, small enough to survive. */
    private const val MAX_PIXELS = 16_777_216

    /** Rejects absurd declared dimensions outright, before any sampling arithmetic. */
    private const val MAX_DIMENSION = 32_768

    fun decodeBytes(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = optionsFor(bounds) ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    fun decodeFile(path: String): Bitmap? = runCatching {
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = optionsFor(bounds) ?: return null
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

    /** Null when the image is unreadable or its declared dimensions are implausible. */
    private fun optionsFor(bounds: BitmapFactory.Options): BitmapFactory.Options? {
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null

        // Halve until the pixel count fits. inSampleSize must be a power of two; anything else is
        // rounded down by the decoder, which would leave the result larger than intended.
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS) {
            sample *= 2
            if (sample > 1024) return null
        }
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }
}
