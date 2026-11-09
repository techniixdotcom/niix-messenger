package app.niix.core.crypto

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * What multi-device rests on, checked against the real libsignal: devices of one account share
 * its identity key but each has its own sessions, and a contact holds a separate session per
 * device, named by the account and the device number.
 */
class MultiDeviceSessionTest {

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

    private val accountIdentity = IdentityKeyPair.generate()
    private val first = Phone(accountIdentity, "alice", 1)
    private val second = Phone(accountIdentity, "alice", 2)
    private val contact = Phone(IdentityKeyPair.generate(), "bob", 1)

    @Test
    fun `a contact reaches each device through its own session`() {
        SessionBuilder(contact.store, first.address, contact.address).process(first.bundle())
        SessionBuilder(contact.store, second.address, contact.address).process(second.bundle())

        assertEquals("hi", first.decrypt(contact.address, contact.encrypt(first.address, "hi")))
        assertEquals("hi", second.decrypt(contact.address, contact.encrypt(second.address, "hi")))
        assertEquals(listOf(2), contact.store.getSubDeviceSessions("alice"))
    }

    @Test
    fun `both devices show the contact the same identity key`() {
        SessionBuilder(contact.store, first.address, contact.address).process(first.bundle())
        SessionBuilder(contact.store, second.address, contact.address).process(second.bundle())
        assertEquals(accountIdentity.publicKey, contact.store.getIdentity(first.address))
        assertEquals(accountIdentity.publicKey, contact.store.getIdentity(second.address))
    }

    @Test
    fun `a second device can start the conversation and the contact replies to it`() {
        SessionBuilder(second.store, contact.address, second.address).process(contact.bundle())
        assertEquals("from my laptop", contact.decrypt(second.address, second.encrypt(contact.address, "from my laptop")))
        assertEquals("got it", second.decrypt(contact.address, contact.encrypt(second.address, "got it")))
    }

    @Test
    fun `devices of one account talk to each other`() {
        SessionBuilder(first.store, second.address, first.address).process(second.bundle())
        assertEquals("sync", second.decrypt(first.address, first.encrypt(second.address, "sync")))
    }

    @Test
    fun `each device has its own sender key, told apart by device number`() {
        val group = UUID.randomUUID()
        val fromFirst = GroupSessionBuilder(first.store).create(first.address, group)
        val fromSecond = GroupSessionBuilder(second.store).create(second.address, UUID.randomUUID())
        GroupSessionBuilder(contact.store).process(first.address, SenderKeyDistributionMessage(fromFirst.serialize()))
        GroupSessionBuilder(contact.store).process(second.address, SenderKeyDistributionMessage(fromSecond.serialize()))

        val one = GroupCipher(first.store, first.address).encrypt(group, "one".toByteArray())
        val two = GroupCipher(second.store, second.address).encrypt(fromSecond.distributionId, "two".toByteArray())
        assertArrayEquals("one".toByteArray(), GroupCipher(contact.store, first.address).decrypt(one.serialize()))
        assertArrayEquals("two".toByteArray(), GroupCipher(contact.store, second.address).decrypt(two.serialize()))
        assertNotEquals(fromFirst.distributionId, fromSecond.distributionId)
    }
}
