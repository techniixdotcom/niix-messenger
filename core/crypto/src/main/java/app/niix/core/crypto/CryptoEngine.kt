package app.niix.core.crypto

import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.LocalIdentity
import app.niix.core.model.OnionAddress
import app.niix.core.storage.SecureStorage
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.security.MessageDigest
import java.util.UUID

/** The public-facing result of starting a new group sender-key session: the id callers pass
 * back into [CryptoEngine.groupEncrypt], and the distribution message bytes to send to every
 * member. Deliberately a plain public type rather than [GroupCryptoEngine]'s own internal one,
 * since [GroupCryptoEngine] itself is internal to this module and can't appear in a public
 * method's signature. */
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

    /** Rotates the signed prekey and last-resort Kyber prekey once either is due, and prunes
     * anything old enough that no still-in-flight handshake could need it. See
     * [PreKeyManager.rotateKeysIfDue] -- cheap to call often, since it's a no-op except when a
     * key has actually aged out. */
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

    /** The serialized Signal identity public key this device has on file for [peerOnion] --
     * i.e. the same key material [remoteFingerprint] hashes -- or null if there's no recorded
     * identity for them yet (no session/handshake has ever completed). Used by the relay
     * feature (see `core/relay`), which authorizes and looks up mailbox operations by identity
     * key rather than onion address. */
    fun remoteIdentityKeyBytes(peerOnion: String): ByteArray? {
        val address = SignalProtocolAddress(peerOnion, CryptoConstants.DEVICE_ID)
        return store.getIdentity(address)?.serialize()
    }

    /** The onion address this device has on file for the contact whose identity key is
     * [identityKeyBytes], or null if no recorded identity matches. This is the reverse of
     * [remoteIdentityKeyBytes] -- needed because a message that arrives via a relay (see
     * `core/relay`) is addressed by the sender's identity key only, not their onion, but
     * decrypting it still requires the pairwise Signal session, which this codebase keys by
     * onion address. */
    fun onionForIdentityKey(identityKeyBytes: ByteArray): String? =
        store.findNameByIdentityKey(identityKeyBytes)

    /**
     * Signs [message] with this device's own long-term identity private key (XEdDSA over
     * Curve25519 -- the same primitive already used for prekey signatures, see
     * [PreKeyManager]). Used by the relay feature to build RelayGrant certificates and to prove
     * a relay-store/-fetch/-delete request genuinely came from the identity key it claims to,
     * without ever handing the relay itself any shared secret (see `core/relay`'s
     * RelayGrant/RelayProtocol doc comments).
     */
    fun signWithIdentityKey(message: ByteArray): ByteArray =
        identityManager.getOrCreate().identityKeyPair.privateKey.calculateSignature(message)

    /**
     * Verifies a signature produced by [signWithIdentityKey] on the *other* end, i.e. checks
     * that [signature] over [message] was produced by the private key matching
     * [identityKeyBytes]. Purely functional -- unlike every other method here, this never
     * touches local session/identity state, so it works equally for an established contact or a
     * total stranger's claimed identity key (e.g. a relay verifying a stranger's fetch proof).
     * Returns false (never throws) for a malformed key or signature.
     */
    fun verifyIdentitySignature(identityKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching { IdentityKey(identityKeyBytes, 0).publicKey.verifySignature(message, signature) }
            .getOrDefault(false)

    // ---------------- Group (sender-key) messaging ----------------

    /** Starts a fresh sender-key session for this device -- call this whenever a group's
     * membership changes (including on creation), then send the returned bytes to every current
     * member over their existing pairwise session. See [GroupCryptoEngine]. */
    fun createGroupDistribution(): GroupDistribution {
        val distribution = groupCrypto.createDistribution()
        return GroupDistribution(distribution.distributionId.toString(), distribution.messageBytes)
    }

    /** Processes a distribution message received (over an already-authenticated pairwise
     * session) from [fromOnion], enabling decryption of that member's future group messages. */
    fun processGroupDistribution(fromOnion: String, distributionBytes: ByteArray) =
        groupCrypto.processDistribution(fromOnion, distributionBytes)

    fun groupEncrypt(distributionId: UUID, plaintext: ByteArray): ByteArray =
        groupCrypto.encrypt(distributionId, plaintext)

    fun groupDecrypt(fromOnion: String, ciphertext: ByteArray): ByteArray =
        groupCrypto.decrypt(fromOnion, ciphertext)

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
