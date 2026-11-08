package app.niix.core.storage

import java.io.File

object AttachmentFiles {

    val ID_PATTERN: Regex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValidId(attachmentId: String): Boolean = ID_PATTERN.matches(attachmentId)

    fun resolve(attachmentsDir: File, attachmentId: String): File? {
        if (!isValidId(attachmentId)) return null
        val root = attachmentsDir.canonicalFile
        val candidate = File(attachmentsDir, "$attachmentId.enc").canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator)) return null
        return candidate
    }

    fun delete(attachmentsDir: File, attachmentId: String): Boolean {
        val file = resolve(attachmentsDir, attachmentId) ?: return false
        return file.delete()
    }
}
