package app.niix.core.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NostrInboxWireTest {

    @Test
    fun `nostr inbox round-trips`() {
        val secret = ByteArray(32) { it.toByte() }
        val relays = listOf("wss://relay.damus.io/", "wss://nos.lol/")
        val decoded = WireCodec.decode(WireCodec.encode(WireMessage.NostrInbox(secret, relays))) as WireMessage.NostrInbox
        assertArrayEquals(secret, decoded.secret)
        assertEquals(relays, decoded.relays)
    }

    @Test
    fun `withdrawal round-trips with an empty secret`() {
        val decoded = WireCodec.decode(WireCodec.encode(WireMessage.NostrInbox(ByteArray(0), emptyList()))) as WireMessage.NostrInbox
        assertTrue(decoded.secret.isEmpty())
        assertTrue(decoded.relays.isEmpty())
    }

    @Test
    fun `wrong secret length is rejected`() {
        expectRejected(WireMessage.NostrInbox(ByteArray(16), listOf("wss://relay.damus.io/")))
    }

    @Test
    fun `too many relays are rejected`() {
        expectRejected(WireMessage.NostrInbox(ByteArray(32), List(9) { "wss://r$it.example.com/" }))
    }

    @Test
    fun `truncated frame is rejected cleanly`() {
        val bytes = WireCodec.encode(WireMessage.NostrInbox(ByteArray(32), listOf("wss://relay.damus.io/")))
        for (cut in 2 until bytes.size) {
            try {
                WireCodec.decode(bytes.copyOf(cut))
                fail("Accepted a frame truncated to $cut bytes")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun `retired relay messages from older peers are accepted and ignored`() {
        // What an older build sends: version, type 10 or 11, then a payload nothing reads.
        for (type in listOf(10, 11)) {
            val bytes = byteArrayOf(1, type.toByte(), 0, 0, 0, 3, 1, 2, 3)
            assertEquals(WireMessage.Retired(type), WireCodec.decode(bytes))
        }
    }

    @Test
    fun `retired types are never sent`() {
        try {
            WireCodec.encode(WireMessage.Retired(10))
            fail("Encoded a retired wire type")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun expectRejected(message: WireMessage) {
        try {
            WireCodec.decode(WireCodec.encode(message))
            fail("Accepted an invalid NostrInbox")
        } catch (_: IllegalArgumentException) {
        }
    }
}
