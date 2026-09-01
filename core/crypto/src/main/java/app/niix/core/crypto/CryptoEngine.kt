package app.niix.core.crypto

import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.LocalIdentity
import app.niix.core.model.OnionAddress
import app.niix.core.storage.SecureStorage
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
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

    /** Is [identityKeyBytes] a well-formed identity public key -- i.e. would libsignal itself
     * accept it? Used to validate a scanned or pasted contact code before it's ever written to
     * storage: a QR code (or clipboard paste) is untrusted input, and Base64 decoding
     * successfully only proves the string was valid Base64, not that the resulting bytes are a
     * usable key. Deliberately delegates to libsignal's own [IdentityKey] constructor rather
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

    fun safetyNumber(peerOnion: String, localOnion: String): String? {
        val localKey = identityManager.getOrCreate().identityKeyPair.publicKey.serialize()
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
        val remoteKey = store.getIdentity(address)?.serialize() ?: return null
        return SafetyNumber.compute(localKey, localOnion, remoteKey, peerOnion)
    }

    fun remoteFingerprint(peerOnion: String): IdentityFingerprint? {
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
        val key = store.getIdentity(address)?.serialize() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return IdentityFingerprint.fromPublicKeyBytes(digest)
    }

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

    fun processGroupDistribution(fromOnion: String, distributionBytes: ByteArray): String =
        groupCrypto.processDistribution(fromOnion, distributionBytes).toString()

    fun groupEncrypt(distributionId: UUID, plaintext: ByteArray): ByteArray =
        groupCrypto.encrypt(distributionId, plaintext)

    fun groupDecrypt(fromOnion: String, ciphertext: ByteArray): ByteArray =
        groupCrypto.decrypt(fromOnion, ciphertext)

    fun revokeGroupSenderKey(fromOnion: String, distributionId: String) {
        val id = runCatching { UUID.fromString(distributionId) }.getOrNull() ?: return
        groupCrypto.revoke(fromOnion, id)
    }

    companion object {
        fun create(secureStorage: SecureStorage, localName: String): CryptoEngine {
            val secureDatabase = secureStorage.database
            val identityManager = IdentityManager(secureDatabase)
            val store = DatabaseSignalProtocolStore(secureDatabase, identityManager)
            val preKeyManager = PreKeyManager(store, identityManager, secureDatabase)
            val sessionManager = SessionManager(store, localName)
            val groupCrypto = GroupCryptoEngine(store, localName)
            return CryptoEngine(store, identityManager, preKeyManager, sessionManager, groupCrypto)
        }
    }
}
