package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.NODE_ID_BYTES
import app.niix.core.relay.RelayProtocol.xorDistanceComparator

class RoutingTable(private val localNodeId: ByteArray, private val k: Int = RelayProtocol.KADEMLIA_K) {

    init {
        require(localNodeId.size == NODE_ID_BYTES) { "Node id must be $NODE_ID_BYTES bytes" }
    }

    private val buckets: Array<MutableList<NodeInfo>> = Array(NODE_ID_BYTES * 8) { mutableListOf() }

    @Synchronized
    fun insertOrUpdate(node: NodeInfo) {
        if (node.nodeId.size != NODE_ID_BYTES) return
        if (node.nodeId.contentEquals(localNodeId)) return
        val index = bucketIndex(node.nodeId) ?: return
        val bucket = buckets[index]
        bucket.removeAll { it.nodeId.contentEquals(node.nodeId) }
        if (bucket.size >= k) {

            return
        }
        bucket.add(node)
    }

    @Synchronized
    fun remove(nodeId: ByteArray) {
        if (nodeId.size != NODE_ID_BYTES) return
        val index = bucketIndex(nodeId) ?: return
        buckets[index].removeAll { it.nodeId.contentEquals(nodeId) }
    }

    @Synchronized
    fun closest(targetId: ByteArray, count: Int): List<NodeInfo> =
        buckets.asSequence().flatten().distinctBy { it.nodeId.toList() }
            .sortedWith(xorDistanceComparator(targetId))
            .take(count)
            .toList()

    @Synchronized
    fun all(): List<NodeInfo> = buckets.flatMap { it }

    @Synchronized
    fun size(): Int = buckets.sumOf { it.size }

    private fun bucketIndex(otherId: ByteArray): Int? {
        for (i in localNodeId.indices) {
            val xor = (localNodeId[i].toInt() xor otherId[i].toInt()) and 0xFF
            if (xor != 0) {
                val leadingZerosInByte = Integer.numberOfLeadingZeros(xor) - 24
                return i * 8 + leadingZerosInByte
            }
        }
        return null
    }
}
