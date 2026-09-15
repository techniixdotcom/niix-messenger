package app.niix.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ProofReplayCache].
 *
 * The property under test is narrow but load-bearing: a relay fetch or delete proof must be
 * honoured exactly once. A replayed proof is a valid signature, so nothing in the signature
 * verification path notices it, this cache is the only thing standing between a captured proof
 * and an attacker fetching someone else's stored envelopes.
 */
class ProofReplayCacheTest {

    private val window = 60_000L

    private fun proof(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `a proof is accepted the first time`() {
        val cache = ProofReplayCache(window)
        assertTrue(cache.offer(proof(1, 2, 3), 1_000L))
    }

    @Test
    fun `the same proof is refused immediately afterwards`() {
        val cache = ProofReplayCache(window)
        cache.offer(proof(1, 2, 3), 1_000L)
        assertFalse(cache.offer(proof(1, 2, 3), 1_001L))
    }

    @Test
    fun `the same proof is refused throughout the acceptance window`() {
 // The window is exactly the period during which a captured proof would otherwise still
 // verify, so this is the whole attack surface.
        val cache = ProofReplayCache(window)
        cache.offer(proof(7), 1_000L)
        assertFalse(cache.offer(proof(7), 1_000L + window - 1))
    }

    @Test
    fun `a distinct proof is unaffected by another being cached`() {
        val cache = ProofReplayCache(window)
        cache.offer(proof(1), 1_000L)
        assertTrue(cache.offer(proof(2), 1_000L))
    }

    @Test
    fun `proofs differing in a single byte are treated as distinct`() {
 // Guards against a sloppy key derivation that collapsed similar proofs together, which
 // would refuse legitimate distinct requests.
        val cache = ProofReplayCache(window)
        assertTrue(cache.offer(proof(1, 2, 3), 1_000L))
        assertTrue(cache.offer(proof(1, 2, 4), 1_000L))
    }

    @Test
    fun `an empty proof does not collide with a different empty-ish proof`() {
        val cache = ProofReplayCache(window)
        assertTrue(cache.offer(ByteArray(0), 1_000L))
        assertTrue(cache.offer(ByteArray(1), 1_000L))
    }

    @Test
    fun `entries are dropped once the proof could no longer be accepted anyway`() {
 // Not a correctness requirement so much as a memory one: a relay serving many recipients
 // must not accumulate every proof it has ever seen.
        val cache = ProofReplayCache(window)
        cache.offer(proof(1), 1_000L)
        cache.offer(proof(2), 1_000L)
        assertEquals(2, cache.size())

 // Any call past the window prunes; the proof offered here is what remains.
        cache.offer(proof(3), 1_000L + window + 1)
        assertEquals(1, cache.size())
    }

    @Test
    fun `a proof becomes offerable again only after it has expired on its own terms`() {
        val cache = ProofReplayCache(window)
        assertTrue(cache.offer(proof(9), 1_000L))
        assertFalse(cache.offer(proof(9), 1_000L + window - 1))
 // Past the window the proof's own timestamp check would reject it, so forgetting it here
 // grants nothing an attacker could use.
        assertTrue(cache.offer(proof(9), 1_000L + window + 1))
    }

    @Test
    fun `the cache does not grow without bound under sustained distinct proofs`() {
        val cache = ProofReplayCache(window)
 // Each proof is offered a full window apart, so every one should evict the last.
        for (i in 1..50) {
            cache.offer(proof(i), i * (window + 1))
        }
        assertEquals(1, cache.size())
    }
}
