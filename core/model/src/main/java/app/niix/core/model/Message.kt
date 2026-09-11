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
)
