package app.niix.core.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SlidingWindowLimiter].
 *
 * This one class backs every rate limit in the app: inbound connections, per-sender message and
 * byte quotas, attachment throughput. A flaw here is not a local bug, it silently removes the
 * ceiling from all of them at once, and nothing reports that a limit stopped limiting.
 *
 * Time is injected rather than read from the clock, so these exercise the real boundaries
 * instead of approximating them with sleeps.
 */
class SlidingWindowLimiterTest {

    @Test
    fun `allows up to the limit and refuses the next`() {
        val limiter = SlidingWindowLimiter(maxWeight = 3, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertTrue(limiter.allow(nowMillis = 0))
        assertTrue(limiter.allow(nowMillis = 0))
        assertFalse(limiter.allow(nowMillis = 0))
    }

    @Test
    fun `a refused call consumes nothing`() {
 // If a rejected call still counted, a caller hammering a full limiter would keep it full
 // forever, the window could never drain and the limit would become permanent.
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertFalse(limiter.allow(nowMillis = 100))
        assertFalse(limiter.allow(nowMillis = 200))
 // One window after the single accepted call, capacity returns.
        assertTrue(limiter.allow(nowMillis = 1001))
    }

    @Test
    fun `capacity returns only after the window has fully passed`() {
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
 // Exactly at the boundary the event is still inside the window: the check is strictly
 // greater than, so equal is not yet expired.
        assertFalse(limiter.allow(nowMillis = 1000))
        assertTrue(limiter.allow(nowMillis = 1001))
    }

    @Test
    fun `weight is respected, not just the count`() {
 // Byte-based limits depend on this: one large item must consume its full share rather
 // than counting as a single event.
        val limiter = SlidingWindowLimiter(maxWeight = 100, windowMillis = 1000)
        assertTrue(limiter.allow(weight = 60, nowMillis = 0))
        assertFalse(limiter.allow(weight = 50, nowMillis = 0))
        assertTrue(limiter.allow(weight = 40, nowMillis = 0))
    }

    @Test
    fun `a single item larger than the whole budget is refused`() {
 // It can never fit, so it must be refused rather than admitted and left to overflow the
 // window on its own.
        val limiter = SlidingWindowLimiter(maxWeight = 100, windowMillis = 1000)
        assertFalse(limiter.allow(weight = 101, nowMillis = 0))
 // And refusing it must not have consumed anything.
        assertTrue(limiter.allow(weight = 100, nowMillis = 0))
    }

    @Test
    fun `old events expire individually rather than all at once`() {
        val limiter = SlidingWindowLimiter(maxWeight = 2, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertTrue(limiter.allow(nowMillis = 500))
        assertFalse(limiter.allow(nowMillis = 600))

 // The first has aged out, the second has not: exactly one slot should be free.
        assertTrue(limiter.allow(nowMillis = 1001))
        assertFalse(limiter.allow(nowMillis = 1002))
    }

    @Test
    fun `a long idle period restores the full budget`() {
        val limiter = SlidingWindowLimiter(maxWeight = 2, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertTrue(limiter.allow(nowMillis = 0))
        assertFalse(limiter.allow(nowMillis = 0))

        assertTrue(limiter.allow(nowMillis = 1_000_000))
        assertTrue(limiter.allow(nowMillis = 1_000_000))
        assertFalse(limiter.allow(nowMillis = 1_000_000))
    }

    @Test
    fun `sustained traffic at the limit is allowed indefinitely`() {
 // A legitimate peer sending steadily at the permitted rate must never be throttled --
 // otherwise the limiter degrades into a cap on total lifetime usage.
        val limiter = SlidingWindowLimiter(maxWeight = 10, windowMillis = 1000)
        var now = 0L
        repeat(200) {
            assertTrue("throttled a peer operating within the limit", limiter.allow(nowMillis = now))
            now += 101
        }
    }

    @Test
    fun `zero weight does not consume budget`() {
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(weight = 0, nowMillis = 0))
        assertTrue(limiter.allow(weight = 0, nowMillis = 0))
        assertTrue(limiter.allow(weight = 1, nowMillis = 0))
        assertFalse(limiter.allow(weight = 1, nowMillis = 0))
    }

    @Test
    fun `time moving backwards does not grant extra capacity`() {
 // The wall clock can be changed, and several callers pass System.currentTimeMillis().
 // A backwards jump must not let a full limiter be topped up again.
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 10_000))
        assertFalse(limiter.allow(nowMillis = 5_000))
        assertFalse(limiter.allow(nowMillis = 0))
    }
}
