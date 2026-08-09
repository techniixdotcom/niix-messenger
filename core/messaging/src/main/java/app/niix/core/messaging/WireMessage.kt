package app.niix.core.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

sealed class WireMessage {

    data class Text(
        val conversationId: String,
        val messageId: String,
        val senderOnion: String,
        val body: String,
        val expiresSeconds: Long,
    ) : WireMessage()

    data class DeleteForEveryone(
        val conversationId: String,
        val targetMessageIds: List<String>,
    ) : WireMessage()

    data class TimerUpdate(
        val conversationId: String,
        val seconds: Long,
    ) : WireMessage()

    data class Receipt(
        val conversationId: String,
        val messageId: String,
        val state: String,
    ) : WireMessage()

    data class GroupInvite(
        val conversationId: String,
        val title: String,
        val members: List<String>,
        val admins: List<String>,
        /** Monotonic counter, incremented on every membership-changing action (creation, add,
         * remove, promote/demote). Lets a recipient reject a replayed older invite -- e.g. one
         * captured before a member was removed -- rather than reprocessing it as current. */
        val epoch: Long = 1,
    ) : WireMessage()

    data class AttachmentOffer(
        val conversationId: String,
        val messageId: String,
        val senderOnion: String,
        val attachmentId: String,
        val mimeType: String,
        val sizeBytes: Long,
        val encKey: ByteArray,
        val digest: ByteArray?,
        val expiresSeconds: Long,
    ) : WireMessage()

    data class ProfileUpdate(
        val senderOnion: String,
        val image: ByteArray?,
        /** Null for a personal profile-photo update (stored under [senderOnion]); set to a
         * group conversation id when an admin is pushing that group's photo to its members
         * (stored under the group id instead, shared by everyone in it). */
        val conversationId: String? = null,
    ) : WireMessage()
}

object WireCodec {

    private const val VERSION = 1
    private const val TYPE_TEXT = 1
    private const val TYPE_DELETE = 2
    private const val TYPE_TIMER = 3
    private const val TYPE_RECEIPT = 4
    private const val TYPE_GROUP_INVITE = 5
    private const val TYPE_ATTACHMENT = 6
    private const val TYPE_PROFILE = 7

    fun encode(message: WireMessage): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.writeByte(VERSION)
            when (message) {
                is WireMessage.Text -> {
                    s.writeByte(TYPE_TEXT)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.messageId)
                    s.writeUTF(message.senderOnion)
                    s.writeUTF(message.body)
                    s.writeLong(message.expiresSeconds)
                }
                is WireMessage.DeleteForEveryone -> {
                    s.writeByte(TYPE_DELETE)
                    s.writeUTF(message.conversationId)
                    writeStringList(s, message.targetMessageIds)
                }
                is WireMessage.TimerUpdate -> {
                    s.writeByte(TYPE_TIMER)
                    s.writeUTF(message.conversationId)
                    s.writeLong(message.seconds)
                }
                is WireMessage.Receipt -> {
                    s.writeByte(TYPE_RECEIPT)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.messageId)
                    s.writeUTF(message.state)
                }
                is WireMessage.GroupInvite -> {
                    s.writeByte(TYPE_GROUP_INVITE)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.title)
                    writeStringList(s, message.members)
                    writeStringList(s, message.admins)
                    s.writeLong(message.epoch)
                }
                is WireMessage.AttachmentOffer -> {
                    s.writeByte(TYPE_ATTACHMENT)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.messageId)
                    s.writeUTF(message.senderOnion)
                    s.writeUTF(message.attachmentId)
                    s.writeUTF(message.mimeType)
                    s.writeLong(message.sizeBytes)
                    writeBlock(s, message.encKey)
                    writeOptionalBlock(s, message.digest)
                    s.writeLong(message.expiresSeconds)
                }
                is WireMessage.ProfileUpdate -> {
                    s.writeByte(TYPE_PROFILE)
                    s.writeUTF(message.senderOnion)
                    writeOptionalBlock(s, message.image)
                    writeOptionalString(s, message.conversationId)
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): WireMessage {
        DataInputStream(ByteArrayInputStream(bytes)).use { s ->
            val version = s.readByte().toInt()
            require(version == VERSION) { "Unsupported wire version $version" }
            return when (val type = s.readByte().toInt()) {
                TYPE_TEXT -> WireMessage.Text(
                    conversationId = s.readUTF(),
                    messageId = s.readUTF(),
                    senderOnion = s.readUTF(),
                    body = s.readUTF(),
                    expiresSeconds = s.readLong(),
                )
                TYPE_DELETE -> WireMessage.DeleteForEveryone(
                    conversationId = s.readUTF(),
                    targetMessageIds = readStringList(s),
                )
                TYPE_TIMER -> WireMessage.TimerUpdate(
                    conversationId = s.readUTF(),
                    seconds = s.readLong(),
                )
                TYPE_RECEIPT -> WireMessage.Receipt(
                    conversationId = s.readUTF(),
                    messageId = s.readUTF(),
                    state = s.readUTF(),
                )
                TYPE_GROUP_INVITE -> WireMessage.GroupInvite(
                    conversationId = s.readUTF(),
                    title = s.readUTF(),
                    members = readStringList(s),
                    admins = readStringList(s),
                    epoch = s.readLong(),
                )
                TYPE_ATTACHMENT -> WireMessage.AttachmentOffer(
                    conversationId = s.readUTF(),
                    messageId = s.readUTF(),
                    senderOnion = s.readUTF(),
                    attachmentId = s.readUTF(),
                    mimeType = s.readUTF(),
                    sizeBytes = s.readLong(),
                    encKey = readBlock(s),
                    digest = readOptionalBlock(s),
                    expiresSeconds = s.readLong(),
                )
                TYPE_PROFILE -> WireMessage.ProfileUpdate(
                    senderOnion = s.readUTF(),
                    image = readOptionalBlock(s),
                    conversationId = readOptionalString(s),
                )
                else -> throw IllegalArgumentException("Unknown wire type $type")
            }
        }
    }

    private fun writeStringList(s: DataOutputStream, items: List<String>) {
        s.writeInt(items.size)
        items.forEach(s::writeUTF)
    }

    private fun readStringList(s: DataInputStream): List<String> {
        val count = s.readInt()
        require(count in 0..MAX_LIST) { "Invalid list size $count" }
        return List(count) { s.readUTF() }
    }

    private fun writeBlock(s: DataOutputStream, data: ByteArray) {
        s.writeInt(data.size)
        s.write(data)
    }

    private fun readBlock(s: DataInputStream): ByteArray {
        val length = s.readInt()
        require(length in 0..MAX_BLOCK) { "Invalid block length $length" }
        return ByteArray(length).also { s.readFully(it) }
    }

    private fun writeOptionalBlock(s: DataOutputStream, data: ByteArray?) {
        if (data == null) {
            s.writeBoolean(false)
        } else {
            s.writeBoolean(true)
            writeBlock(s, data)
        }
    }

    private fun readOptionalBlock(s: DataInputStream): ByteArray? =
        if (s.readBoolean()) readBlock(s) else null

    private fun writeOptionalString(s: DataOutputStream, value: String?) {
        if (value == null) {
            s.writeBoolean(false)
        } else {
            s.writeBoolean(true)
            s.writeUTF(value)
        }
    }

    private fun readOptionalString(s: DataInputStream): String? =
        if (s.readBoolean()) s.readUTF() else null

    private const val MAX_LIST = 4096
    private const val MAX_BLOCK = 1 shl 20
}
