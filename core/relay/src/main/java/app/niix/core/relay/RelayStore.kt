package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex
import java.util.concurrent.ConcurrentHashMap

class RelayStore(
    @Volatile var maxTotalRelayBytes: Long = RelayProtocol.DEFAULT_MAX_TOTAL_RELAY_BYTES,
) {

    private val byRecipientHash = ConcurrentHashMap<String, MutableList<StoredEnvelope>>()

    @Volatile
    private var totalBytes: Long = 0

    @Synchronized
    fun store(
        recipientIdKeyHash: ByteArray,
        senderIdKey: ByteArray,
        envelope: ByteArray,
        ttlMillis: Long,
        now: Long,
    ): Int? {
        val hashHex = recipientIdKeyHash.toHex()
        val bucket = byRecipientHash.getOrPut(hashHex) { mutableListOf() }
        val envelopeHash = RelayProtocol.sha256(envelope)

        if (bucket.any { it.expiresAt > now && it.envelopeHash.contentEquals(envelopeHash) }) {
            return null
        }
        if (bucket.size >= RelayProtocol.MAX_RELAY_ENVELOPES_PER_HASH) {
            return RelayRejectReason.RECIPIENT_QUOTA_FULL
        }
        if (totalBytes + envelope.size > maxTotalRelayBytes) {

            return RelayRejectReason.RECIPIENT_QUOTA_FULL
        }
        val clampedTtl = ttlMillis.coerceIn(0, RelayProtocol.MAX_RELAY_TTL_MILLIS)
        val entry = StoredEnvelope(
            senderIdKey = senderIdKey,
            envelope = envelope,
            envelopeHash = envelopeHash,
            storedAt = now,
            expiresAt = now + clampedTtl,
        )
        bucket.add(entry)
        totalBytes += envelope.size
        return null
    }

    @Synchronized
    fun fetch(recipientIdKeyHash: ByteArray, now: Long): List<StoredEnvelope> {
        val bucket = byRecipientHash[recipientIdKeyHash.toHex()] ?: return emptyList()
        return bucket.filter { it.expiresAt > now }
    }

    @Synchronized
    fun deleteReceipt(recipientIdKeyHash: ByteArray, envelopeHash: ByteArray): Boolean {
        val hashHex = recipientIdKeyHash.toHex()
        val bucket = byRecipientHash[hashHex] ?: return false
        val match = bucket.find { it.envelopeHash.contentEquals(envelopeHash) } ?: return false
        bucket.remove(match)
        totalBytes -= match.envelope.size
        if (bucket.isEmpty()) byRecipientHash.remove(hashHex)
        return true
    }

    @Synchronized
    fun purgeExpired(now: Long): Int {
        var purged = 0
        val it = byRecipientHash.entries.iterator()
        while (it.hasNext()) {
            val bucket = it.next().value
            val before = bucket.size
            bucket.removeAll { entry ->
                if (entry.expiresAt <= now) {
                    totalBytes -= entry.envelope.size
                    true
                } else {
                    false
                }
            }
            purged += before - bucket.size
            if (bucket.isEmpty()) it.remove()
        }
        return purged
    }

    @Synchronized
    fun currentTotalBytes(): Long = totalBytes

    @Synchronized
    fun countFor(recipientIdKeyHash: ByteArray): Int =
        byRecipientHash[recipientIdKeyHash.toHex()]?.size ?: 0

    @Synchronized
    fun clear() {
        byRecipientHash.clear()
        totalBytes = 0
    }
}
