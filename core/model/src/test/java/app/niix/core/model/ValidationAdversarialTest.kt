package app.niix.core.model

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OnionAddress.parse] validates strings that come from untrusted places -- scanned QR codes,
 * pasted contact codes, and peer-supplied fields inside wire messages. Anything it wrongly
 * accepts becomes a conversation id, a database key, and a connection target, so the failure
 * mode for a bad accept is considerably worse than for a bad reject.
 */
class OnionAddressAdversarialTest {

    private val validBody = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"
    private val valid = "$validBody.onion"

    @Test
    fun `a well-formed v3 address is accepted`() {
        assertEquals(valid, OnionAddress.parse(valid).value)
    }

    @Test
    fun `length must be exactly 56 characters`() {
        // One short and one long must both fail -- an off-by-one here would accept addresses
        // that can never resolve, or reject legitimate ones.
        assertNull(OnionAddress.parseOrNull(validBody.dropLast(1) + ".onion"))
        assertNull(OnionAddress.parseOrNull(validBody + "a.onion"))
    }

    @Test
    fun `the onion suffix is required`() {
        assertNull(OnionAddress.parseOrNull(validBody))
        assertNull(OnionAddress.parseOrNull("$validBody.onion.evil.com"))
        assertNull(OnionAddress.parseOrNull("$validBody.oniony"))
    }

    @Test
    fun `characters outside base32 are rejected`() {
        // Tor v3 addresses are base32: a-z and 2-7 only. 0, 1, 8, 9 and punctuation are not.
        for (bad in listOf('0', '1', '8', '9', '-', '_', '/', '.', ' ', '@')) {
            val candidate = bad + validBody.drop(1) + ".onion"
            assertNull("accepted an address containing '$bad'", OnionAddress.parseOrNull(candidate))
        }
    }

    @Test
    fun `path traversal and injection shapes are rejected`() {
        val hostile = listOf(
            "../../../etc/passwd",
            "$validBody.onion/../../evil",
            "$validBody.onion\u0000.evil",
            "http://$valid",
            "$valid:9001",
            "'; DROP TABLE conversations;--",
        )
        for (candidate in hostile) {
            assertNull("accepted hostile input: $candidate", OnionAddress.parseOrNull(candidate))
        }
    }

    @Test
    fun `surrounding whitespace is normalised away rather than stored`() {
        // parse() trims deliberately -- pasted contact codes routinely carry a stray newline,
        // and rejecting those would be user-hostile for no security gain. What matters is that
        // the *stored* value is always the clean address: whitespace must never survive into
        // something later used as a conversation id, database key, or connection target.
        for (candidate in listOf("\n$valid", "$valid\n", "  $valid  ", "\t$valid\r\n")) {
            val parsed = OnionAddress.parseOrNull(candidate)
                ?: fail("rejected an address that only had surrounding whitespace: $candidate")
            assertEquals(valid, (parsed as OnionAddress).value)
        }
        // Whitespace *inside* the address is a different matter entirely and must still fail.
        assertNull(OnionAddress.parseOrNull(validBody.take(10) + " " + validBody.drop(11) + ".onion"))
    }

    @Test
    fun `uppercase is normalised rather than rejected`() {
        // Tor addresses are case-insensitive; the parser lowercases. This matters because the
        // value becomes a database key -- if two casings both parsed but to different values,
        // the same peer could occupy two distinct conversation rows.
        val upper = valid.uppercase()
        assertEquals(valid, OnionAddress.parse(upper).value)
    }

    @Test
    fun `random garbage is never accepted and never throws from parseOrNull`() {
        val random = Random(20260905L)
        val alphabet = ("abcdefghijklmnopqrstuvwxyz0123456789.-_/ \u0000\n" + "ABCDEF").toCharArray()
        repeat(20_000) {
            val len = random.nextInt(80)
            val candidate = buildString {
                repeat(len) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            val parsed = try {
                OnionAddress.parseOrNull(candidate)
            } catch (t: Throwable) {
                fail("parseOrNull threw ${t::class.java.name} for input: $candidate")
                null
            }
            if (parsed != null) {
                // Anything it *does* accept must genuinely satisfy the format, not merely have
                // slipped through -- this is what catches a regex that's too permissive.
                assertTrue(
                    "accepted a malformed address: ${parsed.value}",
                    parsed.value.length == 62 &&
                        parsed.value.endsWith(".onion") &&
                        parsed.value.dropLast(6).all { it in 'a'..'z' || it in '2'..'7' },
                )
            }
        }
    }
}

/**
 * [clampDisappearSeconds] is the last line of defence for the audit finding that a remote peer
 * could send an unchecked disappearing-message duration -- a negative or absurdly large value
 * reaching `now + seconds * 1000` overflows or underflows silently, which can make a message
 * "expire" the instant it arrives.
 */
class DisappearSecondsClampTest {

    @Test
    fun `ordinary durations pass through unchanged`() {
        for (seconds in listOf(0L, 1L, 60L, 3600L, 86_400L, MAX_DISAPPEAR_SECONDS)) {
            assertEquals(seconds, clampDisappearSeconds(seconds))
        }
    }

    @Test
    fun `negative durations clamp to zero`() {
        assertEquals(0L, clampDisappearSeconds(-1L))
        assertEquals(0L, clampDisappearSeconds(-86_400L))
        assertEquals(0L, clampDisappearSeconds(Long.MIN_VALUE))
    }

    @Test
    fun `absurd durations clamp to the maximum`() {
        assertEquals(MAX_DISAPPEAR_SECONDS, clampDisappearSeconds(MAX_DISAPPEAR_SECONDS + 1))
        assertEquals(MAX_DISAPPEAR_SECONDS, clampDisappearSeconds(Long.MAX_VALUE))
    }

    @Test
    fun `no input can produce an expiry timestamp that overflows or lands in the past`() {
        // The actual arithmetic MessageDao performs. Long.MAX_VALUE here is what would silently
        // wrap to a negative timestamp -- i.e. already expired -- without the clamp.
        val now = System.currentTimeMillis()
        val hostile = listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE, Long.MAX_VALUE / 1000, MAX_DISAPPEAR_SECONDS + 1)
        for (seconds in hostile) {
            val expiresAt = now + clampDisappearSeconds(seconds) * 1000
            assertTrue("input $seconds produced an expiry before now ($expiresAt < $now)", expiresAt >= now)
        }
    }

    @Test
    fun `random longs always clamp into range`() {
        val random = Random(20260906L)
        repeat(20_000) {
            val clamped = clampDisappearSeconds(random.nextLong())
            assertTrue("clamp produced out-of-range value $clamped", clamped in 0..MAX_DISAPPEAR_SECONDS)
        }
    }
}
