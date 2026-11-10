package app.niix.core.messaging

import app.niix.core.model.Device
import app.niix.core.model.DeviceList
import app.niix.core.model.OnionAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

sealed class WireMessage {

    data class Text(
        val conversationId: String,
        val messageId: String,
        val senderOnion: String,
        val body: String,
        val expiresSeconds: Long,
        /**
         * Id of the message being replied to. Only the id is sent, never the quoted text, so a
         * reply can't bring back something the sender has deleted.
         */
        val replyToId: String? = null,
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
        val epoch: Long = 1,
        val prevStateHash: String? = null,
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
        val conversationId: String? = null,
        val displayName: String? = null,
    ) : WireMessage()

    /**
     * Tells a contact they've been nominated to erase this device, or that they no longer are. The
     * nomination itself is the authorisation, carried over the authenticated session.
     */
    data class RemoteWipeNomination(
        val nominated: Boolean,
    ) : WireMessage()

    /**
     * A request to erase this device from a contact the user nominated. Never shown, stored or
     * acknowledged.
     */
    data class RemoteWipe(
        val token: String,
    ) : WireMessage()

    /**
     * A member leaving a group.
     *
     * Separate from GroupInvite because an update that removes its own sender looks exactly like
     * someone being ejected, and that rule is worth keeping. Leaving only ever removes the
     * authenticated sender, so it needs no member list.
     */
    data class GroupLeave(
        val conversationId: String,
        val epoch: Long,
    ) : WireMessage()

    data class SenderKeyDistribution(
        val conversationId: String,
        val senderOnion: String,
        val distributionBytes: ByteArray,
    ) : WireMessage()

    data class GroupCiphertext(
        val conversationId: String,
        val senderOnion: String,
        val ciphertext: ByteArray,
    ) : WireMessage()

    /**
     * Types 10 and 11 from the removed peer-to-peer relay. Older peers still send them, so they're
     * decoded and ignored instead of rejected. Never encoded.
     */
    data class Retired(val type: Int) : WireMessage()

    data class Dummy(val filler: ByteArray) : WireMessage()

    /**
     * "To reach me while I'm offline, publish to these Nostr relays with this mailbox secret."
     *
     * Only ever sent inside the Signal session, so the secret gets the same protection as message
     * content. It's specific to the one recipient. An empty secret withdraws an earlier
     * announcement.
     */
    data class NostrInbox(
        val secret: ByteArray,
        val relays: List<String>,
    ) : WireMessage()

    /** The devices the sending account is on. Sent to contacts and to the account's own devices. */
    data class Devices(val list: DeviceList) : WireMessage()

    /**
     * Between devices of one account only: something this account sent, or did, from another of its
     * devices. [inner] is the message as it went to [conversationId].
     */
    data class SyncSent(
        val conversationId: String,
        val inner: WireMessage,
    ) : WireMessage()

    /**
     * Between devices of one account only: a contact was added, renamed, verified, blocked or
     * unblocked on another device. [pinnedIdentityKey] is set when the contact's key is pinned
     * there. Unblocking is its own flag rather than [blocked] being false, so that a rename sent
     * before a block can't undo it when the two cross.
     */
    data class ContactUpdate(
        val onion: String,
        val displayName: String,
        val pinnedIdentityKey: ByteArray?,
        val verified: Boolean,
        val blocked: Boolean,
        val unblocked: Boolean = false,
        /**
         * Whether voice calls with the contact are allowed on the sending device. Null from a build
         * before calls, which says nothing either way.
         */
        val callsAllowed: Boolean? = null,
    ) : WireMessage()

    /**
     * Between devices of one account only: the account's new wipe key, after a device was removed.
     * [epoch] is the epoch of the device list it came with; a device keeps the newest key it has
     * seen. See WipeBeacon.
     */
    data class WipeKey(val key: ByteArray, val epoch: Long) : WireMessage()

    /**
     * "I allow voice calls with you", or no longer. A call needs both people to have sent true;
     * either one withdrawing is enough to stop them.
     */
    data class CallPermission(
        val allowed: Boolean,
        /**
         * With true: the key the recipient can ring the sender's locked devices with (see the
         * doorbell in CallManager). Added later, at the end, so older versions ignore it.
         */
        val ringKey: ByteArray? = null,
    ) : WireMessage()

    /**
     * Rings the recipient. [secret] is the caller's half of the call key; it travels only inside
     * the Signal session, so the call key is as strong as that session, post-quantum part included.
     * [sentAtMillis] is the caller's clock, used only to recognise an offer that waited too long.
     */
    data class CallOffer(
        val callId: String,
        val secret: ByteArray,
        val sentAtMillis: Long,
    ) : WireMessage()

