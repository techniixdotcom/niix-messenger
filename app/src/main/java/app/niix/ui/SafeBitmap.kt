package app.niix.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object SafeBitmap {

    /**
     * About 4096x4096, roughly 67 MB decoded. Bigger than any phone screen, small enough not to run
     * out of memory.
     */
    private const val MAX_PIXELS = 16_777_216

    /** Rejects absurd declared sizes before doing any sampling maths. */
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

    /** Null if the image can't be read or claims implausible dimensions. */
    private fun optionsFor(bounds: BitmapFactory.Options): BitmapFactory.Options? {
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null

        // Halve until it fits. inSampleSize has to be a power of two; the decoder rounds anything
        // else down, which would leave the result too big.
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS) {
            sample *= 2
            if (sample > 1024) return null
        }
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }
}
