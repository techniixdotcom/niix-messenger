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

class RelayConnectionHandler(
    private val store: RelayStore,
    private val routingTable: RoutingTable,
    private val rateLimiter: RelayRateLimiter,
    private val crypto: CryptoEngine,
    private val hostingEnabled: () -> Boolean,
    private val selfOnionProvider: () -> String?,
) {

    suspend fun handleFrame(type: Int, input: DataInputStream, output: OutputStream) {
        if (!hostingEnabled()) return
        when (type) {
            RelayProtocol.FRAME_RELAY_STORE -> handleStore(input, output)
            RelayProtocol.FRAME_RELAY_FETCH -> handleFetch(input, output)
            RelayProtocol.FRAME_RELAY_DELETE_RECEIPT -> handleDeleteReceipt(input)
            RelayProtocol.FRAME_RELAY_FIND_NODE -> handleFindNode(input, output)
            RelayProtocol.FRAME_RELAY_ANNOUNCE -> handleAnnounce(input)

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

        val envelopeLen = input.readInt()
        if (envelopeLen !in 0..MAX_RELAY_ENVELOPE_BYTES.toInt()) {
            reject(output, RelayRejectReason.TOO_LARGE)
            return
        }
        val envelope = ByteArray(envelopeLen).also { input.readFully(it) }

        val now = System.currentTimeMillis()

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

    }

    private fun handleFindNode(input: DataInputStream, output: OutputStream) {
        val targetKey = readBlock(input, RelayProtocol.NODE_ID_BYTES)
        val requesterOnion = readOnion(input)
        val requesterNodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)
        val requesterIdentityKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val requesterProof = readBlock(input, MAX_SIGNATURE_BYTES)

        if (
            requesterNodeId.size == RelayProtocol.NODE_ID_BYTES &&
            OnionAddress.parseOrNull(requesterOnion) != null &&
            RelaySigning.verifyNodeIdentity(crypto, requesterIdentityKey, requesterNodeId, requesterOnion, requesterProof)
        ) {
            routingTable.insertOrUpdate(NodeInfo(requesterNodeId, requesterOnion))
        }

        val closest = if (targetKey.size == RelayProtocol.NODE_ID_BYTES) {
            routingTable.closest(targetKey, RelayProtocol.KADEMLIA_K)
        } else {
            emptyList()
        }
        val ownNodeId = RelayProtocol.nodeId(crypto.localIdentityKey())
        val out = DataOutputStream(output)
        out.writeByte(RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE)

        val selfOnion = selfOnionProvider()
        if (selfOnion != null) {
            writeBlock(out, ownNodeId)
            writeBlock(out, crypto.localIdentityKey())
            writeBlock(out, RelaySigning.signNodeIdentity(crypto, ownNodeId, selfOnion))
        } else {
            writeBlock(out, ByteArray(0))
            writeBlock(out, ByteArray(0))
            writeBlock(out, ByteArray(0))
        }
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
        val identityKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
        val proof = readBlock(input, MAX_SIGNATURE_BYTES)
        if (
            nodeId.size == RelayProtocol.NODE_ID_BYTES &&
            OnionAddress.parseOrNull(onion) != null &&
            RelaySigning.verifyNodeIdentity(crypto, identityKey, nodeId, onion, proof)
        ) {
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
