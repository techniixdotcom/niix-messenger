package app.niix.core.crypto

import java.security.SecureRandom
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Another phone that exists only in memory, for the duress decoy. Exchanging messages with one of
 * these gives a decoy contact a real identity key and a session in the state real use leaves it
 * in, so the decoy's database doesn't show contacts that never had any keys. Forgotten as soon as
 * the decoy is set up; nothing is sent anywhere.
 */
internal class DecoyPeer {

    private val random = SecureRandom()
    private val identity = IdentityKeyPair.generate()
    private val store = InMemorySignalProtocolStore(identity, 1 + random.nextInt(MAX_REGISTRATION_ID))

    // Names on this peer's side. Any will do: only our side's records are kept.
    private val self = SignalProtocolAddress("decoy-peer", CryptoConstants.FIRST_DEVICE_ID)
    private val us = SignalProtocolAddress("decoy-us", CryptoConstants.FIRST_DEVICE_ID)

    fun bundle(): PreKeyBundle {
        val now = System.currentTimeMillis()
        val preKey = ECKeyPair.generate()
        val signedPreKey = ECKeyPair.generate()
        val kyberPreKey = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signedSignature = identity.privateKey.calculateSignature(signedPreKey.publicKey.serialize())
        val kyberSignature = identity.privateKey.calculateSignature(kyberPreKey.publicKey.serialize())
        val (preKeyId, signedPreKeyId, kyberPreKeyId) = List(3) { 1 + random.nextInt(MAX_KEY_ID) }
        store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKey))
        store.storeSignedPreKey(signedPreKeyId, SignedPreKeyRecord(signedPreKeyId, now, signedPreKey, signedSignature))
        store.storeKyberPreKey(kyberPreKeyId, KyberPreKeyRecord(kyberPreKeyId, now, kyberPreKey, kyberSignature))
        return PreKeyBundle(
            store.localRegistrationId,
            CryptoConstants.FIRST_DEVICE_ID,
            preKeyId,
            preKey.publicKey,
            signedPreKeyId,
            signedPreKey.publicKey,
            signedSignature,
            identity.publicKey,
            kyberPreKeyId,
            kyberPreKey.publicKey,
            kyberSignature,
        )
    }

    /** For a conversation the other side started: this peer takes our bundle, as theirs would. */
    fun startSession(ourBundle: PreKeyBundle) {
        SessionBuilder(store, us, self).process(ourBundle)
    }

    fun encrypt(plaintext: ByteArray): EncryptedEnvelope {
        val message = SessionCipher(store, self, us).encrypt(plaintext)
        return EncryptedEnvelope(message.type, message.serialize())
    }

    fun decrypt(envelope: EncryptedEnvelope): ByteArray {
        val cipher = SessionCipher(store, self, us)
        return when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(envelope.ciphertext))
            else -> cipher.decrypt(SignalMessage(envelope.ciphertext))
        }
    }

    private companion object {
        const val MAX_REGISTRATION_ID = 16_380
        const val MAX_KEY_ID = 0xFFFFFE
    }
}
