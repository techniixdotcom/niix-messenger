package app.niix.core.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SlidingWindowLimiter], which backs every rate limit in the app (connections,
 * per-sender message and byte quotas, attachments). A bug here silently removes all of those limits
 * at once. Time is injected, so the boundaries are tested exactly instead of with sleeps.
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
        // If refused calls still counted, a caller hammering a full limiter would keep it full
        // forever.
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertFalse(limiter.allow(nowMillis = 100))
        assertFalse(limiter.allow(nowMillis = 200))
        // One window after the single accepted call, there's room again.
        assertTrue(limiter.allow(nowMillis = 1001))
    }

    @Test
    fun `capacity returns only after the window has fully passed`() {
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        // Exactly on the boundary the event is still inside the window (strictly greater than).
        assertFalse(limiter.allow(nowMillis = 1000))
        assertTrue(limiter.allow(nowMillis = 1001))
    }

    @Test
    fun `weight is respected, not just the count`() {
        // Byte limits depend on this: one big item uses its full weight, not one slot.
        val limiter = SlidingWindowLimiter(maxWeight = 100, windowMillis = 1000)
        assertTrue(limiter.allow(weight = 60, nowMillis = 0))
        assertFalse(limiter.allow(weight = 50, nowMillis = 0))
        assertTrue(limiter.allow(weight = 40, nowMillis = 0))
    }

    @Test
    fun `a single item larger than the whole budget is refused`() {
        // It can never fit, so refuse it rather than let it overflow the window.
        val limiter = SlidingWindowLimiter(maxWeight = 100, windowMillis = 1000)
        assertFalse(limiter.allow(weight = 101, nowMillis = 0))
        // And refusing it mustn't use anything up.
        assertTrue(limiter.allow(weight = 100, nowMillis = 0))
    }

    @Test
    fun `old events expire individually rather than all at once`() {
        val limiter = SlidingWindowLimiter(maxWeight = 2, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 0))
        assertTrue(limiter.allow(nowMillis = 500))
        assertFalse(limiter.allow(nowMillis = 600))

        // The first has aged out and the second hasn't, so exactly one slot is free.
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
        // A peer sending steadily at the allowed rate must never be throttled, or this becomes a
        // lifetime cap.
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
        // Callers pass System.currentTimeMillis(), which can jump backwards. That mustn't refill a
        // full limiter.
        val limiter = SlidingWindowLimiter(maxWeight = 1, windowMillis = 1000)
        assertTrue(limiter.allow(nowMillis = 10_000))
        assertFalse(limiter.allow(nowMillis = 5_000))
        assertFalse(limiter.allow(nowMillis = 0))
    }

    @Test
    fun `a new limiter is idle`() {
        assertTrue(SlidingWindowLimiter(maxWeight = 5, windowMillis = 1000).isIdle(nowMillis = 0))
    }

    @Test
    fun `a limiter with recent events is not idle`() {
        val limiter = SlidingWindowLimiter(maxWeight = 5, windowMillis = 1000)
        limiter.allow(nowMillis = 10_000)
        assertFalse(limiter.isIdle(nowMillis = 10_500))
    }

    @Test
    fun `a limiter becomes idle once its window has passed`() {
        val limiter = SlidingWindowLimiter(maxWeight = 5, windowMillis = 1000)
        limiter.allow(nowMillis = 10_000)
        assertTrue(limiter.isIdle(nowMillis = 11_001))
    }

    @Test
    fun `time moving backwards does not make a busy limiter look idle`() {
        // A limiter reported idle gets replaced by a fresh one, so a false "idle" would hand a
        // sender a full budget early.
        val limiter = SlidingWindowLimiter(maxWeight = 5, windowMillis = 1000)
        limiter.allow(nowMillis = 10_000)
        assertFalse(limiter.isIdle(nowMillis = 5_000))
    }
}
