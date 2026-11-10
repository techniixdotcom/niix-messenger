package app.niix.core.nostr

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrCryptoTest {

    private fun hex(s: String) = Hex.decode(s.lowercase())

    // Vectors 0 and 1 from BIP-340's test-vectors.csv (also checked against libsecp256k1).
    @Test
    fun `BIP-340 test vector 0`() {
        val secret = hex("0000000000000000000000000000000000000000000000000000000000000003")
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Hex.encode(Bip340.publicKey(secret)))
        val sig = Bip340.sign(secret, ByteArray(32), ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Hex.encode(sig),
        )
        assertTrue(Bip340Verifier.verify(Bip340.publicKey(secret), ByteArray(32), sig))
    }

    @Test
    fun `BIP-340 test vector 1`() {
        val secret = hex("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val aux = hex("0000000000000000000000000000000000000000000000000000000000000001")
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", Hex.encode(Bip340.publicKey(secret)))
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            Hex.encode(Bip340.sign(secret, msg, aux)),
        )
    }

    @Test
    fun `a tampered signature does not verify`() {
        val secret = hex("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val msg = ByteArray(32) { it.toByte() }
        val sig = Bip340.sign(secret, msg, ByteArray(32))
        sig[40] = (sig[40].toInt() xor 1).toByte()
        assertFalse(Bip340Verifier.verify(Bip340.publicKey(secret), msg, sig))
    }

    @Test
    fun `each event gets its own valid one-off key`() {
        val rng = SecureRandom()
        val tags = listOf(listOf("z", "ab".repeat(32)))
        val a = NostrEvent.signWithThrowawayKey(1_700_000_000, NostrMailbox.KIND, tags, "content", rng)
        val b = NostrEvent.signWithThrowawayKey(1_700_000_000, NostrMailbox.KIND, tags, "content", rng)
        assertTrue(Bip340Verifier.verifyEvent(a))
        assertTrue(Bip340Verifier.verifyEvent(b))
        assertNotEquals(a.pubkey, b.pubkey)
        assertFalse(Bip340Verifier.verifyEvent(a.copy(content = "changed")))
    }

    @Test
    fun `events round-trip through JSON`() {
        val event = NostrEvent.signWithThrowawayKey(1_700_000_000, 1, listOf(listOf("z", "x")), "a \"quoted\"\nline", SecureRandom())
        val parsed = NostrEvent.fromJson(Json.parse(event.toJson()))
        assertEquals(event, parsed)
        assertTrue(Bip340Verifier.verifyEvent(parsed!!))
    }

    @Test
    fun `event id matches the NIP-01 serialisation`() {
        // Computed independently: sha256 of [0,"<pubkey>",1,1,[["z","t"]],"hi\n"] with NIP-01
        // escaping.
        val id = NostrEvent.computeId("a".repeat(64), 1, 1, listOf(listOf("z", "t")), "hi\n")
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("[0,\"${"a".repeat(64)}\",1,1,[[\"z\",\"t\"]],\"hi\\n\"]".toByteArray())
        assertArrayEquals(expected, id)
    }

    @Test
    fun `sealed envelopes open with the right key`() {
        val rng = SecureRandom()
        val secret = MailboxCrypto.newSecret(rng)
        val epoch = MailboxCrypto.epochOf(1_700_000_000)
        val plaintext = ByteArray(1000) { (it * 7).toByte() }
        val sealed = MailboxCrypto.seal(secret, epoch, plaintext, rng)
        val opened = MailboxCrypto.open(MailboxCrypto.key(secret, epoch), MailboxCrypto.tag(secret, epoch), sealed)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `wrong key, tag or epoch does not open`() {
        val rng = SecureRandom()
        val secret = MailboxCrypto.newSecret(rng)
        val other = MailboxCrypto.newSecret(rng)
        val epoch = 100L
        val sealed = MailboxCrypto.seal(secret, epoch, byteArrayOf(1, 2, 3), rng)
        assertNull(MailboxCrypto.open(MailboxCrypto.key(other, epoch), MailboxCrypto.tag(secret, epoch), sealed))
        assertNull(MailboxCrypto.open(MailboxCrypto.key(secret, epoch + 1), MailboxCrypto.tag(secret, epoch), sealed))
        assertNull(MailboxCrypto.open(MailboxCrypto.key(secret, epoch), MailboxCrypto.tag(secret, epoch + 1), sealed))
        assertNull(MailboxCrypto.open(MailboxCrypto.key(secret, epoch), MailboxCrypto.tag(secret, epoch), "not base64!"))
    }

    @Test
    fun `tags change every epoch and are stable within one`() {
        val a = MailboxCrypto.newSecret(SecureRandom())
        assertNotEquals(MailboxCrypto.tag(a, 1), MailboxCrypto.tag(a, 2))
        assertEquals(MailboxCrypto.tag(a, 1), MailboxCrypto.tag(a, 1))
        assertTrue(Hex.isHex(MailboxCrypto.tag(a, 1), 32))
    }

    @Test
    fun `epoch boundaries`() {
        assertEquals(0L, MailboxCrypto.epochOf(0))
        assertEquals(0L, MailboxCrypto.epochOf(MailboxCrypto.EPOCH_SECONDS - 1))
        assertEquals(1L, MailboxCrypto.epochOf(MailboxCrypto.EPOCH_SECONDS))
    }
}
