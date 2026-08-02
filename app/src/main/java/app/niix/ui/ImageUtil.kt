package app.niix.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min

object ImageUtil {

    private const val MAX_DIMEN = 256

    /**
     * Decodes [uri], downscales to a small square-ish thumbnail and re-encodes to JPEG.
     * Re-encoding from raw pixels drops all EXIF metadata (location, device, timestamps).
     */
    fun processProfile(context: Context, uri: Uri): ByteArray? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        val scale = min(MAX_DIMEN.toFloat() / bitmap.width, MAX_DIMEN.toFloat() / bitmap.height).coerceAtMost(1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
