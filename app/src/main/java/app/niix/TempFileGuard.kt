package app.niix

import android.content.Context
import java.io.File

/**
 * Deletes the plaintext copies the app has to write out temporarily.
 *
 * Attachments are stored encrypted, but viewing or sharing one means writing a decrypted copy
 * somewhere another app can read via FileProvider, and taking a photo means writing the capture
 * before it's encrypted. Those copies are plaintext on disk. Purging them whenever the app locks
 * keeps the window they exist in as short as possible, so a locked device isn't holding readable
 * versions of things the database is carefully encrypting.
 *
 * Cleans both the current location (`cache/shared/`, which is the only path FileProvider is
 * scoped to) and the cache root, where earlier builds put these files -- otherwise upgrading
 * would silently orphan whatever was already sitting there.
 */
object TempFileGuard {

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
