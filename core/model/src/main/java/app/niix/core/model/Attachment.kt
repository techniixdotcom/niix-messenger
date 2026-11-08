package app.niix.core.model

enum class AttachmentState {
    PENDING,
    COMPLETE,
    FAILED,
}

data class Attachment(
    val id: String,
    val conversationId: String,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val encKey: ByteArray,
    val digest: ByteArray?,
    val state: AttachmentState,
    val createdAtEpochMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            filePath == other.filePath &&
            mimeType == other.mimeType &&
            sizeBytes == other.sizeBytes &&
            encKey.contentEquals(other.encKey) &&
            (digest?.contentEquals(other.digest ?: ByteArray(0)) ?: (other.digest == null)) &&
            state == other.state &&
            createdAtEpochMillis == other.createdAtEpochMillis
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + encKey.contentHashCode()
        result = 31 * result + state.hashCode()
        return result
    }
}
