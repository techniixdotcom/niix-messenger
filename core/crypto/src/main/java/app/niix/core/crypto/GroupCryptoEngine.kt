package app.niix.core.crypto

import java.util.UUID
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

/**
 * Sender keys. Each device of an account has its own, so senders are named by account and device.
 *
 * Like one-to-one sessions (see SessionManager), a sender key is loaded, advanced and stored again,
 * so operations take a lock. They're quick, so one lock for all of them is enough.
 */
internal class GroupCryptoEngine(
    private val store: DatabaseSignalProtocolStore,
    private val localName: String,
    private val localDeviceId: () -> Int,
) {

    private val sessionBuilder = GroupSessionBuilder(store)
    private val lock = Any()

    fun createDistribution(): Distribution {
        val distributionId = UUID.randomUUID()
        val message = synchronized(lock) { sessionBuilder.create(localAddress(), distributionId) }
        return Distribution(distributionId, message.serialize())
    }

    /**
     * The key message for a group key we already created. libsignal's create() reuses the existing
     * sender key for the distribution id and describes its current position, so it can be re-sent
     * to anyone who missed it without changing the key.
     */
    fun currentDistribution(distributionId: UUID): ByteArray =
        synchronized(lock) { sessionBuilder.create(localAddress(), distributionId) }.serialize()

    fun processDistribution(fromAccount: String, deviceId: Int, distributionBytes: ByteArray): UUID {
        val message = SenderKeyDistributionMessage(distributionBytes)
        synchronized(lock) { sessionBuilder.process(SignalProtocolAddress(fromAccount, deviceId), message) }
        return message.distributionId
    }

    /** Every sender key from every device of [fromAccount]. */
    fun revokeAll(fromAccount: String) {
        synchronized(lock) { store.deleteAllSenderKeysFrom(fromAccount) }
    }

    fun revoke(fromAccount: String, distributionId: UUID) {
        synchronized(lock) { store.deleteSenderKey(fromAccount, distributionId) }
    }

    fun encrypt(distributionId: UUID, plaintext: ByteArray): ByteArray {
        val message = synchronized(lock) { GroupCipher(store, localAddress()).encrypt(distributionId, plaintext) }
        return message.serialize()
    }

    fun decrypt(fromAccount: String, deviceId: Int, ciphertext: ByteArray): ByteArray {
        val sender = SignalProtocolAddress(fromAccount, deviceId)
        return synchronized(lock) { GroupCipher(store, sender).decrypt(ciphertext) }
    }

    private fun localAddress(): SignalProtocolAddress =
        SignalProtocolAddress(localName, localDeviceId())

    data class Distribution(val distributionId: UUID, val messageBytes: ByteArray)
}
