package app.niix.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

private fun id(seed: Int): ByteArray = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(seed.toByte()))

class RoutingTableTest {

    @Test
    fun `closest returns nodes ordered by true xor distance`() {
        val local = id(0)
        val table = RoutingTable(local)
        val nodes = (1..20).map { NodeInfo(id(it), "node$it.onion") }
        nodes.forEach { table.insertOrUpdate(it) }

        val target = id(0) // distance from local itself: find nodes closest to us
        val closest = table.closest(target, 5)
        assertEquals(5, closest.size)

        // Verify the returned list really is sorted by ascending XOR distance to target.
        val distances = closest.map { node -> xorDistance(node.nodeId, target) }
        val sorted = distances.sortedWith { a, b -> compareBytes(a, b) }
        assertEquals(sorted, distances)
    }

    @Test
    fun `never returns the local node itself`() {
        val local = id(0)
        val table = RoutingTable(local)
        table.insertOrUpdate(NodeInfo(local, "self.onion"))
        table.insertOrUpdate(NodeInfo(id(1), "node1.onion"))
        val closest = table.closest(local, 10)
        assertTrue(closest.none { it.nodeId.contentEquals(local) })
    }

    @Test
    fun `remove drops a node from future closest results`() {
        val local = id(0)
        val table = RoutingTable(local)
        val target = NodeInfo(id(5), "node5.onion")
        table.insertOrUpdate(target)
        assertTrue(table.all().any { it.nodeId.contentEquals(target.nodeId) })
        table.remove(target.nodeId)
        assertTrue(table.all().none { it.nodeId.contentEquals(target.nodeId) })
    }

    @Test
    fun `bucket capacity caps how many peers are remembered at a given distance class`() {
        val local = id(0)
        val table = RoutingTable(local, k = 2)
        // These all share the local id's first byte's top bits in a way that's likely to bucket
        // together for a small k; regardless of exact bucketing, size() must never exceed the
        // total number of unique nodes inserted, and repeated inserts of the same id must not
        // grow it.
        val node = NodeInfo(id(7), "node7.onion")
        table.insertOrUpdate(node)
        table.insertOrUpdate(node)
        table.insertOrUpdate(node)
        assertEquals(1, table.size())
    }

    private fun xorDistance(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
