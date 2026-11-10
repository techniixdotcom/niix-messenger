package app.niix.desktop

import app.niix.core.storage.StoragePaths
import java.io.File
import java.util.UUID

/**
 * The plaintext copies the desktop app has to write: an attachment decrypted to be opened in another
 * program or played, and a voice message while it's being recorded. They live in one folder of the
 * cache, and are deleted whenever the app locks, when it starts and when it quits, so a locked NiiX
 * isn't leaving readable copies of what it otherwise keeps encrypted.
 */
class TempFiles(cacheDir: File) {

    private val dir = File(cacheDir, DIR_NAME)

    /** A new empty file in the private temporary folder, named so its type is recognised. */
    fun create(prefix: String, extension: String): File {
        StoragePaths.ensurePrivateDirectory(dir)
        val safeExtension = extension.filter { it.isLetterOrDigit() }.take(10)
        val name = prefix + UUID.randomUUID().toString().replace("-", "") + if (safeExtension.isEmpty()) "" else ".$safeExtension"
        val file = File(dir, name)
        file.createNewFile()
        StoragePaths.makePrivate(file)
        return file
    }

    fun purge() {
        runCatching { dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } } }
    }

    private companion object {
        const val DIR_NAME = "shared"
    }
}
