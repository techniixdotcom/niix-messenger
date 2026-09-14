package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.NODE_ID_BYTES
import app.niix.core.relay.RelayProtocol.xorDistanceComparator

class RoutingTable(private val localNodeId: ByteArray, private val k: Int = RelayProtocol.KADEMLIA_K) {

    init {
        require(localNodeId.size == NODE_ID_BYTES) { "Node id must be $NODE_ID_BYTES bytes" }
    }

    /**
     * A node plus when it was last heard from.
     *
     * Freshness is tracked here rather than on NodeInfo because NodeInfo's equality is its
     * identity, node id and onion, and folding a timestamp into that would make two sightings
     * of the same node compare as different nodes.
     */
    private class Entry(val node: NodeInfo, var lastSeenMillis: Long)

    private val buckets: Array<MutableList<Entry>> = Array(NODE_ID_BYTES * 8) { mutableListOf() }

    @Synchronized
    fun insertOrUpdate(node: NodeInfo, nowMillis: Long = System.currentTimeMillis()) {
        if (node.nodeId.size != NODE_ID_BYTES) return
        if (node.nodeId.contentEquals(localNodeId)) return
        val index = bucketIndex(node.nodeId) ?: return
        val bucket = buckets[index]

 // Refresh in place when the node is already known.
 //
 // The previous code removed the existing entry and then returned without re-adding if
 // the bucket was full, so a node already in a full bucket was evicted by the very act
 // of proving it was still alive. That is backwards: a node that keeps announcing is the
 // best evidence a bucket has of who is actually reachable.
        val existing = bucket.firstOrNull { it.node.nodeId.contentEquals(node.nodeId) }
        if (existing != null) {
            existing.lastSeenMillis = nowMillis
            return
        }

        if (bucket.size >= k) {
 // Full: replace the stalest entry, but only if it is stale enough to be doubted.
 // Evicting a recently-seen node for an unproven newcomer would let a stream of new
 // announcements flush out the nodes known to work.
            val stalest = bucket.minByOrNull { it.lastSeenMillis } ?: return
            if (nowMillis - stalest.lastSeenMillis < STALE_AFTER_MILLIS) return
            bucket.remove(stalest)
        }
        bucket.add(Entry(node, nowMillis))
    }

    @Synchronized
    fun remove(nodeId: ByteArray) {
        if (nodeId.size != NODE_ID_BYTES) return
        val index = bucketIndex(nodeId) ?: return
        buckets[index].removeAll { it.node.nodeId.contentEquals(nodeId) }
    }

    @Synchronized
    fun closest(targetId: ByteArray, count: Int): List<NodeInfo> =
        buckets.asSequence().flatten().map { it.node }.distinctBy { it.nodeId.toList() }
            .sortedWith(xorDistanceComparator(targetId))
            .take(count)
            .toList()

    @Synchronized
    fun all(): List<NodeInfo> = buckets.flatMap { bucket -> bucket.map { it.node } }

    @Synchronized
    fun size(): Int = buckets.sumOf { it.size }

    /**
     * Drops nodes not heard from in a long time.
     *
     * Without this a bucket that filled up early keeps entries indefinitely, so a table can end
     * up full of nodes that stopped existing months ago while refusing the ones still running.
     * Called opportunistically rather than on a timer, an app that is not routing anything has
     * no reason to be doing housekeeping.
     */
    @Synchronized
    fun pruneStale(nowMillis: Long = System.currentTimeMillis()): Int {
        var removed = 0
        for (bucket in buckets) {
            val before = bucket.size
            bucket.removeAll { nowMillis - it.lastSeenMillis > DROP_AFTER_MILLIS }
            removed += before - bucket.size
        }
        return removed
    }

    private companion object {
        /** How long before an entry is considered stale enough to be replaced by a newcomer.
         * Long enough that a node briefly offline is not displaced by the first stranger. */
        const val STALE_AFTER_MILLIS = 60L * 60 * 1000

        /** How long before an entry is dropped outright. Well beyond any plausible restart. */
        const val DROP_AFTER_MILLIS = 24L * 60 * 60 * 1000
    }

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
