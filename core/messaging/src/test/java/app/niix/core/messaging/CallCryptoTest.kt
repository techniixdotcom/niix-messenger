package app.niix.core.messaging

import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallCryptoTest {

    private val offer = ByteArray(32) { it.toByte() }
    private val answer = ByteArray(32) { (100 + it).toByte() }
    private val callId = "0f0e0d0c-0b0a-4908-8706-050403020100"
    private val alice = "a".repeat(56) + ".onion"
    private val bob = "b".repeat(56) + ".onion"

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        val okm = Hkdf.expand(prk, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `both sides derive the same keys`() {
        val caller = CallKeys.derive(offer, answer, callId, alice, bob)
        val callee = CallKeys.derive(offer.copyOf(), answer.copyOf(), callId, alice, bob)
        assertArrayEquals(caller.callerToCallee, callee.callerToCallee)
        assertArrayEquals(caller.calleeToCaller, callee.calleeToCaller)
        assertArrayEquals(caller.callerProof, callee.callerProof)
        assertArrayEquals(caller.calleeProof, callee.calleeProof)
    }

    @Test
    fun `both sides show the same eight-digit safety code, and another call shows another`() {
        val caller = CallKeys.derive(offer, answer, callId, alice, bob)
        val callee = CallKeys.derive(offer, answer, callId, alice, bob)
        assertEquals(caller.safetyCode, callee.safetyCode)
        assertTrue(Regex("^[0-9]{4} [0-9]{4}$").matches(caller.safetyCode))
        val other = CallKeys.derive(offer, ByteArray(32) { 9 }, callId, alice, bob)
        assertFalse(caller.safetyCode == other.safetyCode)
    }

    @Test
    fun `the four keys are all different`() {
        val keys = CallKeys.derive(offer, answer, callId, alice, bob)
        val all = listOf(keys.callerToCallee, keys.calleeToCaller, keys.callerProof, keys.calleeProof)
        for (i in all.indices) for (j in all.indices) if (i != j) assertFalse(all[i].contentEquals(all[j]))
    }

    @Test
    fun `keys are bound to the call and to who is calling whom`() {
        val base = CallKeys.derive(offer, answer, callId, alice, bob)
        val otherCall = CallKeys.derive(offer, answer, "0f0e0d0c-0b0a-4908-8706-050403020101", alice, bob)
        val swapped = CallKeys.derive(offer, answer, callId, bob, alice)
        val otherAnswer = CallKeys.derive(offer, ByteArray(32) { 7 }, callId, alice, bob)
        for (other in listOf(otherCall, swapped, otherAnswer)) {
            assertFalse(base.callerToCallee.contentEquals(other.callerToCallee))
            assertFalse(base.callerProof.contentEquals(other.callerProof))
        }
    }

    @Test
    fun `the derivation does not modify the secrets it was given`() {
        val o = offer.copyOf()
        val a = answer.copyOf()
        CallKeys.derive(o, a, callId, alice, bob)
        assertArrayEquals(offer, o)
        assertArrayEquals(answer, a)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `identical halves are refused`() {
        CallKeys.derive(offer, offer.copyOf(), callId, alice, bob)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short secret is refused`() {
        CallKeys.derive(ByteArray(16), answer, callId, alice, bob)
    }

    @Test
    fun `wipe zeroes every key`() {
        val keys = CallKeys.derive(offer, answer, callId, alice, bob)
        keys.wipe()
        for (k in listOf(keys.callerToCallee, keys.calleeToCaller, keys.callerProof, keys.calleeProof)) {
            assertTrue(k.all { it == 0.toByte() })
        }
    }

    @Test
    fun `records open in order on the other side`() {
        val keys = CallKeys.derive(offer, answer, callId, alice, bob)
        val sender = MediaCipher(keys.callerToCallee)
        val receiver = MediaCipher(keys.callerToCallee)
        repeat(50) { n ->
            val plain = MediaRecord.audio(CallCodecs.OPUS, listOf(byteArrayOf(n.toByte(), 1, 2)))
            val sealed = sender.seal(plain)
            assertEquals(plain.size + MediaCipher.TAG_BYTES, sealed.size)
            assertArrayEquals(plain, receiver.open(sealed))
        }
    }

    @Test
    fun `a dropped, repeated or reordered record fails`() {
        val key = ByteArray(32) { 3 }
        val sender = MediaCipher(key)
        val first = sender.seal(MediaRecord.audio(CallCodecs.OPUS, emptyList()))
        val second = sender.seal(MediaRecord.audio(CallCodecs.OPUS, emptyList()))

        assertOpenFails(MediaCipher(key), second) // first was dropped
        val replay = MediaCipher(key)
        replay.open(first)
        assertOpenFails(replay, first) // repeated
    }

    @Test
    fun `a tampered record fails`() {
        val key = ByteArray(32) { 3 }
        val sealed = MediaCipher(key).seal(MediaRecord.audio(CallCodecs.OPUS, listOf(byteArrayOf(9))))
        sealed[10] = (sealed[10].toInt() xor 1).toByte()
        assertOpenFails(MediaCipher(key), sealed)
    }

    @Test
    fun `the other direction's key does not open a record`() {
        val keys = CallKeys.derive(offer, answer, callId, alice, bob)
        val sealed = MediaCipher(keys.callerToCallee).seal(MediaRecord.audio(CallCodecs.OPUS, emptyList()))
        assertOpenFails(MediaCipher(keys.calleeToCaller), sealed)
    }

    private fun assertOpenFails(cipher: MediaCipher, sealed: ByteArray) {
        try {
            cipher.open(sealed)
            fail("Expected the record to be refused")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun `every audio record of a codec is the same size, whatever it carries`() {
        for (codec in CallCodecs.ALL) {
            val sizes = setOf(
                MediaRecord.audio(codec, emptyList()).size,
                MediaRecord.audio(codec, listOf(ByteArray(1))).size,
                MediaRecord.audio(codec, List(4) { ByteArray(40) }).size,
                MediaRecord.audio(codec, List(9) { ByteArray(200) }).size,
                MediaRecord.hangup(codec).size,
            )
            assertEquals(setOf(MediaRecord.sizeFor(codec)), sizes)
        }
    }

    @Test
    fun `packets round trip through a record`() {
        val packets = listOf(ByteArray(40) { 1 }, ByteArray(41) { 2 }, ByteArray(39) { 3 })
        val decoded = MediaRecord.decode(MediaRecord.audio(CallCodecs.OPUS, packets))
        assertEquals(MediaRecord.TYPE_AUDIO, decoded.type)
        assertEquals(CallCodecs.OPUS, decoded.codec)
        assertEquals(3, decoded.packets.size)
        packets.indices.forEach { assertArrayEquals(packets[it], decoded.packets[it]) }
    }

    @Test
    fun `three PCMU packets fit a record`() {
        val packets = List(3) { ByteArray(160) { n -> n.toByte() } }
        val decoded = MediaRecord.decode(MediaRecord.audio(CallCodecs.PCMU, packets))
        assertEquals(3, decoded.packets.size)
    }

    @Test
    fun `what does not fit is left out, never overflowing`() {
        val decoded = MediaRecord.decode(MediaRecord.audio(CallCodecs.OPUS, List(4) { ByteArray(150) }))
        assertEquals(2, decoded.packets.size)
        assertEquals(MediaRecord.MAX_PACKETS, MediaRecord.decode(MediaRecord.audio(CallCodecs.OPUS, List(10) { ByteArray(10) })).packets.size)
    }

    @Test
    fun `a hangup decodes as one`() {
        assertEquals(MediaRecord.TYPE_HANGUP, MediaRecord.decode(MediaRecord.hangup(CallCodecs.OPUS)).type)
    }

    @Test
    fun `malformed records are refused cleanly`() {
        val good = MediaRecord.audio(CallCodecs.OPUS, listOf(ByteArray(10)))
        val cases = listOf(
            ByteArray(0),
            ByteArray(2),
            good.copyOf(good.size - 1),
            good.copyOf().also { it[0] = 9 },
            good.copyOf().also { it[1] = 9 },
            good.copyOf().also { it[2] = 5 },
            good.copyOf().also { it[3] = 0x7f; it[4] = 0x7f },
            good.copyOf().also { it[3] = 0; it[4] = 0 },
            MediaRecord.audio(CallCodecs.OPUS, emptyList()).also { it[1] = CallCodecs.PCMU.toByte() },
        )
        for (bytes in cases) {
            try {
                MediaRecord.decode(bytes)
                fail("Expected a malformed record to be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `sealed sizes cover every codec`() {
        assertEquals(setOf(320 + 16, 656 + 16), MediaRecord.SEALED_SIZES)
    }

    @Test
    fun `g711 round trips within its quantisation error`() {
        for (sample in -32768..32767 step 7) {
            val back = G711.decode(byteArrayOf(G711.encodeOne(sample)))[0].toInt()
            val clipped = sample.coerceIn(-32635, 32635)
            val tolerance = maxOf(16, kotlin.math.abs(clipped) / 16)
            assertTrue("$sample came back as $back", kotlin.math.abs(back - clipped) <= tolerance)
        }
    }

    @Test
    fun `g711 silence stays near silence`() {
        val decoded = G711.decode(G711.encode(ShortArray(160)))
        assertTrue(decoded.all { kotlin.math.abs(it.toInt()) <= 8 })
    }

    @Test
    fun `g711 encodes the standard reference values`() {
        // ITU-T G.711: zero encodes to 0xFF, full-scale positive to 0x80, full-scale negative to 0x00.
        assertEquals(0xff.toByte(), G711.encodeOne(0))
        assertEquals(0x80.toByte(), G711.encodeOne(32767))
        assertEquals(0x00.toByte(), G711.encodeOne(-32768))
    }
}
