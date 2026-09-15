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

        val target = id(0)
        val closest = table.closest(target, 5)
        assertEquals(5, closest.size)

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

        val node = NodeInfo(id(7), "node7.onion")
        table.insertOrUpdate(node)
        table.insertOrUpdate(node)
        table.insertOrUpdate(node)
        assertEquals(1, table.size())
    }

    @Test
    fun `re-announcing a known node in a full bucket refreshes rather than evicting it`() {
 // The previous implementation removed the existing entry and then bailed out because the
 // bucket was full, so proving you were still alive got you dropped.
        val local = id(0)
        val table = RoutingTable(local, k = 1)
        val node = NodeInfo(id(7), "node7.onion")

        table.insertOrUpdate(node, nowMillis = 1_000L)
        table.insertOrUpdate(node, nowMillis = 2_000L)

        assertEquals(1, table.size())
        assertEquals(listOf(node), table.closest(id(7), 5))
    }

    @Test
    fun `a full bucket keeps recently seen nodes rather than admitting a newcomer`() {
        val local = id(0)
        val table = RoutingTable(local, k = 1)
 // Seeds 1 and 14 hash into the same bucket relative to id(0). Capacity is per-bucket, so
 // two arbitrary ids would usually land in different buckets and both be admitted, the
 // earlier version of this test picked such a pair and failed for that reason rather than
 // because the code was wrong.
        val incumbent = NodeInfo(id(1), "incumbent.onion")
        val newcomer = NodeInfo(id(14), "newcomer.onion")

        table.insertOrUpdate(incumbent, nowMillis = 1_000L)
 // Shortly afterwards: the incumbent is not stale, so it is not displaced.
        table.insertOrUpdate(newcomer, nowMillis = 2_000L)

        assertEquals(1, table.size())
        assertEquals(listOf(incumbent), table.closest(id(1), 5))
    }

    @Test
    fun `a full bucket admits a newcomer once the incumbent is stale`() {
 // The counterpart to the test above: same two ids, same bucket, but enough time passed.
 // Together they also prove the pair genuinely shares a bucket, if it did not, both
 // would be admitted here and the size assertion would fail.
        val local = id(0)
        val table = RoutingTable(local, k = 1)
        val incumbent = NodeInfo(id(1), "incumbent.onion")
        val newcomer = NodeInfo(id(14), "newcomer.onion")

        table.insertOrUpdate(incumbent, nowMillis = 1_000L)
        table.insertOrUpdate(newcomer, nowMillis = 1_000L + 2 * 60 * 60 * 1000L)

        assertEquals(1, table.size())
        assertEquals(listOf(newcomer), table.closest(id(14), 5))
    }

    @Test
    fun `pruneStale drops nodes not heard from in a long time`() {
        val local = id(0)
        val table = RoutingTable(local, k = 4)
        table.insertOrUpdate(NodeInfo(id(7), "old.onion"), nowMillis = 0L)
        table.insertOrUpdate(NodeInfo(id(6), "fresh.onion"), nowMillis = 100_000_000L)

        val removed = table.pruneStale(nowMillis = 100_000_000L)

        assertEquals(1, removed)
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