    /** The callee picked up. [secret] is the callee's half of the call key. */
    data class CallAnswer(
        val callId: String,
        val secret: ByteArray,
    ) : WireMessage()

    /** Ends a call, or refuses one. [reason] is one of [CallEndReasons]. */
    data class CallEnd(
        val callId: String,
        val reason: Int,
    ) : WireMessage()
}

/** Why a call ended, as carried in [WireMessage.CallEnd]. */
object CallEndReasons {
    const val HANGUP = 1
    const val DECLINED = 2
    const val BUSY = 3
    const val NOT_PERMITTED = 4
    const val ANSWERED_ELSEWHERE = 5
    const val TIMEOUT = 6
    const val FAILED = 7

    fun isKnown(reason: Int): Boolean = reason in HANGUP..FAILED
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
    private const val TYPE_SENDER_KEY_DISTRIBUTION = 8
    private const val TYPE_GROUP_CIPHERTEXT = 9
    // Reserved for the removed relay. Never reuse them, or an old peer's message would be read as
    // something else.
    private const val TYPE_RETIRED_RELAY_GRANT = 10
    private const val TYPE_RETIRED_RELAY_CAPABILITY = 11
    private const val TYPE_DUMMY = 12
    private const val TYPE_GROUP_LEAVE = 13
    private const val TYPE_REMOTE_WIPE = 14
    private const val TYPE_REMOTE_WIPE_NOMINATION = 15
    private const val TYPE_NOSTR_INBOX = 16
    private const val TYPE_DEVICES = 17
    private const val TYPE_SYNC_SENT = 18
    private const val TYPE_CONTACT_UPDATE = 19
    private const val TYPE_WIPE_KEY = 20
    private const val TYPE_CALL_PERMISSION = 21
    private const val RING_KEY_BYTES = 32
    private const val TYPE_CALL_OFFER = 22
    private const val TYPE_CALL_ANSWER = 23
    private const val TYPE_CALL_END = 24

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
                    writeOptionalString(s, message.replyToId)
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
                    writeOptionalString(s, message.prevStateHash)
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
                    writeOptionalString(s, message.displayName)
                }
                is WireMessage.RemoteWipeNomination -> {
                    s.writeByte(TYPE_REMOTE_WIPE_NOMINATION)
                    s.writeBoolean(message.nominated)
                }
                is WireMessage.RemoteWipe -> {
                    s.writeByte(TYPE_REMOTE_WIPE)
                    s.writeUTF(message.token)
                }
                is WireMessage.GroupLeave -> {
                    s.writeByte(TYPE_GROUP_LEAVE)
                    s.writeUTF(message.conversationId)
                    s.writeLong(message.epoch)
                }
                is WireMessage.SenderKeyDistribution -> {
                    s.writeByte(TYPE_SENDER_KEY_DISTRIBUTION)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.senderOnion)
                    writeBlock(s, message.distributionBytes)
                }
                is WireMessage.GroupCiphertext -> {
                    s.writeByte(TYPE_GROUP_CIPHERTEXT)
                    s.writeUTF(message.conversationId)
                    s.writeUTF(message.senderOnion)
                    writeBlock(s, message.ciphertext)
                }
                is WireMessage.Retired -> throw IllegalArgumentException("Retired wire type ${message.type} is never sent")
                is WireMessage.Dummy -> {
                    s.writeByte(TYPE_DUMMY)
                    writeBlock(s, message.filler)
                }
                is WireMessage.NostrInbox -> {
                    s.writeByte(TYPE_NOSTR_INBOX)
                    writeBlock(s, message.secret)
                    writeStringList(s, message.relays)
                }
                is WireMessage.Devices -> {
                    s.writeByte(TYPE_DEVICES)
                    writeDeviceList(s, message.list)
                }
                is WireMessage.SyncSent -> {
                    require(message.inner !is WireMessage.SyncSent) { "A sync message can't carry another" }
                    s.writeByte(TYPE_SYNC_SENT)
                    s.writeUTF(message.conversationId)
                    writeBlock(s, encode(message.inner))
                }
                is WireMessage.ContactUpdate -> {
                    s.writeByte(TYPE_CONTACT_UPDATE)
                    s.writeUTF(message.onion)
                    s.writeUTF(message.displayName)
                    writeOptionalBlock(s, message.pinnedIdentityKey)
                    s.writeBoolean(message.verified)
                    s.writeBoolean(message.blocked)
                    s.writeBoolean(message.unblocked)
                    writeOptionalBoolean(s, message.callsAllowed)
                }
                is WireMessage.WipeKey -> {
                    s.writeByte(TYPE_WIPE_KEY)
                    writeBlock(s, message.key)
                    s.writeLong(message.epoch)
                }
                is WireMessage.CallPermission -> {
                    s.writeByte(TYPE_CALL_PERMISSION)
                    s.writeBoolean(message.allowed)
                    message.ringKey?.takeIf { message.allowed && it.size == RING_KEY_BYTES }?.let { s.write(it) }
                }
                is WireMessage.CallOffer -> {
                    require(isValidCallId(message.callId)) { "Invalid call id" }
                    require(message.secret.size == CALL_SECRET_BYTES) { "Invalid call secret" }
                    s.writeByte(TYPE_CALL_OFFER)
                    s.writeUTF(message.callId)
                    writeBlock(s, message.secret)
                    s.writeLong(message.sentAtMillis)
                }
                is WireMessage.CallAnswer -> {
                    require(isValidCallId(message.callId)) { "Invalid call id" }
                    require(message.secret.size == CALL_SECRET_BYTES) { "Invalid call secret" }
                    s.writeByte(TYPE_CALL_ANSWER)
                    s.writeUTF(message.callId)
                    writeBlock(s, message.secret)
                }
                is WireMessage.CallEnd -> {
                    require(isValidCallId(message.callId)) { "Invalid call id" }
                    s.writeByte(TYPE_CALL_END)
                    s.writeUTF(message.callId)
                    s.writeByte(message.reason)
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
                    // Missing from builds before replies existed; treated as no reply.
                    replyToId = readOptionalString(s),
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
                    // Missing from builds before state chaining; treated as not supplied.
                    prevStateHash = readOptionalString(s),
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
                    // Missing from older builds; treated as no name.
                    displayName = readOptionalString(s),
                )
                TYPE_REMOTE_WIPE_NOMINATION -> WireMessage.RemoteWipeNomination(nominated = s.readBoolean())
                TYPE_REMOTE_WIPE -> WireMessage.RemoteWipe(token = s.readUTF())
                TYPE_GROUP_LEAVE -> WireMessage.GroupLeave(
                    conversationId = s.readUTF(),
                    epoch = s.readLong(),
                )
                TYPE_SENDER_KEY_DISTRIBUTION -> WireMessage.SenderKeyDistribution(
                    conversationId = s.readUTF(),
                    senderOnion = s.readUTF(),
                    distributionBytes = readBlock(s),
                )
                TYPE_GROUP_CIPHERTEXT -> WireMessage.GroupCiphertext(
                    conversationId = s.readUTF(),
                    senderOnion = s.readUTF(),
                    ciphertext = readBlock(s),
                )
                // Not parsed, nothing in it is used.
                TYPE_RETIRED_RELAY_GRANT, TYPE_RETIRED_RELAY_CAPABILITY -> WireMessage.Retired(type)
                TYPE_DUMMY -> WireMessage.Dummy(filler = readBlock(s))
                TYPE_NOSTR_INBOX -> {
                    val secret = readBlock(s)
                    // 32 bytes, or empty to withdraw. Relays are validated again where they're
                    // used.
                    require(secret.isEmpty() || secret.size == 32) { "Invalid Nostr mailbox secret" }
                    val relays = readStringList(s)
                    require(relays.size <= MAX_NOSTR_RELAYS) { "Too many Nostr relays" }
                    WireMessage.NostrInbox(secret = secret, relays = relays)
                }
                TYPE_DEVICES -> WireMessage.Devices(readDeviceList(s))
                TYPE_SYNC_SENT -> {
                    val conversationId = s.readUTF()
                    val inner = decode(readBlock(s))
                    require(inner !is WireMessage.SyncSent) { "A sync message can't carry another" }
                    WireMessage.SyncSent(conversationId, inner)
                }
                TYPE_CONTACT_UPDATE -> WireMessage.ContactUpdate(
                    onion = OnionAddress.parse(s.readUTF()).value,
                    displayName = s.readUTF(),
                    pinnedIdentityKey = readOptionalBlock(s),
                    verified = s.readBoolean(),
                    blocked = s.readBoolean(),
                    unblocked = s.readBoolean(),
                    // Missing from builds before calls; treated as not stated.
                    callsAllowed = readOptionalBoolean(s),
                )
                TYPE_WIPE_KEY -> WireMessage.WipeKey(
                    key = readBlock(s).also { require(it.size == WIPE_KEY_BYTES) { "Invalid wipe key" } },
                    epoch = s.readLong().also { require(it >= 0) { "Invalid wipe key epoch" } },
                )
                TYPE_CALL_PERMISSION -> {
                    val allowed = s.readBoolean()
                    // Absent from older versions; anything but exactly a key counts as absent.
                    val rest = s.readBytes()
                    WireMessage.CallPermission(allowed, rest.takeIf { allowed && it.size == RING_KEY_BYTES })
                }
                TYPE_CALL_OFFER -> WireMessage.CallOffer(
                    callId = readCallId(s),
                    secret = readCallSecret(s),
                    sentAtMillis = s.readLong(),
                )
                TYPE_CALL_ANSWER -> WireMessage.CallAnswer(
                    callId = readCallId(s),
                    secret = readCallSecret(s),
                )
                TYPE_CALL_END -> WireMessage.CallEnd(
                    callId = readCallId(s),
                    // Unknown reasons from a newer build are kept: the call still ends.
                    reason = s.readUnsignedByte(),
                )
                else -> throw IllegalArgumentException("Unknown wire type $type")
            }
        }
    }

    internal fun writeDeviceList(s: DataOutputStream, list: DeviceList) {
        s.writeLong(list.epoch)
        s.writeByte(list.devices.size)
        list.devices.forEach { device ->
            s.writeByte(device.id)
            s.writeUTF(device.onion.value)
        }
    }

    internal fun readDeviceList(s: DataInputStream): DeviceList {
        val epoch = s.readLong()
        val count = s.readUnsignedByte()
        require(count in 1..DeviceList.MAX_DEVICES) { "Invalid device count $count" }
        // DeviceList checks ids, duplicates and the epoch; OnionAddress checks each address.
        return DeviceList(epoch, List(count) { Device(s.readUnsignedByte(), OnionAddress.parse(s.readUTF())) })
    }

    private fun writeStringList(s: DataOutputStream, items: List<String>) {
        s.writeInt(items.size)
        items.forEach(s::writeUTF)
    }

    private fun readStringList(s: DataInputStream): List<String> {
        val count = s.readInt()
        require(count in 0..MAX_LIST) { "Invalid list size $count" }
        // Per-item limits aren't enough on their own: 4096 entries at readUTF's 64 KB maximum is a
        // quarter of a gigabyte from one frame. Entries are onion addresses or message ids, so cap
        // the total by what they actually hold.
        var total = 0
        return List(count) {
            val value = s.readUTF()
            require(value.length <= MAX_LIST_ITEM_CHARS) { "List entry too long: ${value.length}" }
            total += value.length
            require(total <= MAX_LIST_TOTAL_CHARS) { "List exceeds aggregate limit" }
            value
        }
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

    /**
     * Reads a trailing optional field, telling "not sent" apart from "broken".
     *
     * These fields were added later, so an older peer's frame just ends before them and that's
     * fine. A field that starts and then runs out, or has a nonsense length, means truncation or
     * tampering, so the exception is allowed through and the message is rejected.
     */
    private fun readOptionalString(s: DataInputStream): String? {
        val present = try {
            s.readBoolean()
        } catch (_: EOFException) {
            // Clean end of frame: an older peer that doesn't send this field.
            return null
        }
        if (!present) return null
        // The sender said the field is there, so a failure from here on is corruption.
        return s.readUTF()
    }

    /** Like [readOptionalString], for a trailing flag: null when an older peer didn't send it. */
    private fun readOptionalBoolean(s: DataInputStream): Boolean? {
        val present = try {
            s.readBoolean()
        } catch (_: EOFException) {
            return null
        }
        if (!present) return null
        return s.readBoolean()
    }

    private fun writeOptionalBoolean(s: DataOutputStream, value: Boolean?) {
        if (value == null) {
            s.writeBoolean(false)
        } else {
            s.writeBoolean(true)
            s.writeBoolean(value)
        }
    }

    private fun readCallId(s: DataInputStream): String {
        val id = s.readUTF()
        require(isValidCallId(id)) { "Invalid call id" }
        return id
    }

    private fun readCallSecret(s: DataInputStream): ByteArray {
        val length = s.readInt()
        require(length == CALL_SECRET_BYTES) { "Invalid call secret length $length" }
        return ByteArray(length).also { s.readFully(it) }
    }

    /**
     * Call ids are random UUIDs, written in lower case. Anything else is refused, since the id ends
     * up in frames and log-free state where an unbounded string from a peer has no business.
     */
    fun isValidCallId(id: String): Boolean = CALL_ID.matches(id)

    private val CALL_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Each side's half of a call key. */
    const val CALL_SECRET_BYTES = 32

    /** Max members or message ids in one frame. */
    private const val MAX_LIST = 512

    /**
     * Longest single entry. Onion addresses are 62 characters and message ids are UUIDs, so this is
     * plenty.
     */
    private const val MAX_LIST_ITEM_CHARS = 128

    /**
     * Total across a whole list, so lots of short entries or a few long ones can't add up to a big
     * allocation.
     */
    private const val MAX_LIST_TOTAL_CHARS = 32_768
    private const val MAX_BLOCK = 1 shl 20

    private const val WIPE_KEY_BYTES = 32

    /** Most relays a contact may ask us to publish to. */
    private const val MAX_NOSTR_RELAYS = 8
}
