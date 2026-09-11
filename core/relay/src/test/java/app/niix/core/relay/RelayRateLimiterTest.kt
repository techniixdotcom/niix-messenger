package app.niix.core.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRateLimiterTest {

    private val senderA = ByteArray(33) { 1 }
    private val senderB = ByteArray(33) { 2 }

    @Test
    fun `allows up to the configured max within the window`() {
        val limiter = RelayRateLimiter(maxPerWindow = 3, windowMillis = 60_000)
        val now = 1_000_000L
        assertTrue(limiter.allow(senderA, now))
        assertTrue(limiter.allow(senderA, now + 1))
        assertTrue(limiter.allow(senderA, now + 2))
        assertFalse(limiter.allow(senderA, now + 3))
    }

    @Test
    fun `tracks each sender independently`() {
        val limiter = RelayRateLimiter(maxPerWindow = 1, windowMillis = 60_000)
        val now = 1_000_000L
        assertTrue(limiter.allow(senderA, now))
        assertTrue(limiter.allow(senderB, now))
        assertFalse(limiter.allow(senderA, now))
        assertFalse(limiter.allow(senderB, now))
    }

    @Test
    fun `old events roll out of the window`() {
        val limiter = RelayRateLimiter(maxPerWindow = 1, windowMillis = 1000)
        val now = 1_000_000L
        assertTrue(limiter.allow(senderA, now))
        assertFalse(limiter.allow(senderA, now + 500))
        assertTrue(limiter.allow(senderA, now + 1001))
    }
}
