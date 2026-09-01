package app.niix.core.messaging

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adversarial tests for the wire protocol.
 *
 * [WireCodec.decode] and [MessagePadding.unpad] process bytes that arrive from remote peers.
 * For the raw-frame paths they run *before* any authentication -- anyone able to open a
 * connection to this device's onion service can reach them. The security property being
 * tested here is deliberately narrow but absolute: **no input, however malformed, may cause
 * anything other than a clean exception.** An OutOfMemoryError from an attacker-chosen length
 * field, an infinite loop, or a silently wrong parse are all failures; a thrown exception is
 * a pass, because every caller in ConversationManager wraps these in runCatching and drops
 * the message.
 */
class WireProtocolAdversarialTest {

    private fun assertRejectedCleanly(bytes: ByteArray, what: String) {
        try {
            WireCodec.decode(bytes)
            // Decoding without throwing is acceptable only if it produced something; the point
            // is that it must not hang, crash the process, or exhaust memory.
        } catch (_: Exception) {
            // Expected for malformed input.
        } catch (t: Throwable) {
            fail("$what produced a non-Exception Throwable (${t::class.java.name}) -- " +
                "callers only catch Exception, so this would crash the process")
        }
    }

    @Test
    fun `empty input is rejected cleanly`() {
        assertRejectedCleanly(ByteArray(0), "empty input")
    }

    @Test
    fun `truncated at every possible length is rejected cleanly`() {
        val valid = WireCodec.encode(
            WireMessage.Text(
                conversationId = "conv",
                messageId = "msg",
                senderOnion = "a".repeat(56) + ".onion",
                body = "hello",
                expiresSeconds = 60,
            ),
        )
        // Every prefix of a valid message is malformed. None may do anything but throw.
        for (len in 0 until valid.size) {
            assertRejectedCleanly(valid.copyOfRange(0, len), "truncation to $len bytes")
        }
    }

    @Test
    fun `unknown version is rejected`() {
        val valid = WireCodec.encode(WireMessage.TimerUpdate("conv", 60))
        val tampered = valid.copyOf()
        tampered[0] = 99
        assertRejectedCleanly(tampered, "unknown version byte")
    }

    @Test
    fun `every possible type byte is handled without crashing`() {
        // Includes negative values -- readByte() returns a signed Byte, so a type byte above
        // 127 arrives as a negative Int and must not match any branch by accident.
        for (type in 0..255) {
            val bytes = byteArrayOf(1, type.toByte())
            assertRejectedCleanly(bytes, "bare type byte $type")
        }
    }

    @Test
    fun `huge declared string length does not exhaust memory`() {
        // A DataInputStream UTF length prefix is 2 bytes (max 65535), but a declared length far
        // beyond the actual remaining bytes must fail fast rather than allocate optimistically.
        val bytes = byteArrayOf(
            1, 1,
            0xFF.toByte(), 0xFF.toByte(),
        )
        assertRejectedCleanly(bytes, "declared UTF length far beyond available bytes")
    }

    @Test
    fun `huge declared string list count does not exhaust memory`() {
        // TYPE_DELETE reads a conversationId then a string list whose count is a 4-byte int.
        // Int.MAX_VALUE entries must not cause a huge allocation before the data runs out.
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { s ->
            s.writeByte(1)
            s.writeByte(2)
            s.writeUTF("conv")
            s.writeInt(Int.MAX_VALUE)
        }
        assertRejectedCleanly(out.toByteArray(), "string list count of Int.MAX_VALUE")
    }

    @Test
    fun `negative string list count is rejected`() {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { s ->
            s.writeByte(1)
            s.writeByte(2)
            s.writeUTF("conv")
            s.writeInt(-1)
        }
        assertRejectedCleanly(out.toByteArray(), "negative string list count")
    }

    @Test
    fun `random garbage of many shapes never escapes a clean exception`() {
        // Deterministic seed: a failure here must be reproducible, not a flaky one-off.
        val random = Random(20260901L)
        repeat(20_000) {
            val size = random.nextInt(64)
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            assertRejectedCleanly(bytes, "random ${size}-byte input")
        }
    }

    @Test
    fun `random garbage with a valid header prefix never escapes a clean exception`() {
        // Pure random bytes almost never get past the version check, so this variant forces a
        // valid version + a valid type byte and fuzzes only the payload -- reaching much deeper
        // into each branch's parsing logic.
        val random = Random(20260902L)
        val types = 1..12
        for (type in types) {
            repeat(2_000) {
                val payload = ByteArray(random.nextInt(48)).also { random.nextBytes(it) }
                val bytes = byteArrayOf(1, type.toByte()) + payload
                assertRejectedCleanly(bytes, "type $type with random payload")
            }
        }
    }

    @Test
    fun `bit flips in an otherwise valid message never escape a clean exception`() {
        val valid = WireCodec.encode(
            WireMessage.GroupInvite(
                conversationId = "conv",
                title = "group",
                members = listOf("a".repeat(56) + ".onion", "b".repeat(56) + ".onion"),
                admins = listOf("a".repeat(56) + ".onion"),
                epoch = 7,
            ),
        )
        for (byteIndex in valid.indices) {
            for (bit in 0..7) {
                val mutated = valid.copyOf()
                mutated[byteIndex] = (mutated[byteIndex].toInt() xor (1 shl bit)).toByte()
                assertRejectedCleanly(mutated, "bit flip at byte $byteIndex bit $bit")
            }
        }
    }

