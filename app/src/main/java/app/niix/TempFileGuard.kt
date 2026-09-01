package app.niix

import android.content.Context

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
