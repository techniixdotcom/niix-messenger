package app.niix.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayStoreTest {

    private val recipientKey = ByteArray(33) { 1 }
    private val recipientHash = RelayProtocol.sha256(recipientKey)
    private val senderKey = ByteArray(33) { 2 }

    @Test
    fun `store then fetch returns the envelope`() {
        val store = RelayStore()
        val now = 1_000_000L
        val failure = store.store(recipientHash, senderKey, "hello".toByteArray(), 60_000L, now)
        assertNull(failure)

        val fetched = store.fetch(recipientHash, now)
        assertEquals(1, fetched.size)
        assertEquals("hello", String(fetched[0].envelope))
    }

    @Test
    fun `expired envelopes are not returned by fetch`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "hello".toByteArray(), 1000L, now)
        val fetched = store.fetch(recipientHash, now + 5000L)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `purgeExpired removes only expired envelopes`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "a".toByteArray(), 1000L, now)
        store.store(recipientHash, senderKey, "b".toByteArray(), 100_000L, now)

        val purged = store.purgeExpired(now + 5000L)
        assertEquals(1, purged)
        assertEquals(1, store.fetch(recipientHash, now + 5000L).size)
    }

    @Test
    fun `ttl is clamped to the protocol maximum`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "hello".toByteArray(), Long.MAX_VALUE, now)
        val fetched = store.fetch(recipientHash, now)
        val expected = now + RelayProtocol.MAX_RELAY_TTL_MILLIS
        assertEquals(expected, fetched[0].expiresAt)
    }

    @Test
    fun `per recipient quota is enforced`() {
        val store = RelayStore()
        val now = 1_000_000L
        repeat(RelayProtocol.MAX_RELAY_ENVELOPES_PER_HASH) {
            val failure = store.store(recipientHash, senderKey, "msg$it".toByteArray(), 60_000L, now)
            assertNull(failure)
        }
        val overflow = store.store(recipientHash, senderKey, "one too many".toByteArray(), 60_000L, now)
        assertEquals(RelayRejectReason.RECIPIENT_QUOTA_FULL, overflow)
    }

    @Test
    fun `global byte ceiling is enforced`() {
        val store = RelayStore(maxTotalRelayBytes = 10)
        val now = 1_000_000L
        val ok = store.store(recipientHash, senderKey, ByteArray(10), 60_000L, now)
        assertNull(ok)
        val overflow = store.store(recipientHash, senderKey, ByteArray(1), 60_000L, now)
        assertEquals(RelayRejectReason.RECIPIENT_QUOTA_FULL, overflow)
    }

    @Test
    fun `delete receipt removes exactly the matching envelope`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "a".toByteArray(), 60_000L, now)
        store.store(recipientHash, senderKey, "b".toByteArray(), 60_000L, now)
        val entries = store.fetch(recipientHash, now)
        val hashToDelete = entries.first { String(it.envelope) == "a" }.envelopeHash

        assertTrue(store.deleteReceipt(recipientHash, hashToDelete))
        val remaining = store.fetch(recipientHash, now)
        assertEquals(1, remaining.size)
        assertEquals("b", String(remaining[0].envelope))
    }

    @Test
    fun `delete receipt for unknown hash returns false`() {
        val store = RelayStore()
        assertFalse(store.deleteReceipt(recipientHash, ByteArray(32)))
    }

    @Test
    fun `replaying an identical envelope is a no-op, not a duplicate entry`() {
        val store = RelayStore()
        val now = 1_000_000L
        val first = store.store(recipientHash, senderKey, "hello".toByteArray(), 60_000L, now)
        assertNull(first)

        val replay = store.store(recipientHash, senderKey, "hello".toByteArray(), 60_000L, now + 10)
        assertNull(replay)

        val fetched = store.fetch(recipientHash, now)
        assertEquals(1, fetched.size)
    }

    @Test
    fun `an expired envelope's hash can legitimately be stored again`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "hello".toByteArray(), 1000L, now)

        val second = store.store(recipientHash, senderKey, "hello".toByteArray(), 60_000L, now + 5000)
        assertNull(second)
        assertEquals(1, store.fetch(recipientHash, now + 5000).size)
    }

    @Test
    fun `replay dedup is scoped per recipient, not global`() {
        val store = RelayStore()
        val now = 1_000_000L
        val otherRecipientHash = RelayProtocol.sha256(ByteArray(33) { 9 })
        store.store(recipientHash, senderKey, "hello".toByteArray(), 60_000L, now)

        val forOther = store.store(otherRecipientHash, senderKey, "hello".toByteArray(), 60_000L, now)
        assertNull(forOther)
        assertEquals(1, store.fetch(recipientHash, now).size)
        assertEquals(1, store.fetch(otherRecipientHash, now).size)
    }

    @Test
    fun `clear wipes everything`() {
        val store = RelayStore()
        val now = 1_000_000L
        store.store(recipientHash, senderKey, "a".toByteArray(), 60_000L, now)
        store.clear()
        assertTrue(store.fetch(recipientHash, now).isEmpty())
        assertEquals(0, store.currentTotalBytes())
    }
}
