package app.niix.core.crypto

import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.LocalIdentity
import app.niix.core.model.OnionAddress
import app.niix.core.storage.SecureStorage
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.security.MessageDigest

class CryptoEngine internal constructor(
    private val store: DatabaseSignalProtocolStore,
    private val identityManager: IdentityManager,
    private val preKeyManager: PreKeyManager,
    private val sessionManager: SessionManager,
) {

    fun ensureKeysInitialized() {
        preKeyManager.generateInitialKeysIfNeeded()
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

    /** The local identity public key — the only key material needed in the share code. */
    fun localIdentityKey(): ByteArray =
        identityManager.getOrCreate().identityKeyPair.publicKey.serialize()

    /** Extracts the identity public key from a serialized peer bundle (for verification). */
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

    companion object {
        fun create(secureStorage: SecureStorage, localName: String): CryptoEngine {
            val secureDatabase = secureStorage.database
            val identityManager = IdentityManager(secureDatabase)
            val store = DatabaseSignalProtocolStore(secureDatabase, identityManager)
            val preKeyManager = PreKeyManager(store, identityManager, secureDatabase)
            val sessionManager = SessionManager(store, localName)
            return CryptoEngine(store, identityManager, preKeyManager, sessionManager)
        }
    }
}
