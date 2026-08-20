package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
import app.niix.core.model.OnionAddress
import app.niix.core.relay.RelayProtocol.MAX_ENVELOPE_HASH_BYTES
import app.niix.core.relay.RelayProtocol.MAX_IDENTITY_KEY_BYTES
import app.niix.core.relay.RelayProtocol.MAX_RELAY_ENVELOPE_BYTES
import app.niix.core.relay.RelayProtocol.MAX_SIGNATURE_BYTES
import app.niix.core.relay.RelayProtocol.readBlock
import app.niix.core.relay.RelayProtocol.readOnion
import app.niix.core.relay.RelayProtocol.sha256
import app.niix.core.relay.RelayProtocol.toBigEndianBytes
import app.niix.core.relay.RelayProtocol.writeBlock
import app.niix.core.relay.RelayProtocol.writeOnion
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Owns every FRAME_RELAY_* branch on the receiving end of a connection -- i.e. this device's
 * behavior *as a relay node* for other people's traffic. Deliberately a separate class the
 * transport dispatches to (see `ConversationManager.handleConnection`) rather than new cases
 * mixed into the core messaging path, so relay code has zero effect on any user who never turns
 * on "Enable relay mode" (build spec item 11.4): every method here bails out immediately unless
 * [hostingEnabled] is true, which is exactly what makes a relay-disabled node behave, from a
 * remote peer's point of view, as if it simply weren't listening for these frame types at all.
 *
 * This class only handles the *hosting* (storing-for-strangers) side. A device that never
 * enables relay mode can still *use* the relay network as a sender/recipient -- that's
 * [RelayManager]'s client-side responsibility, which has no dependency on this class.
 */
