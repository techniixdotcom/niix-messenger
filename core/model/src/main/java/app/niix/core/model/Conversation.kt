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
    val pending: Boolean = false,
    /** For a GROUP conversation: the last-applied GroupInvite epoch, used to reject replayed
     * older invites (e.g. one captured before a member was removed). Unused for DIRECT. */
    val epoch: Long = 0,
)

data class GroupMember(
    val conversationId: String,
    val memberOnion: OnionAddress,
    val role: GroupRole,
)
