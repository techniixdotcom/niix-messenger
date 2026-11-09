package app.niix

import android.content.Context
import java.io.File

/**
 * Deletes the plaintext copies the app has to write temporarily.
 *
 * Viewing or sharing an attachment means writing a decrypted copy where another app can read it
 * through FileProvider, and a new photo exists unencrypted until we store it. These are purged
 * whenever the app locks, a minute after it's left even without a passcode, and when the lock
 * screen opens, so a locked phone isn't holding readable copies of things we otherwise keep
 * encrypted.
 *
 * Cleans cache/shared/ (the only place FileProvider can see) and the cache root, where older builds
 * put these files.
 */
object TempFileGuard {

    // Prefixes, so "att_" also covers "att_crop_" and "open_" covers "open_avatar_".
    private val PREFIXES = listOf("cam_", "rec_", "att_", "open_")

    fun purge(context: Context) {
        runCatching {
            val cacheRoot = context.cacheDir ?: return
            purgeDirectory(File(cacheRoot, "shared"))
            purgeDirectory(cacheRoot)
        }
    }

    private fun purgeDirectory(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && PREFIXES.any { file.name.startsWith(it) }) {
                file.delete()
            }
        }
    }
}
