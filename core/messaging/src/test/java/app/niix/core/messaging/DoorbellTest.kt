package app.niix.core.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorbellTest {

    private val key = ByteArray(Doorbell.KEY_BYTES) { it.toByte() }
    private val nonce = ByteArray(Doorbell.NONCE_BYTES) { (it * 3).toByte() }
    private val device = "a".repeat(56) + ".onion"

    @Test
    fun tokenMatchesOnlyItsKeyAndDevice() {
        val token = Doorbell.token(key, device, 1_700_000_000_000L, nonce)
        assertEquals(Doorbell.TOKEN_BYTES, token.size)
        val parsed = Doorbell.parse(token)
        assertNotNull(parsed)
        assertEquals(1_700_000_000_000L, parsed!!.sentAtMillis)
        assertTrue(Doorbell.matches(parsed, key, device))
        assertFalse(Doorbell.matches(parsed, ByteArray(Doorbell.KEY_BYTES) { 9 }, device))
        assertFalse("a token for one device doesn't ring another", Doorbell.matches(parsed, key, "b".repeat(56) + ".onion"))
    }

    @Test
    fun changedTokenIsRefused() {
        val token = Doorbell.token(key, device, 5L, nonce)
        for (i in token.indices) {
            val changed = token.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", Doorbell.matches(Doorbell.parse(changed)!!, key, device))
        }
        assertNull(Doorbell.parse(token.copyOf(token.size - 1)))
        assertNull(Doorbell.parse(token + 0))
    }

    @Test
    fun callPermissionCarriesRingKeyOnlyWhenAllowing() {
        val allowed = WireCodec.decode(WireCodec.encode(WireMessage.CallPermission(true, key))) as WireMessage.CallPermission
        assertTrue(allowed.allowed)
        assertTrue(allowed.ringKey!!.contentEquals(key))

        val withdrawn = WireCodec.decode(WireCodec.encode(WireMessage.CallPermission(false, key))) as WireMessage.CallPermission
        assertFalse(withdrawn.allowed)
        assertNull(withdrawn.ringKey)

        // What a version from before the doorbell sends: the flag and nothing after it.
        val old = WireCodec.encode(WireMessage.CallPermission(true))
        val decoded = WireCodec.decode(old) as WireMessage.CallPermission
        assertTrue(decoded.allowed)
        assertNull(decoded.ringKey)

        // And what such a version does with ours: reads the flag, ignores the rest.
        val ours = WireCodec.encode(WireMessage.CallPermission(true, key))
        assertEquals(old.size + Doorbell.KEY_BYTES, ours.size)
        assertTrue(ours.copyOf(old.size).contentEquals(old))
    }
}
