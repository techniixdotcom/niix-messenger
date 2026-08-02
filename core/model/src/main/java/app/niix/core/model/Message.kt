package app.niix.core.model

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
)
