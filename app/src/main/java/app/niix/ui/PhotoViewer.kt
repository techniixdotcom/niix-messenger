package app.niix.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a profile photo full screen. The viewer opens files, so the photo is written to the cache
 * first; the viewer deletes it again when it closes.
 */
fun AppCompatActivity.showPhotoLarge(photo: Bitmap) {
    lifecycleScope.launch {
        val file = withContext(Dispatchers.IO) {
            val target = File(cacheDir, "open_avatar_${System.currentTimeMillis()}.jpg")
            runCatching {
                FileOutputStream(target).use { out -> photo.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                target
            }.getOrNull()
        } ?: return@launch
        startActivity(
            Intent(this@showPhotoLarge, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_PATH, file.absolutePath)
                .putExtra(MediaViewerActivity.EXTRA_MIME, "image/jpeg"),
        )
    }
}
