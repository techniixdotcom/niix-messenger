package app.niix.core.messaging

import app.niix.core.model.DeviceList
import app.niix.core.model.OnionAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What the QR code on a device waiting to be linked says: where to reach it, and a one-time secret
 * that only someone looking at its screen can know.
 */
data class LinkInvite(val onion: OnionAddress, val secret: ByteArray) {

    fun encode(): String = "$PREFIX${onion.value}:${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"

    companion object {
        private const val PREFIX = "niix-link:1:"
        const val SECRET_BYTES = 32

        fun create(onion: OnionAddress) = LinkInvite(onion, ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

        /** Null for anything that isn't a link code, so a scanned contact code can be told apart. */
        fun parseOrNull(raw: String): LinkInvite? {
            if (!raw.startsWith(PREFIX)) return null
            val parts = raw.removePrefix(PREFIX).split(':')
            if (parts.size != 2) return null
            val onion = OnionAddress.parseOrNull(parts[0]) ?: return null
            val secret = runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrNull() ?: return null
            return if (secret.size == SECRET_BYTES) LinkInvite(onion, secret) else null
        }
    }
}

/**
 * Everything a newly linked device needs to act as the account, except message history: the
 * identity key, its number, the account's devices, contacts and groups.
 */
internal data class LinkBundle(
    val account: String,
    val identityKeyPair: ByteArray,
    val deviceId: Int,
    val devices: DeviceList,
    val wipeKey: ByteArray,
    val username: String,
    val contacts: List<Contact>,
    val groups: List<Group>,
    val blocked: List<String>,
) {
    data class Contact(
        val onion: String,
        val name: String,
        val verified: Boolean,
        val identityKey: ByteArray?,
        val pinned: Boolean,
        val devices: DeviceList?,
    )

    data class Group(
        val id: String,
        val title: String,
        val epoch: Long,
        val members: List<String>,
        val admins: List<String>,
        val disappearSeconds: Long,
        val chained: Boolean,
    )

    /**
     * Encrypted under a key from the QR code's secret. The new device's address is bound in as
     * associated data, so a bundle can't be replayed to a different device that scanned nothing.
     */
    fun seal(invite: LinkInvite): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(invite.secret), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(invite.onion.value.toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(encode())
    }

    private fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.writeByte(VERSION)
            s.writeUTF(account)
            writeBlock(s, identityKeyPair)
            s.writeByte(deviceId)
            WireCodec.writeDeviceList(s, devices)
            writeBlock(s, wipeKey)
            s.writeUTF(username)
            s.writeInt(contacts.size)
            contacts.forEach { c ->
                s.writeUTF(c.onion)
                s.writeUTF(c.name)
                s.writeBoolean(c.verified)
                writeOptionalBlock(s, c.identityKey)
                s.writeBoolean(c.pinned)
                s.writeBoolean(c.devices != null)
                c.devices?.let { WireCodec.writeDeviceList(s, it) }
            }
            s.writeInt(groups.size)
            groups.forEach { g ->
                s.writeUTF(g.id)
                s.writeUTF(g.title)
                s.writeLong(g.epoch)
                writeStrings(s, g.members)
                writeStrings(s, g.admins)
                s.writeLong(g.disappearSeconds)
                s.writeBoolean(g.chained)
            }
            writeStrings(s, blocked)
        }
        return out.toByteArray()
    }

    companion object {
        private const val VERSION = 1
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_ITEMS = 10_000
        private const val MAX_BLOCK = 4_096

        /** Throws if the bundle wasn't sealed with this invite's secret, or doesn't parse. */
        fun open(invite: LinkInvite, sealed: ByteArray): LinkBundle {
            require(sealed.size > NONCE_BYTES) { "Link bundle too short" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key(invite.secret), "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.updateAAD(invite.onion.value.toByteArray(Charsets.UTF_8))
            return decode(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES))
        }

        private fun decode(bytes: ByteArray): LinkBundle = DataInputStream(ByteArrayInputStream(bytes)).use { s ->
            require(s.readByte().toInt() == VERSION) { "Unsupported link bundle version" }
            LinkBundle(
                account = OnionAddress.parse(s.readUTF()).value,
                identityKeyPair = readBlock(s),
                deviceId = s.readUnsignedByte(),
                devices = WireCodec.readDeviceList(s),
                wipeKey = readBlock(s).also { require(it.size == 32) { "Invalid wipe key" } },
                username = s.readUTF(),
                contacts = List(count(s)) {
                    Contact(
                        onion = OnionAddress.parse(s.readUTF()).value,
                        name = s.readUTF(),
                        verified = s.readBoolean(),
                        identityKey = if (s.readBoolean()) readBlock(s) else null,
                        pinned = s.readBoolean(),
                        devices = if (s.readBoolean()) WireCodec.readDeviceList(s) else null,
                    )
                },
                groups = List(count(s)) {
                    Group(
                        id = s.readUTF(),
                        title = s.readUTF(),
                        epoch = s.readLong(),
                        members = readStrings(s),
                        admins = readStrings(s),
                        disappearSeconds = s.readLong(),
                        chained = s.readBoolean(),
                    )
                },
                blocked = readStrings(s),
            )
        }

        // HKDF-SHA256 (RFC 5869) with no salt and a single output block.
        private fun key(secret: ByteArray): ByteArray {
            val prk = hmac(ByteArray(32), secret)
            return hmac(prk, "niix device link v1".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private fun count(s: DataInputStream): Int =
            s.readInt().also { require(it in 0..MAX_ITEMS) { "Invalid count $it" } }

        private fun writeBlock(s: DataOutputStream, data: ByteArray) {
            s.writeShort(data.size)
            s.write(data)
        }

        private fun readBlock(s: DataInputStream): ByteArray {
            val size = s.readUnsignedShort()
            require(size <= MAX_BLOCK) { "Block too large" }
            return ByteArray(size).also(s::readFully)
        }

        private fun writeOptionalBlock(s: DataOutputStream, data: ByteArray?) {
            s.writeBoolean(data != null)
            data?.let { writeBlock(s, it) }
        }

        private fun writeStrings(s: DataOutputStream, items: List<String>) {
            s.writeInt(items.size)
            items.forEach(s::writeUTF)
        }

        private fun readStrings(s: DataInputStream): List<String> = List(count(s)) { s.readUTF() }
    }
}
