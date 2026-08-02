package app.niix.core.model

enum class ConversationType {
    DIRECT,
    GROUP,
}

enum class GroupRole {
    ADMIN,
    MEMBER,
}

data class Conversation(
    val id: String,
    val type: ConversationType,
    val title: String,
    val disappearSeconds: Long,
    val createdAtEpochMillis: Long,
)

data class GroupMember(
    val conversationId: String,
    val memberOnion: OnionAddress,
    val role: GroupRole,
)
