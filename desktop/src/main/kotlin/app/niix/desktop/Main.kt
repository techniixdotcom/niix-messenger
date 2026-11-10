package app.niix.desktop

import app.niix.core.storage.StoragePaths
import app.niix.desktop.ui.NiixApp
import java.util.Properties
import kotlin.system.exitProcess

/** The version this build was made as, from version.properties at build time. */
object Version {
    val name: String by lazy {
        runCatching {
            Properties().apply {
                Version::class.java.getResourceAsStream("/app/niix/desktop/build.properties")?.use(::load)
            }.getProperty("versionName")
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: "dev"
    }
}

/**
 * Starts NiiX for Linux. One instance per user: starting it again brings the running one's window
 * to the front. With --background the first start shows no window, for starting at login.
 */
fun main(args: Array<String>) {
    if ("--version" in args) {
        println("NiiX ${Version.name}")
        return
    }
    if ("--help" in args || "-h" in args) {
        println("Usage: niix [--background] [--version]")
        return
    }
    val unknown = args.filterNot { it == "--background" }
    if (unknown.isNotEmpty()) {
        System.err.println("niix: unknown option ${unknown.first()}")
        exitProcess(2)
    }
    val paths = StoragePaths.fromEnvironment()
    // Native libraries the JDBC driver unpacks go here rather than to the shared /tmp.
    val nativeDir = java.io.File(paths.cacheDir, "native")
    StoragePaths.ensurePrivateDirectory(nativeDir)
    System.setProperty("org.sqlite.tmpdir", nativeDir.absolutePath)
    System.setProperty("java.io.tmpdir", nativeDir.absolutePath)
    val container = DesktopContainer(paths)
    container.tempFiles.purge()
    val status = NiixApp(container, startHidden = "--background" in args).run()
    container.tempFiles.purge()
    exitProcess(status)
}
