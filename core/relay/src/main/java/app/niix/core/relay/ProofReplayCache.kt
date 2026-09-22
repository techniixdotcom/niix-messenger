package app.niix.core.relay

/**
 * Tracks proofs we have already accepted so the same one cannot be used twice.
 *
 * Fetch and delete proofs are just signatures over a timestamp or a hash, valid for a window.
 * Anyone who sees one can replay it inside that window and the signature still checks out.
 *
 * Proper fix is challenge-response (relay picks a nonce, client signs it) but that needs both
 * ends changed. This is the cheap version: accept each proof once.
 *
 * Entries expire with the window. Nothing older can be accepted anyway, and it keeps the map
 * from growing forever on a busy relay.
 */
internal class ProofReplayCache(private val windowMillis: Long) {

    private val seen = HashMap<String, Long>()

    /**
     * Records a proof and reports whether it is new.
     *
     * Returns false if this exact proof has been honoured before, in which case the caller must
     * refuse it. Pruning happens here rather than on a timer so the cache cannot grow unbounded
     * on a node that is never otherwise swept.
     */
    @Synchronized
    fun offer(proof: ByteArray, nowMillis: Long): Boolean {
        prune(nowMillis)
        val key = proof.joinToString("") { "%02x".format(it) }
        if (seen.containsKey(key)) return false
        seen[key] = nowMillis
        return true
    }

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - windowMillis
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    @Synchronized
    fun size(): Int = seen.size
}