class RelayConnectionHandler(
    private val store: RelayStore,
    private val routingTable: RoutingTable,
    private val rateLimiter: RelayRateLimiter,
    private val crypto: CryptoEngine,
    private val hostingEnabled: () -> Boolean,
) {

    /** [input] has already had its leading frame-type byte consumed by the caller; [type] is
     * that byte. Relay frames are self-contained (see [RelayProtocol]'s class doc) so nothing
     * else needs to be peeled off before dispatching here. */
    suspend fun handleFrame(type: Int, input: DataInputStream, output: OutputStream) {
        if (!hostingEnabled()) return
        when (type) {
            RelayProtocol.FRAME_RELAY_STORE -> handleStore(input, output)
            RelayProtocol.FRAME_RELAY_FETCH -> handleFetch(input, output)
            RelayProtocol.FRAME_RELAY_DELETE_RECEIPT -> handleDeleteReceipt(input)
            RelayProtocol.FRAME_RELAY_FIND_NODE -> handleFindNode(input, output)
            RelayProtocol.FRAME_RELAY_ANNOUNCE -> handleAnnounce(input)
            // FETCH_RESPONSE / REJECT / FIND_NODE_RESPONSE are response-only types, read
            // directly by RelayClient off the same connection it made the request on -- they
            // never arrive as a fresh inbound "connection" the way a request type does, so
            // there's nothing to do here for them (same pattern as FRAME_BUNDLE_RESPONSE in
            // ConversationManager.handleConnection).
            else -> Unit
        }
    }

    private fun handleStore(input: DataInputStream, output: OutputStream) {
        val recipientIdKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val senderIdKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val grantIssuedAt = input.readLong()
        val grantExpiresAt = input.readLong()
        val grantSig = readBlock(input, MAX_SIGNATURE_BYTES)
        val requestSig = readBlock(input, MAX_SIGNATURE_BYTES)
        val ttlMillis = input.readLong()

        // Read the envelope's declared length ourselves (rather than via the generic
        // readBlock(maxLen) helper, which would just throw on an oversized value) so an
        // over-cap envelope gets the spec's explicit reason=1 FRAME_RELAY_REJECT reply instead
        // of the connection merely dropping.
        val envelopeLen = input.readInt()
        if (envelopeLen !in 0..MAX_RELAY_ENVELOPE_BYTES.toInt()) {
            reject(output, RelayRejectReason.TOO_LARGE)
            return
        }
        val envelope = ByteArray(envelopeLen).also { input.readFully(it) }

        val now = System.currentTimeMillis()

        // Validation order matches build spec item 11.2 exactly: reject on first failure.
        if (grantExpiresAt <= now) {
            reject(output, RelayRejectReason.GRANT_EXPIRED)
            return
        }
        val grantSigValid = RelaySigning.verifyGrantSignature(
            crypto, issuerIdentityKey = recipientIdKey, granteeIdentityKey = senderIdKey,
            issuedAt = grantIssuedAt, expiresAt = grantExpiresAt, signature = grantSig,
        )
        if (!grantSigValid) {
            reject(output, RelayRejectReason.BAD_GRANT_SIGNATURE)
            return
        }
        val requestSigValid = RelaySigning.verifyStoreRequestSignature(
            crypto, senderIdKey = senderIdKey, recipientIdKey = recipientIdKey,
            envelope = envelope, ttlMillis = ttlMillis, signature = requestSig,
        )
        if (!requestSigValid) {
            reject(output, RelayRejectReason.BAD_REQUEST_SIGNATURE)
            return
        }
        val recipientHash = sha256(recipientIdKey)
        if (store.countFor(recipientHash) >= RelayProtocol.MAX_RELAY_ENVELOPES_PER_HASH) {
            reject(output, RelayRejectReason.RECIPIENT_QUOTA_FULL)
            return
        }
        if (!rateLimiter.allow(senderIdKey, now)) {
            reject(output, RelayRejectReason.RATE_LIMITED)
            return
        }

        val failure = store.store(recipientHash, senderIdKey, envelope, ttlMillis, now)
        if (failure != null) {
            reject(output, failure)
            return
        }
        DataOutputStream(output).apply {
            writeByte(RelayProtocol.FRAME_ACK)
            flush()
        }
    }

    private fun handleFetch(input: DataInputStream, output: OutputStream) {
        val recipientIdKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val timestamp = input.readLong()
        val proofSig = readBlock(input, MAX_SIGNATURE_BYTES)

        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) > RelayProtocol.RELAY_FETCH_PROOF_WINDOW_MS) {
            reject(output, RelayRejectReason.PROOF_INVALID)
            return
        }
        val proofValid = RelaySigning.verifyKeyPossessionProof(
            crypto, claimedIdentityKey = recipientIdKey, subject = timestamp.toBigEndianBytes(), signature = proofSig,
        )
        if (!proofValid) {
            reject(output, RelayRejectReason.PROOF_INVALID)
            return
        }

        val results = store.fetch(sha256(recipientIdKey), now)
        val out = DataOutputStream(output)
        out.writeByte(RelayProtocol.FRAME_RELAY_FETCH_RESPONSE)
        out.writeInt(results.size)
        results.forEach { entry ->
            writeBlock(out, entry.senderIdKey)
            writeBlock(out, entry.envelopeHash)
            writeBlock(out, entry.envelope)
        }
        out.flush()
    }

    private fun handleDeleteReceipt(input: DataInputStream) {
        val recipientIdKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val envelopeHash = readBlock(input, MAX_ENVELOPE_HASH_BYTES)
        val proofSig = readBlock(input, MAX_SIGNATURE_BYTES)
        val proofValid = RelaySigning.verifyKeyPossessionProof(
            crypto, claimedIdentityKey = recipientIdKey, subject = envelopeHash, signature = proofSig,
        )
        if (proofValid) {
            store.deleteReceipt(sha256(recipientIdKey), envelopeHash)
        }
        // No response either way -- the client treats this as fire-and-forget (see RelayClient).
    }

    private fun handleFindNode(input: DataInputStream, output: OutputStream) {
        val targetKey = readBlock(input, RelayProtocol.NODE_ID_BYTES)
        val requesterOnion = readOnion(input)
        val requesterNodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)

        if (requesterNodeId.size == RelayProtocol.NODE_ID_BYTES && OnionAddress.parseOrNull(requesterOnion) != null) {
            routingTable.insertOrUpdate(NodeInfo(requesterNodeId, requesterOnion))
        }

        val closest = if (targetKey.size == RelayProtocol.NODE_ID_BYTES) {
            routingTable.closest(targetKey, RelayProtocol.KADEMLIA_K)
        } else {
            emptyList()
        }
        val out = DataOutputStream(output)
        out.writeByte(RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE)
        out.writeInt(closest.size)
        closest.forEach { node ->
            writeBlock(out, node.nodeId)
            writeOnion(out, node.onion)
        }
        out.flush()
    }

    private fun handleAnnounce(input: DataInputStream) {
        val nodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)
        val onion = readOnion(input)
        if (nodeId.size == RelayProtocol.NODE_ID_BYTES && OnionAddress.parseOrNull(onion) != null) {
            routingTable.insertOrUpdate(NodeInfo(nodeId, onion))
        }
    }

    private fun reject(output: OutputStream, reason: Int) {
        runCatching {
            DataOutputStream(output).apply {
                writeByte(RelayProtocol.FRAME_RELAY_REJECT)
                writeByte(reason)
                flush()
            }
        }
    }
}
