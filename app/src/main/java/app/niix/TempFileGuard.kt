package app.niix

import android.content.Context

/**
 * Deletes short-lived files the app creates in the cache directory: camera captures and audio
 * recordings staged before sending, picked files staged before encrypting, and decrypted copies
 * made so an image/video/audio file could be viewed or opened externally.
 *
 * These are cleaned up immediately at their normal point of use (right after sending, or when
 * the in-app viewer closes). This is the backstop: a file opened in an *external* app (via
 * ACTION_VIEW) gives no reliable "the user is done" callback, so anything left over is removed
 * here -- called every time the app locks back to the calculator, which is the app's normal,
 * frequent "at rest" moment.
 */
object TempFileGuard {

    private val PREFIXES = listOf("cam_", "rec_", "att_", "open_")

    fun purge(context: Context) {
        runCatching {
            val dir = context.cacheDir ?: return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && PREFIXES.any { file.name.startsWith(it) }) {
                    file.delete()
                }
            }
        }
    }
}
