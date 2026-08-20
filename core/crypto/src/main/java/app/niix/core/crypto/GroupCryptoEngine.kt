package app.niix.core.crypto

import java.util.UUID
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage

/**
 * Sender-key group messaging: every member encrypts with libsignal's `GroupCipher` against a
 * single shared symmetric chain instead of re-running a separate pairwise Double Ratchet step
 * per recipient. The chain itself is still only ever handed to a recipient over that recipient's
 * own already-authenticated pairwise session -- see [ConversationManager]'s use of this class --
 * so this doesn't replace the pairwise sessions, it just takes the per-*message* ratchet cost
 * from O(members) down to O(1) and ties "who can currently read this group" to one revocable
 * sender-key epoch instead of N separate long-lived ratchet states.
 *
 * A member removal (or any membership change) requires generating a fresh distribution and
 * redistributing it to the remaining members -- a sender-key chain only ratchets forward, so
 * anyone who already holds it could otherwise keep decrypting messages sent after they left. See
 * [ConversationManager.syncGroup].
 */
internal class GroupCryptoEngine(
    private val store: DatabaseSignalProtocolStore,
    private val localName: String,
) {

    private val sessionBuilder = GroupSessionBuilder(store)

    /**
     * Starts a brand-new sender-key session for this device and returns the distribution
     * message to send (over each member's existing pairwise session) to everyone who should be
     * able to read what's encrypted under it. Returns the message's serialized bytes plus the
     * distribution id the caller needs for [encrypt].
     */
    fun createDistribution(): Distribution {
        val distributionId = UUID.randomUUID()
        val message = sessionBuilder.create(localAddress(), distributionId)
        return Distribution(distributionId, message.serialize())
    }

    /** Processes a distribution message received from [fromOnion], so that member's future
     * sender-key-encrypted group messages can be decrypted going forward. */
    fun processDistribution(fromOnion: String, distributionBytes: ByteArray) {
        val message = SenderKeyDistributionMessage(distributionBytes)
        sessionBuilder.process(remoteAddress(fromOnion), message)
    }

    /** Encrypts [plaintext] under our own current sender-key chain for [distributionId]. */
    fun encrypt(distributionId: UUID, plaintext: ByteArray): ByteArray {
        val cipher = GroupCipher(store, localAddress())
        return cipher.encrypt(distributionId, plaintext).serialize()
    }

    /** Decrypts a sender-key ciphertext previously sent by [fromOnion]. Requires that a
     * distribution message from that sender was already processed via [processDistribution]. */
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
