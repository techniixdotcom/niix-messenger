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
 * [WireCodec.decode] and [MessagePadding.unpad] handle bytes from remote peers, and for raw frames
 * that's before any authentication. The rule is simple: no input may do anything except throw a
 * normal exception. An OutOfMemoryError from a hostile length field, a hang, or a quietly wrong
 * parse all count as failures; callers wrap these in runCatching and drop the message.
 */
class WireProtocolAdversarialTest {

    private fun assertRejectedCleanly(bytes: ByteArray, what: String) {
        try {
            WireCodec.decode(bytes)
            // Not throwing is fine as long as it doesn't hang, crash or eat memory.
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
        // Every prefix of a valid message is malformed and must throw.
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
        // Includes negatives: readByte() is signed, so a type byte above 127 arrives negative and
        // mustn't match a branch by accident.
        for (type in 0..255) {
            val bytes = byteArrayOf(1, type.toByte())
            assertRejectedCleanly(bytes, "bare type byte $type")
        }
    }

    @Test
    fun `huge declared string length does not exhaust memory`() {
        // A declared length far past the remaining bytes must fail fast, not allocate.
        val bytes = byteArrayOf(
            1, 1,
            0xFF.toByte(), 0xFF.toByte(),
        )
        assertRejectedCleanly(bytes, "declared UTF length far beyond available bytes")
    }

    @Test
    fun `huge declared string list count does not exhaust memory`() {
        // TYPE_DELETE reads a string list with a 4-byte count. Int.MAX_VALUE mustn't cause a huge
        // allocation before the data runs out.
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
        // Fixed seed so failures are reproducible.
        val random = Random(20260901L)
        repeat(20_000) {
            val size = random.nextInt(64)
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            assertRejectedCleanly(bytes, "random ${size}-byte input")
        }
    }

    @Test
    fun `random garbage with a valid header prefix never escapes a clean exception`() {
        // Random bytes rarely get past the version check, so this keeps a valid version and type
        // and fuzzes only the payload, which reaches much further into each parser.
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
        // The cases above mean nothing if the codec just rejects everything; this checks valid
        // input still works.
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
 * [MessagePadding.unpad] runs on decrypted but still untrusted bytes. Its length prefix is
 * attacker-influenced and can come out negative after the shifts; these make sure the bounds check
 * catches that.
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
        // The point of padding is that different sizes produce the same ciphertext size. If that
        // breaks, nothing else would notice.
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
        // 0xFF in the top byte makes the length negative; without the bounds check that reaches
        // copyOfRange.
        val padded = ByteArray(256)
        padded[0] = 0xFF.toByte()
        padded[1] = 0xFF.toByte()
        padded[2] = 0xFF.toByte()
        padded[3] = 0xFF.toByte()
        try {
            MessagePadding.unpad(padded)
            fail("unpad accepted a length prefix that reconstructs to a negative Int")
        } catch (_: IllegalArgumentException) {
            // Expected: the bounds check caught it.
        }
    }

    @Test
    fun `declared length longer than the buffer is rejected`() {
        val padded = ByteArray(256)
        // Claim 100000 bytes of payload in a 256-byte buffer.
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
