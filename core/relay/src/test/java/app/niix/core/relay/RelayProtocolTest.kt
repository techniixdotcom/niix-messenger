package app.niix.core.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayProtocolTest {

    @Test
    fun `writeBlock then readBlock round-trips exactly`() {
        val out = ByteArrayOutputStream()
        val data = "hello relay".toByteArray()
        RelayProtocol.writeBlock(DataOutputStream(out), data)

        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val result = RelayProtocol.readBlock(input, 1024)
        assertArrayEquals(data, result)
    }

    @Test
    fun `readBlock rejects a length exceeding the cap`() {
        val out = ByteArrayOutputStream()
        RelayProtocol.writeBlock(DataOutputStream(out), ByteArray(100))
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            RelayProtocol.readBlock(input, 10)
        }
    }

    @Test
    fun `onion round-trips through writeOnion readOnion`() {
        val onion = "a".repeat(56) + ".onion"
        val out = ByteArrayOutputStream()
        RelayProtocol.writeOnion(DataOutputStream(out), onion)
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(onion, RelayProtocol.readOnion(input))
    }

    @Test
    fun `nodeId is a deterministic sha256 of the identity key`() {
        val key = "identity-key-bytes".toByteArray()
        val a = RelayProtocol.nodeId(key)
        val b = RelayProtocol.nodeId(key)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun `xorDistanceComparator orders exact match first`() {
        val target = RelayProtocol.sha256("target".toByteArray())
        val exact = NodeInfo(target, "exact.onion")
        val far = NodeInfo(RelayProtocol.sha256("far".toByteArray()), "far.onion")
        val sorted = listOf(far, exact).sortedWith(RelayProtocol.xorDistanceComparator(target))
        assertEquals(exact, sorted.first())
    }

    @Test
    fun `concat preserves byte order across all parts`() {
        val result = RelayProtocol.concat(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
    }
}
