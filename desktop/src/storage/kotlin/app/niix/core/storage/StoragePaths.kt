package app.niix.core.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where a desktop install keeps its data: the stand-ins for Android's filesDir, noBackupFilesDir
 * and cacheDir. Every folder is created readable by its owner only.
 */
class StoragePaths(val filesDir: File, val noBackupDir: File, val cacheDir: File) {

    init {
        listOf(filesDir, noBackupDir, cacheDir).forEach(::ensurePrivateDirectory)
    }

    companion object {

        /**
         * The XDG base directories, which inside a Flatpak are already the app's own private ones.
         * NIIX_DATA_DIR puts everything under one folder instead, for running several instances.
         */
        fun fromEnvironment(appName: String = "niix"): StoragePaths {
            System.getenv("NIIX_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { root ->
                val base = File(root).absoluteFile
                return StoragePaths(File(base, "files"), File(base, "private"), File(base, "cache"))
            }
            val home = File(System.getProperty("user.home"))
            val data = xdg("XDG_DATA_HOME", File(home, ".local/share"))
            val cache = xdg("XDG_CACHE_HOME", File(home, ".cache"))
            val base = File(data, appName)
            return StoragePaths(File(base, "files"), File(base, "private"), File(cache, appName))
        }

        private fun xdg(variable: String, fallback: File): File =
            System.getenv(variable)?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let(::File) ?: fallback

        fun ensurePrivateDirectory(dir: File) {
            dir.mkdirs()
            runCatching {
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }

        /** Restricts a file to its owner. Called before anything secret is written into it. */
        fun makePrivate(file: File) {
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
