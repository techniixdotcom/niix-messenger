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
    /** Non-null when this message should disappear a set time after it's actually *read* --
     * this is that duration, in seconds. [expiresAtEpochMillis] stays null (the countdown
     * hasn't started) until the recipient reads it, so a message can never vanish before the
     * person it was sent to has had a chance to see it, even if they were offline for a while. */
    val disappearSeconds: Long? = null,
)
