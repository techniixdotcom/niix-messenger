package app.niix.core.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

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

internal class SessionManager(
    private val store: DatabaseSignalProtocolStore,
    private val localName: String,
) {

    fun establishSession(remoteName: String, bundle: PreKeyBundle) {
        val builder = SessionBuilder(store, remoteAddress(remoteName), localAddress())
        builder.process(bundle)
    }

    fun hasSession(remoteName: String): Boolean =
        store.containsSession(remoteAddress(remoteName))

    fun encrypt(remoteName: String, plaintext: ByteArray): EncryptedEnvelope {
        val cipher = SessionCipher(store, localAddress(), remoteAddress(remoteName))
        val message = cipher.encrypt(plaintext)
        return EncryptedEnvelope(message.type, message.serialize())
    }

    fun decrypt(remoteName: String, envelope: EncryptedEnvelope): ByteArray {
        val cipher = SessionCipher(store, localAddress(), remoteAddress(remoteName))
        return when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE ->
                cipher.decrypt(PreKeySignalMessage(envelope.ciphertext))
            CiphertextMessage.WHISPER_TYPE ->
                cipher.decrypt(SignalMessage(envelope.ciphertext))
            else -> throw IllegalArgumentException("Unknown ciphertext type ${envelope.type}")
        }
    }

    private fun remoteAddress(remoteName: String): SignalProtocolAddress =
        SignalProtocolAddress(remoteName, CryptoConstants.DEVICE_ID)

    private fun localAddress(): SignalProtocolAddress =
        SignalProtocolAddress(localName, CryptoConstants.DEVICE_ID)
}
