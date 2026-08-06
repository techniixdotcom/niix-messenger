package app.niix.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import app.niix.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class CropActivity : AppCompatActivity() {

    private var cropView: CropImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.let { Uri.parse(it) }
        val bitmap = sourceUri?.let { loadOrientedBitmap(it) }
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.crop_load_failed), Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val view = CropImageView(this)
        cropView = view
        findViewById<FrameLayout>(R.id.crop_container).addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view.setImageBitmap(bitmap)

        findViewById<MaterialButton>(R.id.crop_done).setOnClickListener { exportCrop() }
    }

    private fun exportCrop() {
        val cropped = cropView?.cropToBitmap(OUTPUT_SIZE)
        if (cropped == null) {
            Toast.makeText(this, getString(R.string.crop_load_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(cacheDir, "att_crop_${System.currentTimeMillis()}.jpg")
        val saved = runCatching {
            FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        }.isSuccess
        if (!saved) {
            Toast.makeText(this, getString(R.string.crop_load_failed), Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.absolutePath))
        finish()
    }

    /**
     * Decodes the source image downsampled to a sane working size (avoids OOM on a full-size
     * camera photo), and bakes in its EXIF rotation as real pixels -- re-encoding a cropped JPEG
     * later would otherwise silently drop that tag and the exported photo would come out sideways.
     *
     * Reads the source [uri] into memory exactly once. Some content providers (cloud-backed
     * gallery items, "virtual"/streamed documents, certain file managers) only support a single
     * read of a given Uri and fail or return truncated data on a second open -- opening the
     * stream three separate times (bounds, full decode, EXIF) intermittently broke the picker
     * for photos from those providers. Reading once and reusing the bytes works everywhere.
     */
    private fun loadOrientedBitmap(uri: Uri): Bitmap? {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        if (bounds.outHeight > MAX_WORKING_DIMEN || bounds.outWidth > MAX_WORKING_DIMEN) {
            val halfHeight = bounds.outHeight / 2
            val halfWidth = bounds.outWidth / 2
            while (halfHeight / inSampleSize >= MAX_WORKING_DIMEN && halfWidth / inSampleSize >= MAX_WORKING_DIMEN) {
                inSampleSize *= 2
            }
        }

        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val rotation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ).let {
                when (it) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)

        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_RESULT_PATH = "result_path"
        private const val OUTPUT_SIZE = 480
        private const val MAX_WORKING_DIMEN = 1600
    }
}
