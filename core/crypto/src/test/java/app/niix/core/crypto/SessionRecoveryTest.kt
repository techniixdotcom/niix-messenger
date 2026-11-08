package app.niix.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
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
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * What recovering a broken session rests on, against the real libsignal: when the receiver can't
 * decrypt, it throws its session away, starts a new one from the sender's bundle and sends a message
 * on it. The sender switches to that session on receiving it, and its next retry decrypts.
 * ConversationManager.startNewSession does exactly this.
 */
class SessionRecoveryTest {

    private class Phone(identity: IdentityKeyPair, val account: String, val deviceId: Int) {
        val store = InMemorySignalProtocolStore(identity, 1 + (Math.random() * 16_000).toInt())
        val address = SignalProtocolAddress(account, deviceId)

        fun bundle(): PreKeyBundle {
            val identity = store.identityKeyPair
            val preKey = ECKeyPair.generate()
            val signed = ECKeyPair.generate()
            val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signedSig = identity.privateKey.calculateSignature(signed.publicKey.serialize())
            val kyberSig = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
            store.storePreKey(1, PreKeyRecord(1, preKey))
            store.storeSignedPreKey(2, SignedPreKeyRecord(2, 0, signed, signedSig))
            store.storeKyberPreKey(3, KyberPreKeyRecord(3, 0, kyber, kyberSig))
            return PreKeyBundle(
                store.localRegistrationId, deviceId, 1, preKey.publicKey, 2, signed.publicKey, signedSig,
                identity.publicKey, 3, kyber.publicKey, kyberSig,
            )
        }

        fun encrypt(to: SignalProtocolAddress, text: String): CiphertextMessage =
            SessionCipher(store, address, to).encrypt(text.toByteArray())

        fun decrypt(from: SignalProtocolAddress, message: CiphertextMessage): String {
            val cipher = SessionCipher(store, address, from)
            val plain = when (message.type) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(message.serialize()))
                else -> cipher.decrypt(SignalMessage(message.serialize()))
            }
            return String(plain)
        }
    }

    private val sender = Phone(IdentityKeyPair.generate(), "alice", 1)
    private val receiver = Phone(IdentityKeyPair.generate(), "bob", 1)

    private fun talk(rounds: Int) {
        repeat(rounds) {
            receiver.decrypt(sender.address, sender.encrypt(receiver.address, "to bob $it"))
            sender.decrypt(receiver.address, receiver.encrypt(sender.address, "to alice $it"))
        }
    }

    private fun recover() {
        receiver.store.deleteSession(sender.address)
        SessionBuilder(receiver.store, sender.address, receiver.address).process(sender.bundle())
        assertEquals("new session", sender.decrypt(receiver.address, receiver.encrypt(sender.address, "new session")))
    }

    @Test
    fun `a receiver that lost its session recovers`() {
        SessionBuilder(sender.store, receiver.address, sender.address).process(receiver.bundle())
        talk(3)
        receiver.store.deleteSession(sender.address)

        val failure = runCatching { receiver.decrypt(sender.address, sender.encrypt(receiver.address, "lost")) }.exceptionOrNull()
        assertTrue(failure is NoSessionException)

        recover()
        assertEquals("retried", receiver.decrypt(sender.address, sender.encrypt(receiver.address, "retried")))
        assertEquals("both ways", sender.decrypt(receiver.address, receiver.encrypt(sender.address, "both ways")))
    }

    @Test
    fun `a receiver that went back to an old session recovers`() {
        SessionBuilder(sender.store, receiver.address, sender.address).process(receiver.bundle())
        talk(1)
        val old = receiver.store.loadSession(sender.address).serialize()
        talk(3)
        receiver.store.storeSession(sender.address, SessionRecord(old))

        val failure = runCatching { receiver.decrypt(sender.address, sender.encrypt(receiver.address, "stale")) }.exceptionOrNull()
        assertTrue(failure is InvalidMessageException)

        recover()
        assertEquals("retried", receiver.decrypt(sender.address, sender.encrypt(receiver.address, "retried")))
    }
}
