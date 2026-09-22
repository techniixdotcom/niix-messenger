package app.niix.core.crypto

import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.LocalIdentity
import app.niix.core.model.OnionAddress
import app.niix.core.storage.SecureStorage
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
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

    /** Is [identityKeyBytes] a well-formed identity public key, i.e. would libsignal itself
     * accept it? Used to validate a scanned or pasted contact code before it's ever written to
     * storage: a QR code (or clipboard paste) is untrusted input, and Base64 decoding
     * successfully only proves the string was valid Base64, not that the resulting bytes are a
     * usable key. On purpose delegates to libsignal's own [IdentityKey] constructor rather
     * than hand-checking a byte length here, so this can't drift out of sync with whatever
     * format libsignal actually expects. */
    fun isValidIdentityKeyBytes(identityKeyBytes: ByteArray): Boolean =
        runCatching { IdentityKey(identityKeyBytes, 0) }.isSuccess

    fun localIdentityKey(): ByteArray =
        identityManager.getOrCreate().identityKeyPair.publicKey.serialize()

    fun bundleIdentityKey(peerBundleBytes: ByteArray): ByteArray =
        PreKeyBundleCodec.decode(peerBundleBytes).identityKey.serialize()

    fun establishOutboundSession(remoteName: String, peerBundleBytes: ByteArray) {
        val bundle = PreKeyBundleCodec.decode(peerBundleBytes)
        sessionManager.establishSession(remoteName, bundle)
    }

    fun hasSession(remoteName: String): Boolean = sessionManager.hasSession(remoteName)

    fun encrypt(remoteName: String, plaintext: ByteArray): ByteArray =
        sessionManager.encrypt(remoteName, plaintext).toBytes()

    fun decrypt(remoteName: String, wire: ByteArray): ByteArray {
        val plaintext = sessionManager.decrypt(remoteName, EncryptedEnvelope.fromBytes(wire))
        preKeyManager.replenishOneTimeKeysIfLow()
        return plaintext
    }

    fun hasRemoteIdentity(peerOnion: String): Boolean {
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
        return store.getIdentity(address) != null
    }

    /**
     * The safety number shown to the user, from libsignal's generator.
     *
     * Previously a separate SHA-512 construction, so the number the user compared was not the one
     * the rest of the app treated as the contact's fingerprint. One construction means there is
     * only one answer, and it is one that has been reviewed by people other than us.
     */
    fun safetyNumber(peerOnion: String, localOnion: String): String? {
        localOnionForFingerprint = localOnion
        return remoteFingerprint(peerOnion)?.displayable
    }

    fun remoteFingerprint(peerOnion: String): IdentityFingerprint? {
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
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
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
        return store.getIdentity(address)?.serialize()
    }

    fun onionForIdentityKey(identityKeyBytes: ByteArray): String? =
        store.findNameByIdentityKey(identityKeyBytes)

    fun signWithIdentityKey(message: ByteArray): ByteArray =
        identityManager.getOrCreate().identityKeyPair.privateKey.calculateSignature(message)

    fun verifyIdentitySignature(identityKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching { IdentityKey(identityKeyBytes, 0).publicKey.verifySignature(message, signature) }
            .getOrDefault(false)

    fun createGroupDistribution(): GroupDistribution {
        val distribution = groupCrypto.createDistribution()
        return GroupDistribution(distribution.distributionId.toString(), distribution.messageBytes)
    }

    /** The current key message for one of this device's existing group keys. */
    fun groupDistributionFor(distributionId: String): ByteArray =
        groupCrypto.currentDistribution(UUID.fromString(distributionId))

    fun processGroupDistribution(fromOnion: String, distributionBytes: ByteArray): String =
        groupCrypto.processDistribution(fromOnion, distributionBytes).toString()

    fun groupEncrypt(distributionId: UUID, plaintext: ByteArray): ByteArray =
        groupCrypto.encrypt(distributionId, plaintext)

    fun groupDecrypt(fromOnion: String, ciphertext: ByteArray): ByteArray =
        groupCrypto.decrypt(fromOnion, ciphertext)

    /** Revokes every sender key from [fromOnion], not just tracked ones. See
     * [GroupCryptoEngine.revokeAll]. */
    fun revokeAllGroupSenderKeys(fromOnion: String) {
        groupCrypto.revokeAll(fromOnion)
    }

    fun revokeGroupSenderKey(fromOnion: String, distributionId: String) {
        val id = runCatching { UUID.fromString(distributionId) }.getOrNull() ?: return
        groupCrypto.revoke(fromOnion, id)
    }

    companion object {
        /** Signal's own iteration count for numeric fingerprints, a deliberate work factor
         * that makes searching for a colliding safety number expensive. */
        private const val FINGERPRINT_ITERATIONS = 5200
        private const val FINGERPRINT_VERSION = 2

        fun create(secureStorage: SecureStorage, localName: String): CryptoEngine {
            val secureDatabase = secureStorage.database
            val identityManager = IdentityManager(secureDatabase)
 // The pin is written by the messaging layer when the user scans a contact's code.
 // Reading it here lets the identity store refuse to replace a pinned key at the
 // point of the change, rather than the application noticing afterwards that the
 // stored key disagrees with what the user verified.
            val store = DatabaseSignalProtocolStore(secureDatabase, identityManager) { addressName ->
                runCatching {
                    secureStorage.settings.getString("identity:$addressName")
                        ?.let { java.util.Base64.getDecoder().decode(it) }
                }.getOrNull()
            }
            val preKeyManager = PreKeyManager(store, identityManager, secureDatabase)
            val sessionManager = SessionManager(store, localName)
            val groupCrypto = GroupCryptoEngine(store, localName)
            return CryptoEngine(store, identityManager, preKeyManager, sessionManager, groupCrypto)
        }
    }
}