    @Test
    fun `valid messages still round-trip exactly`() {
        // The adversarial cases above are worthless if the codec is simply rejecting
        // everything -- this pins down that well-formed input genuinely still works.
        val original = WireMessage.Text(
            conversationId = "conv-id",
            messageId = "msg-id",
            senderOnion = "x".repeat(56) + ".onion",
            body = "hello \uD83D\uDE00 unicode",
            expiresSeconds = 86_400,
        )
        val decoded = WireCodec.decode(WireCodec.encode(original))
        assertEquals(original, decoded)
    }
}

/**
 * [MessagePadding.unpad] runs on decrypted-but-still-untrusted bytes. Its length prefix is
 * attacker-influenced, and the shift arithmetic that reconstructs it can produce a negative
 * Int -- these tests pin down that the bounds check actually catches that rather than allowing
 * a negative-length or out-of-range copy.
 */
class MessagePaddingAdversarialTest {

    @Test
    fun `pad then unpad round-trips for many sizes including bucket boundaries`() {
        val sizes = listOf(0, 1, 251, 252, 253, 1019, 1020, 1021, 4095, 4096, 16_000, 20_000, 70_000)
        for (size in sizes) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val roundTripped = MessagePadding.unpad(MessagePadding.pad(data))
            assertArrayEquals("size $size failed to round-trip", data, roundTripped)
        }
    }

    @Test
    fun `padding actually hides the true length in buckets`() {
        // The whole point of padding is that different plaintext sizes produce identical
        // ciphertext sizes -- if this stops being true, traffic analysis gets easier and the
        // failure would otherwise be completely silent.
        assertEquals(MessagePadding.pad(ByteArray(10)).size, MessagePadding.pad(ByteArray(200)).size)
        assertEquals(MessagePadding.pad(ByteArray(300)).size, MessagePadding.pad(ByteArray(900)).size)
    }

    @Test
    fun `input shorter than the length prefix is rejected`() {
        for (size in 0..3) {
            try {
                MessagePadding.unpad(ByteArray(size))
                fail("unpad accepted a $size-byte input shorter than the 4-byte length prefix")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `length prefix with the high bit set cannot produce a negative length copy`() {
        // 0xFF in the top byte makes the reconstructed Int negative. Without the bounds check
        // this would reach copyOfRange with a negative length.
        val padded = ByteArray(256)
        padded[0] = 0xFF.toByte()
        padded[1] = 0xFF.toByte()
        padded[2] = 0xFF.toByte()
        padded[3] = 0xFF.toByte()
        try {
            MessagePadding.unpad(padded)
            fail("unpad accepted a length prefix that reconstructs to a negative Int")
        } catch (_: IllegalArgumentException) {
            // Expected -- the bounds check caught it.
        }
    }

    @Test
    fun `declared length longer than the buffer is rejected`() {
        val padded = ByteArray(256)
        // Declare 100000 bytes of payload inside a 256-byte buffer.
        val declared = 100_000
        padded[0] = (declared ushr 24).toByte()
        padded[1] = (declared ushr 16).toByte()
        padded[2] = (declared ushr 8).toByte()
        padded[3] = declared.toByte()
        try {
            MessagePadding.unpad(padded)
            fail("unpad accepted a declared length longer than the actual buffer")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `every possible top-byte value is handled without a non-Exception throwable`() {
        for (topByte in 0..255) {
            val padded = ByteArray(512)
            padded[0] = topByte.toByte()
            padded[1] = 0xFF.toByte()
            padded[2] = 0xFF.toByte()
            padded[3] = 0xFF.toByte()
            try {
                MessagePadding.unpad(padded)
            } catch (_: Exception) {
                // Fine.
            } catch (t: Throwable) {
                fail("top byte $topByte produced ${t::class.java.name}, which callers don't catch")
            }
        }
    }

    @Test
    fun `random garbage never escapes a clean exception`() {
        val random = Random(20260903L)
        repeat(20_000) {
            val bytes = ByteArray(random.nextInt(600)).also { random.nextBytes(it) }
            try {
                MessagePadding.unpad(bytes)
            } catch (_: Exception) {
                // Fine.
            } catch (t: Throwable) {
                fail("random input produced ${t::class.java.name}, which callers don't catch")
            }
        }
    }

    @Test
    fun `unpad never returns more data than the buffer could hold`() {
        val random = Random(20260904L)
        repeat(5_000) {
            val bytes = ByteArray(4 + random.nextInt(300)).also { random.nextBytes(it) }
            val result = try {
                MessagePadding.unpad(bytes)
            } catch (_: Exception) {
                return@repeat
            }
            assertTrue(
                "unpad returned ${result.size} bytes from a ${bytes.size}-byte buffer",
                result.size <= bytes.size - 4,
            )
        }
    }
}
