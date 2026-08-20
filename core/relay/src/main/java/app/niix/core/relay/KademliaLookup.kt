package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex
import app.niix.core.relay.RelayProtocol.xorDistanceComparator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The standard iterative Kademlia lookup (build spec item 11.3), used identically for both
 * storing and fetching: given [targetKey], queries the [RelayProtocol.KADEMLIA_ALPHA] closest
 * not-yet-queried known nodes in parallel via FIND_NODE, folds any newly-discovered peers into
 * the candidate shortlist and [routingTable], and repeats until a round yields no closer node --
 * i.e. it has converged on the true [RelayProtocol.LOOKUP_K] closest nodes in the whole
 * currently-reachable overlay, not merely this device's own contacts.
 */
class KademliaLookup(
    private val routingTable: RoutingTable,
    private val client: RelayClient,
    private val localNodeId: ByteArray,
    private val selfOnionProvider: () -> String?,
) {

    suspend fun lookup(
        targetKey: ByteArray,
        k: Int = RelayProtocol.LOOKUP_K,
        alpha: Int = RelayProtocol.KADEMLIA_ALPHA,
    ): List<NodeInfo> {
        val selfOnion = selfOnionProvider()
            // Not yet reachable ourselves (e.g. onion service still publishing) -- fall back to
            // whatever the routing table already knows rather than querying the network, since
            // we can't usefully advertise ourselves as the requester yet.
            ?: return routingTable.closest(targetKey, k)

        val comparator = xorDistanceComparator(targetKey)
        val queried = HashSet<String>()
        var shortlist = routingTable.closest(targetKey, maxOf(k, alpha)).toMutableList()

        var progressed = true
        while (progressed) {
            progressed = false
            val batch = shortlist
                .filter { it.nodeId.toHex() !in queried }
                .sortedWith(comparator)
                .take(alpha)
            if (batch.isEmpty()) break
            // Mark these as queried before dispatching the parallel batch below, rather than
            // from inside each async block: mutating a plain HashSet concurrently from multiple
            // coroutines running on a multi-threaded dispatcher (as this does in practice, since
            // callers run under Dispatchers.IO) is not thread-safe.
            batch.forEach { queried += it.nodeId.toHex() }

            val responses = coroutineScope {
                batch.map { node ->
                    async {
                        node to client.findNode(node.onion, targetKey, selfOnion, localNodeId)
                    }
                }.map { it.await() }
            }

            for ((node, result) in responses) {
                if (result == null) {
                    // Unreachable this round -- drop it so a dead node doesn't keep being
                    // preferred by future lookups.
                    routingTable.remove(node.nodeId)
                    continue
                }
                for (candidate in result) {
                    if (candidate.nodeId.contentEquals(localNodeId)) continue
                    routingTable.insertOrUpdate(candidate)
                    if (shortlist.none { it.nodeId.contentEquals(candidate.nodeId) }) {
                        shortlist.add(candidate)
                        progressed = true
                    }
                }
            }

            shortlist = shortlist.sortedWith(comparator).take(maxOf(k, alpha * 3)).toMutableList()
        }

        return shortlist.sortedWith(comparator).take(k)
    }
}
