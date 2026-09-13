package app.niix.core.model

const val MAX_DISAPPEAR_SECONDS: Long = 28L * 24 * 60 * 60

fun clampDisappearSeconds(seconds: Long): Long = seconds.coerceIn(0, MAX_DISAPPEAR_SECONDS)

enum class MessageDirection {
    OUTGOING,
    INCOMING,
}

enum class MessageType {
    TEXT,
    ATTACHMENT,
    SYSTEM,
}

enum class DeliveryState {
    PENDING,
    SENT,

    RELAYED,
    DELIVERED,
    FAILED,
    RECEIVED,
}

data class Message(
    val id: String,
    val conversationId: String,
    val senderOnion: String,
    val direction: MessageDirection,
    val type: MessageType,
    val body: String,
    val attachmentId: String?,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val deliveryState: DeliveryState,
    val deleted: Boolean,
    val remoteDeletable: Boolean,

    val disappearSeconds: Long? = null,
    /** Id of the message this one replies to, or null. Stored as a plain id rather than a copy
     * of the quoted text so a reply cannot preserve content the sender later deletes. */
    val replyToId: String? = null,
    /**
     * Whether the user has revealed this message past the blur.
     *
     * Persisted rather than kept in memory: a message you have already read should not hide
     * itself again after a lock or a restart. The blur exists to stop a glance at an unlocked
     * phone showing new messages -- not to make you re-tap things you have seen.
     */
    val revealed: Boolean = false,
)
