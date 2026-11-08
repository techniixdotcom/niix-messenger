package app.niix.core.messaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The call messages on the wire: round trips, and refusal of anything malformed. */
class CallWireTest {

    private val callId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val secret = ByteArray(32) { (it * 3).toByte() }
    private val onion = "c".repeat(56) + ".onion"

    @Test
    fun `call permission round trips`() {
        for (allowed in listOf(true, false)) {
            val decoded = WireCodec.decode(WireCodec.encode(WireMessage.CallPermission(allowed)))
            assertEquals(WireMessage.CallPermission(allowed), decoded)
        }
    }

    @Test
    fun `call offer round trips`() {
        val decoded = WireCodec.decode(WireCodec.encode(WireMessage.CallOffer(callId, secret, 1_700_000_000_000L)))
        decoded as WireMessage.CallOffer
        assertEquals(callId, decoded.callId)
        assertArrayEquals(secret, decoded.secret)
        assertEquals(1_700_000_000_000L, decoded.sentAtMillis)
    }

    @Test
    fun `call answer round trips`() {
        val decoded = WireCodec.decode(WireCodec.encode(WireMessage.CallAnswer(callId, secret))) as WireMessage.CallAnswer
        assertEquals(callId, decoded.callId)
        assertArrayEquals(secret, decoded.secret)
    }

    @Test
    fun `call end round trips, unknown reasons included`() {
        for (reason in listOf(CallEndReasons.HANGUP, CallEndReasons.FAILED, 200)) {
            assertEquals(WireMessage.CallEnd(callId, reason), WireCodec.decode(WireCodec.encode(WireMessage.CallEnd(callId, reason))))
        }
    }

    @Test
    fun `call messages survive padding`() {
        val wire = WireMessage.CallOffer(callId, secret, 5L)
        val decoded = WireCodec.decode(MessagePadding.unpad(MessagePadding.pad(WireCodec.encode(wire)))) as WireMessage.CallOffer
        assertEquals(callId, decoded.callId)
    }

    @Test
    fun `invalid call ids are refused on both ends`() {
        val bad = listOf("", "not-a-uuid", callId.uppercase(), "$callId ", "../../$callId", "x".repeat(5000))
        for (id in bad) {
            try {
                WireCodec.encode(WireMessage.CallEnd(id, CallEndReasons.HANGUP))
                fail("Encoding accepted call id '$id'")
            } catch (_: IllegalArgumentException) {
            }
            val forged = frame(24) { writeUTF(id.take(1000)); writeByte(1) }
            try {
                WireCodec.decode(forged)
                fail("Decoding accepted call id '$id'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `call secrets must be exactly 32 bytes`() {
        for (size in listOf(0, 16, 31, 33, 64)) {
            try {
                WireCodec.encode(WireMessage.CallAnswer(callId, ByteArray(size)))
                fail("Encoding accepted a $size-byte secret")
            } catch (_: IllegalArgumentException) {
            }
            val forged = frame(23) {
                writeUTF(callId)
                writeInt(size)
                write(ByteArray(size))
            }
            try {
                WireCodec.decode(forged)
                fail("Decoding accepted a $size-byte secret")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `a huge secret length is refused without allocating it`() {
        val forged = frame(22) {
            writeUTF(callId)
            writeInt(Int.MAX_VALUE)
        }
        try {
            WireCodec.decode(forged)
            fail("Accepted a huge secret length")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `every truncation of a call offer is refused cleanly`() {
        val valid = WireCodec.encode(WireMessage.CallOffer(callId, secret, 99L))
        for (length in 0 until valid.size) {
            try {
                WireCodec.decode(valid.copyOf(length))
                fail("Accepted an offer cut to $length bytes")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun `contact update carries whether calls are allowed`() {
        for (value in listOf(true, false, null)) {
            val wire = WireMessage.ContactUpdate(onion, "Name", null, verified = true, blocked = false, callsAllowed = value)
            val decoded = WireCodec.decode(WireCodec.encode(wire)) as WireMessage.ContactUpdate
            assertEquals(value, decoded.callsAllowed)
            assertTrue(decoded.verified)
        }
    }

    @Test
    fun `contact update from a build before calls still decodes`() {
        val old = frame(19) {
            writeUTF(onion)
            writeUTF("Name")
            writeBoolean(false)
            writeBoolean(false)
            writeBoolean(true)
            writeBoolean(false)
        }
        val decoded = WireCodec.decode(old) as WireMessage.ContactUpdate
        assertNull(decoded.callsAllowed)
        assertTrue(decoded.blocked)
    }

    @Test
    fun `a contact update whose calls flag starts and is cut off is refused`() {
        val cut = frame(19) {
            writeUTF(onion)
            writeUTF("Name")
            writeBoolean(false)
            writeBoolean(false)
            writeBoolean(false)
            writeBoolean(false)
            writeBoolean(true)
        }
        try {
            WireCodec.decode(cut)
            fail("Accepted a truncated calls flag")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `call ids from UUID randomUUID are valid`() {
        repeat(100) { assertTrue(WireCodec.isValidCallId(java.util.UUID.randomUUID().toString())) }
    }

    private fun frame(type: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeByte(1)
            it.writeByte(type)
            it.body()
        }
        return bytes.toByteArray()
    }
}
