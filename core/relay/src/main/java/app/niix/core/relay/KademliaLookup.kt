package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex
import app.niix.core.relay.RelayProtocol.xorDistanceComparator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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

                    routingTable.remove(node.nodeId)
                    continue
                }

                if (result.responder != null) {
                    routingTable.insertOrUpdate(result.responder)
                }
                for (candidate in result.candidates) {
                    if (candidate.nodeId.contentEquals(localNodeId)) continue
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
