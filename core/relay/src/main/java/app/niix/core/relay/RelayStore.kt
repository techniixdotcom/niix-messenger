package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex
import java.util.concurrent.ConcurrentHashMap

/**
 * The relay-side mailbox: `sha256(recipientIdKey) -> envelopes waiting for them`.
 *
 * In-memory only -- a plain [ConcurrentHashMap], never touches disk, no SQLCipher table, no file
 * writes. This is the deliberate difference from every other piece of persisted state in this
 * app (see `core/storage`): killing this process mid-TTL must lose everything held here, which
 * is what keeps a relay operator's exposure bounded to "whatever's currently in RAM, for at most
 * [RelayProtocol.MAX_RELAY_TTL_MILLIS]" rather than an ever-growing disk-backed store of
 * arbitrary third-party content (see item 11.9 of the build spec for why that distinction is the
 * whole point of this design).
 *
 * Thread-safe: [RelayConnectionHandler] processes each inbound connection on its own coroutine.
 */
class RelayStore(
    @Volatile var maxTotalRelayBytes: Long = RelayProtocol.DEFAULT_MAX_TOTAL_RELAY_BYTES,
) {

    private val byRecipientHash = ConcurrentHashMap<String, MutableList<StoredEnvelope>>()

    @Volatile
    private var totalBytes: Long = 0

    /**
     * Attempts to store [envelope] for the recipient identified by [recipientIdKeyHash] (hex
     * `sha256(recipientIdKey)`). Returns the [RelayRejectReason] code to send back on failure,
     * or null on success. Caller ([RelayConnectionHandler]) owns every check that doesn't
     * require looking at existing stored state (size cap, grant/request signature validity,
     * per-sender rate limit) -- this only owns the two checks that inherently need the store's
     * own state: per-recipient quota and the global byte ceiling.
     */
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
        if (bucket.size >= RelayProtocol.MAX_RELAY_ENVELOPES_PER_HASH) {
            return RelayRejectReason.RECIPIENT_QUOTA_FULL
        }
        if (totalBytes + envelope.size > maxTotalRelayBytes) {
            // Full relay just rejects until natural TTL expiry frees space -- no
            // oldest-expiring-first eviction, to avoid a starvation attack where flooding evicts
            // legitimate stored messages (build spec item 11.5).
            return RelayRejectReason.RECIPIENT_QUOTA_FULL
        }
        val clampedTtl = ttlMillis.coerceIn(0, RelayProtocol.MAX_RELAY_TTL_MILLIS)
        val entry = StoredEnvelope(
            senderIdKey = senderIdKey,
            envelope = envelope,
            envelopeHash = RelayProtocol.sha256(envelope),
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

    /** Deletes exactly the envelope matching [envelopeHash] for this recipient, immediately
     * rather than waiting for TTL expiry. Returns true if something was actually removed. */
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

    /** Purges every envelope past [now], across every recipient -- see [RelaySweeper]. Returns
     * the number of envelopes purged. */
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

    /** Wipes everything -- used when relay hosting is turned off, so nothing this device was
     * holding for strangers lingers in memory for a user who opted back out. */
    @Synchronized
    fun clear() {
        byRecipientHash.clear()
        totalBytes = 0
    }
}
