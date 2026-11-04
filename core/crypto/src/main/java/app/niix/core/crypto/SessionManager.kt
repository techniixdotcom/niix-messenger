package app.niix.core.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.concurrent.ConcurrentHashMap

class EncryptedEnvelope(val type: Int, val ciphertext: ByteArray) {

    fun toBytes(): ByteArray {
        val out = ByteArray(ciphertext.size + 1)
        out[0] = type.toByte()
        System.arraycopy(ciphertext, 0, out, 1, ciphertext.size)
        return out
    }

    companion object {
        fun fromBytes(bytes: ByteArray): EncryptedEnvelope {
            require(bytes.isNotEmpty()) { "Empty envelope" }
            val type = bytes[0].toInt()
            val ciphertext = bytes.copyOfRange(1, bytes.size)
            return EncryptedEnvelope(type, ciphertext)
        }
    }
}

/**
 * One-to-one sessions. A session is with one device of an account: [remoteName] is the account,
 * [deviceId] the device within it.
 *
 * Every operation on a session holds that session's lock. libsignal loads the session, advances it
 * and stores it again, and two of those running at once on the same session (a direct copy and a
 * relayed copy arriving together, or a send while a message is being decrypted) would each store
 * their own version, losing the other's ratchet step. What follows is messages that can't be
 * decrypted. Different sessions don't share a lock.
 */
internal class SessionManager(
    private val store: DatabaseSignalProtocolStore,
    private val localName: String,
    private val localDeviceId: () -> Int,
) {

    private val locks = ConcurrentHashMap<String, Any>()

    private inline fun <T> withSession(remoteName: String, deviceId: Int, block: (SignalProtocolAddress) -> T): T =
        synchronized(locks.computeIfAbsent("$remoteName.$deviceId") { Any() }) {
            block(remoteAddress(remoteName, deviceId))
        }

    fun establishSession(remoteName: String, deviceId: Int, bundle: PreKeyBundle) =
        withSession(remoteName, deviceId) { remote ->
            SessionBuilder(store, remote, localAddress()).process(bundle)
        }

    fun deleteSession(remoteName: String, deviceId: Int) =
        withSession(remoteName, deviceId) { remote -> store.deleteSession(remote) }

    fun hasSession(remoteName: String, deviceId: Int): Boolean =
        store.containsSession(remoteAddress(remoteName, deviceId))

    fun encrypt(remoteName: String, deviceId: Int, plaintext: ByteArray): EncryptedEnvelope =
        withSession(remoteName, deviceId) { remote ->
            val message = SessionCipher(store, localAddress(), remote).encrypt(plaintext)
            EncryptedEnvelope(message.type, message.serialize())
        }

    fun decrypt(remoteName: String, deviceId: Int, envelope: EncryptedEnvelope): ByteArray =
        withSession(remoteName, deviceId) { remote ->
            val cipher = SessionCipher(store, localAddress(), remote)
            when (envelope.type) {
                CiphertextMessage.PREKEY_TYPE ->
                    cipher.decrypt(PreKeySignalMessage(envelope.ciphertext))
                CiphertextMessage.WHISPER_TYPE ->
                    cipher.decrypt(SignalMessage(envelope.ciphertext))
                else -> throw IllegalArgumentException("Unknown ciphertext type ${envelope.type}")
            }
        }

    private fun remoteAddress(remoteName: String, deviceId: Int): SignalProtocolAddress =
        SignalProtocolAddress(remoteName, deviceId)

    private fun localAddress(): SignalProtocolAddress =
        SignalProtocolAddress(localName, localDeviceId())
}
