package app.niix.core.crypto

import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.LocalIdentity
import app.niix.core.model.OnionAddress
import app.niix.core.storage.SecureStorage
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidSessionException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import java.security.MessageDigest
import java.util.UUID

data class GroupDistribution(val distributionId: String, val messageBytes: ByteArray)

class CryptoEngine internal constructor(
    private val store: DatabaseSignalProtocolStore,
    private val identityManager: IdentityManager,
    private val preKeyManager: PreKeyManager,
    private val sessionManager: SessionManager,
    private val groupCrypto: GroupCryptoEngine,
) {

    fun ensureKeysInitialized() {
        preKeyManager.generateInitialKeysIfNeeded()
    }

    fun forgetCachedIdentity() {
        identityManager.reset()
    }

    fun rotateKeysIfDue() {
        preKeyManager.rotateKeysIfDue()
    }

    fun registrationId(): Int = identityManager.getOrCreate().registrationId

    /** This device's number within its account. */
    fun localDeviceId(): Int = identityManager.getOrCreate().deviceId

    /** The account's identity key pair, for handing to a device being linked. Never sent anywhere else. */
    fun exportIdentityKeyPair(): ByteArray = identityManager.getOrCreate().identityKeyPair.serialize()

    /** Joins this device to an existing account. See [IdentityManager.adopt]. */
    fun adoptIdentity(identityKeyPair: ByteArray, deviceId: Int) {
        identityManager.adopt(IdentityKeyPair(identityKeyPair), deviceId)
    }

    fun identityFingerprint(): IdentityFingerprint {
        val publicKey = identityManager.getOrCreate().identityKeyPair.publicKey.serialize()
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return IdentityFingerprint.fromPublicKeyBytes(digest)
    }

    fun localIdentity(onionAddress: OnionAddress?): LocalIdentity =
        LocalIdentity(
            registrationId = registrationId(),
            fingerprint = identityFingerprint(),
            onionAddress = onionAddress,
        )

    fun exportLocalBundle(): ByteArray {
        val bundle = preKeyManager.createLocalBundle()
        return PreKeyBundleCodec.encode(bundle)
    }

    /**
     * Whether [identityKeyBytes] is a key libsignal would accept. Used on scanned or pasted codes
     * before they're stored, since valid Base64 doesn't mean a valid key. Uses libsignal's own
     * [IdentityKey] constructor so it can't drift from what libsignal expects.
     */
    fun isValidIdentityKeyBytes(identityKeyBytes: ByteArray): Boolean =
        runCatching { IdentityKey(identityKeyBytes, 0) }.isSuccess

    fun localIdentityKey(): ByteArray =
        identityManager.getOrCreate().identityKeyPair.publicKey.serialize()

    fun bundleIdentityKey(peerBundleBytes: ByteArray): ByteArray =
        PreKeyBundleCodec.decode(peerBundleBytes).identityKey.serialize()

    /** The device number a bundle says it's for. Checked against the device list before use. */
    fun bundleDeviceId(peerBundleBytes: ByteArray): Int =
        PreKeyBundleCodec.decode(peerBundleBytes).deviceId

    fun establishOutboundSession(remoteName: String, deviceId: Int, peerBundleBytes: ByteArray) {
        val bundle = PreKeyBundleCodec.decode(peerBundleBytes)
        sessionManager.establishSession(remoteName, deviceId, bundle)
    }

    fun hasSession(remoteName: String, deviceId: Int = CryptoConstants.FIRST_DEVICE_ID): Boolean =
        sessionManager.hasSession(remoteName, deviceId)

    /** Throws away the session with this device, so the next one starts from its key bundle. */
    fun resetSession(remoteName: String, deviceId: Int) = sessionManager.deleteSession(remoteName, deviceId)

    /**
     * For the duress decoy: gives [remoteName] a real identity key and session by running a real
     * exchange with a stand-in phone held in memory. [sent] has one entry per message, in order:
     * true for one we sent, false for one we received. Nothing leaves the phone.
     */
    fun simulateConversation(remoteName: String, sent: List<Boolean>) {
        val peer = DecoyPeer()
        if (sent.firstOrNull() == false) {
            peer.startSession(preKeyManager.createLocalBundle())
        } else {
            sessionManager.establishSession(remoteName, CryptoConstants.FIRST_DEVICE_ID, peer.bundle())
        }
        val random = java.security.SecureRandom()
        for (outgoing in sent) {
            // About the size of a short padded message. The content is never stored.
            val filler = ByteArray(MessageSize.MIN + random.nextInt(MessageSize.SPREAD)).also(random::nextBytes)
            if (outgoing) {
                peer.decrypt(sessionManager.encrypt(remoteName, CryptoConstants.FIRST_DEVICE_ID, filler))
            } else {
                decrypt(remoteName, CryptoConstants.FIRST_DEVICE_ID, peer.encrypt(filler).toBytes())
            }
        }
    }

    private object MessageSize {
        const val MIN = 160
        const val SPREAD = 320
    }

    fun encrypt(remoteName: String, deviceId: Int, plaintext: ByteArray): ByteArray =
        sessionManager.encrypt(remoteName, deviceId, plaintext).toBytes()

    /**
     * Throws [AlreadyReceivedException] for a message decrypted before, and
     * [SessionMismatchException] when the session with that device can't open it. A changed
     * identity key is neither: libsignal's UntrustedIdentityException comes through as it is.
     */
    fun decrypt(remoteName: String, deviceId: Int, wire: ByteArray): ByteArray {
        val plaintext = try {
            sessionManager.decrypt(remoteName, deviceId, EncryptedEnvelope.fromBytes(wire))
        } catch (e: DuplicateMessageException) {
            throw AlreadyReceivedException(e)
        } catch (e: NoSessionException) {
            throw SessionMismatchException(e)
        } catch (e: InvalidSessionException) {
            throw SessionMismatchException(e)
        } catch (e: InvalidKeyIdException) {
            throw SessionMismatchException(e)
        } catch (e: InvalidKeyException) {
            throw SessionMismatchException(e)
        } catch (e: InvalidMessageException) {
            throw SessionMismatchException(e)
        }
        preKeyManager.replenishOneTimeKeysIfLow()
        return plaintext
    }

    /**
     * The identity key a session-starting message claims, or null if it isn't one. Read without
     * decrypting or storing anything, so it can be checked before libsignal trusts it.
     */
    fun claimedIdentity(wire: ByteArray): ByteArray? = runCatching {
        val envelope = EncryptedEnvelope.fromBytes(wire)
        if (envelope.type != CiphertextMessage.PREKEY_TYPE) return null
        PreKeySignalMessage(envelope.ciphertext).identityKey.serialize()
    }.getOrNull()

    /** Records [account]'s identity key, as known on another device of ours, before any session. */
    fun trustIdentity(account: String, identityKey: ByteArray) {
        store.saveIdentity(identityAddress(account), IdentityKey(identityKey, 0))
    }

    fun hasRemoteIdentity(peerOnion: String): Boolean {
        val address = identityAddress(peerOnion)
        return store.getIdentity(address) != null
    }

    /**
     * The safety number shown to the user, from libsignal's generator, so it's the same fingerprint
     * the rest of the app uses.
     */
    fun safetyNumber(peerOnion: String, localOnion: String): String? {
        localOnionForFingerprint = localOnion
        return remoteFingerprint(peerOnion)?.displayable
    }

    fun remoteFingerprint(peerOnion: String): IdentityFingerprint? {
        val address = identityAddress(peerOnion)
        val remoteKey = store.getIdentity(address) ?: return null
        val localKey = identityManager.getOrCreate().identityKeyPair.publicKey
        val selfOnion = localOnionForFingerprint ?: return null
        return runCatching {
            val fingerprint = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
                FINGERPRINT_VERSION,
                selfOnion.toByteArray(Charsets.UTF_8),
                localKey,
                peerOnion.toByteArray(Charsets.UTF_8),
                remoteKey,
            )
            IdentityFingerprint(fingerprint.displayableFingerprint.displayText)
        }.getOrNull()
    }

    @Volatile
    var localOnionForFingerprint: String? = null

    fun remoteIdentityKeyBytes(peerOnion: String): ByteArray? {
        val address = identityAddress(peerOnion)
        return store.getIdentity(address)?.serialize()
    }

    fun createGroupDistribution(): GroupDistribution {
        val distribution = groupCrypto.createDistribution()
        return GroupDistribution(distribution.distributionId.toString(), distribution.messageBytes)
    }

    /** The current key message for one of our existing group keys. */
    fun groupDistributionFor(distributionId: String): ByteArray =
        groupCrypto.currentDistribution(UUID.fromString(distributionId))

    fun processGroupDistribution(fromAccount: String, deviceId: Int, distributionBytes: ByteArray): String =
        groupCrypto.processDistribution(fromAccount, deviceId, distributionBytes).toString()

    fun groupEncrypt(distributionId: UUID, plaintext: ByteArray): ByteArray =
        groupCrypto.encrypt(distributionId, plaintext)

    fun groupDecrypt(fromAccount: String, deviceId: Int, ciphertext: ByteArray): ByteArray =
        groupCrypto.decrypt(fromAccount, deviceId, ciphertext)

    /**
     * Revokes every sender key from [fromOnion], not just the tracked ones. See
     * [GroupCryptoEngine.revokeAll].
     */
    fun revokeAllGroupSenderKeys(fromOnion: String) {
        groupCrypto.revokeAll(fromOnion)
    }

    fun revokeGroupSenderKey(fromOnion: String, distributionId: String) {
        val id = runCatching { UUID.fromString(distributionId) }.getOrNull() ?: return
        groupCrypto.revoke(fromOnion, id)
    }

    // Identity keys belong to the account, so the store ignores the device number for them.
    private fun identityAddress(account: String) = SignalProtocolAddress(account, CryptoConstants.FIRST_DEVICE_ID)

    companion object {
        /**
         * Signal's iteration count for numeric fingerprints; it makes searching for a colliding
         * safety number expensive.
         */
        private const val FINGERPRINT_ITERATIONS = 5200
        private const val FINGERPRINT_VERSION = 2

        fun create(secureStorage: SecureStorage, localName: String): CryptoEngine {
            val secureDatabase = secureStorage.database
            val identityManager = IdentityManager(secureDatabase)
            // The messaging layer writes the pin when a contact's code is scanned. Reading it here
            // lets the identity store refuse to replace a pinned key at the moment of the change.
            val store = DatabaseSignalProtocolStore(secureDatabase, identityManager) { addressName ->
                runCatching {
                    secureStorage.settings.getString("identity:$addressName")
                        ?.let { java.util.Base64.getDecoder().decode(it) }
                }.getOrNull()
            }
            val preKeyManager = PreKeyManager(store, identityManager, secureDatabase)
            val localDeviceId = { identityManager.getOrCreate().deviceId }
            val sessionManager = SessionManager(store, localName, localDeviceId)
            val groupCrypto = GroupCryptoEngine(store, localName, localDeviceId)
            return CryptoEngine(store, identityManager, preKeyManager, sessionManager, groupCrypto)
        }
    }
}
