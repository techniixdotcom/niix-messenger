package app.niix.core.crypto

import java.util.UUID
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

internal class GroupCryptoEngine(
    private val store: DatabaseSignalProtocolStore,
    private val localName: String,
) {

    private val sessionBuilder = GroupSessionBuilder(store)

    fun createDistribution(): Distribution {
        val distributionId = UUID.randomUUID()
        val message = sessionBuilder.create(localAddress(), distributionId)
        return Distribution(distributionId, message.serialize())
    }

    fun processDistribution(fromOnion: String, distributionBytes: ByteArray): UUID {
        val message = SenderKeyDistributionMessage(distributionBytes)
        sessionBuilder.process(remoteAddress(fromOnion), message)
        return message.distributionId
    }

    /** Revokes every sender key held for [fromOnion] -- see
     * [DatabaseSignalProtocolStore.deleteAllSenderKeysFrom] for why revoking by sender rather
     * than by tracked distribution id is what makes removal actually complete. */
    fun revokeAll(fromOnion: String) {
        store.deleteAllSenderKeysFrom(remoteAddress(fromOnion))
    }

    fun revoke(fromOnion: String, distributionId: UUID) {
        store.deleteSenderKey(remoteAddress(fromOnion), distributionId)
    }

    fun encrypt(distributionId: UUID, plaintext: ByteArray): ByteArray {
        val cipher = GroupCipher(store, localAddress())
        return cipher.encrypt(distributionId, plaintext).serialize()
    }

    fun decrypt(fromOnion: String, ciphertext: ByteArray): ByteArray {
        val cipher = GroupCipher(store, remoteAddress(fromOnion))
        return cipher.decrypt(ciphertext)
    }

    private fun localAddress(): SignalProtocolAddress =
        SignalProtocolAddress(localName, CryptoConstants.DEVICE_ID)

    private fun remoteAddress(remoteName: String): SignalProtocolAddress =
        SignalProtocolAddress(remoteName, CryptoConstants.DEVICE_ID)

    data class Distribution(val distributionId: UUID, val messageBytes: ByteArray)
}
