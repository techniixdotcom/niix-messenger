package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.NODE_ID_BYTES
import app.niix.core.relay.RelayProtocol.xorDistanceComparator

/**
 * A standard Kademlia k-bucket routing table keyed by XOR distance from [localNodeId] -- build
 * spec item 11.3. Buckets are indexed by the position of the first bit (from the most
 * significant end) at which a peer's id differs from ours, so nearer peers (in XOR-distance
 * terms) land in higher-numbered buckets; within a bucket, capacity is simply capped at [k]
 * rather than doing full least-recently-seen eviction with liveness pings, which keeps this
 * implementation compact for the scale a contact-graph-bootstrapped overlay actually reaches.
 * [closest] itself never relies on bucket indexing being exact -- it sorts every known peer by
 * true XOR distance to the target -- so a simplified insertion policy here can't produce
 * incorrect lookup results, only (in the worst case) a slightly less diverse set of remembered
 * peers than a textbook implementation would keep.
 */
class RoutingTable(private val localNodeId: ByteArray, private val k: Int = RelayProtocol.KADEMLIA_K) {

    init {
        require(localNodeId.size == NODE_ID_BYTES) { "Node id must be $NODE_ID_BYTES bytes" }
    }

    // Bucket i holds peers whose id differs from ours first at bit position i (0 = most
    // significant bit of the id). There are 8*NODE_ID_BYTES possible bit positions.
    private val buckets: Array<MutableList<NodeInfo>> = Array(NODE_ID_BYTES * 8) { mutableListOf() }

    @Synchronized
    fun insertOrUpdate(node: NodeInfo) {
        if (node.nodeId.size != NODE_ID_BYTES) return
        if (node.nodeId.contentEquals(localNodeId)) return // never route to ourselves
        val index = bucketIndex(node.nodeId) ?: return
        val bucket = buckets[index]
        bucket.removeAll { it.nodeId.contentEquals(node.nodeId) }
        if (bucket.size >= k) {
            // Bucket full: drop the new entry rather than the existing ones. A textbook
            // implementation would ping the least-recently-seen entry first and only evict it
            // if it fails to respond; skipping that liveness check is the one place this
            // implementation deliberately trades a little churn-resistance for simplicity.
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

    /** The [count] known peers closest to [targetId] by true XOR distance, across every
     * bucket -- see the class doc for why sorting the whole known set is used instead of
     * bucket-relative traversal. */
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

    /** Position (0 = most significant bit) of the first bit at which [otherId] differs from
     * [localNodeId], or null if the ids are identical. */
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
