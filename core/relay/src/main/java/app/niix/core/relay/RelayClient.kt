package app.niix.core.relay

import app.niix.core.model.OnionAddress
import app.niix.core.relay.RelayProtocol.MAX_ENVELOPE_HASH_BYTES
import app.niix.core.relay.RelayProtocol.MAX_IDENTITY_KEY_BYTES
import app.niix.core.relay.RelayProtocol.MAX_LOOKUP_RESULTS
import app.niix.core.relay.RelayProtocol.MAX_SIGNATURE_BYTES
import app.niix.core.relay.RelayProtocol.readBlock
import app.niix.core.relay.RelayProtocol.readOnion
import app.niix.core.relay.RelayProtocol.writeBlock
import app.niix.core.relay.RelayProtocol.writeOnion
import app.niix.core.transport.TorTransport
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client-side operations of the raw relay protocol (build spec item 11.2): opens a fresh
 * connection to a relay node's onion service for each call, exactly like every other short-lived
 * connection in this app (see `ConversationManager.fetchBundle`/`transportSend`). Every method
 * here is best-effort -- returns a failure value rather than throwing -- since callers
 * ([RelayManager]) always try several candidate relays and only need one to succeed.
 */
class RelayClient(
    private val transport: TorTransport,
    private val servicePort: Int,
) {

    suspend fun store(
        relayOnion: String,
        recipientIdKey: ByteArray,
        senderIdKey: ByteArray,
        grantIssuedAt: Long,
        grantExpiresAt: Long,
        grantSignature: ByteArray,
        requestSignature: ByteArray,
        ttlMillis: Long,
        envelope: ByteArray,
    ): Boolean = withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
        withContext(Dispatchers.IO) {
            runCatching {
                transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                    val out = DataOutputStream(connection.output)
                    out.writeByte(RelayProtocol.FRAME_RELAY_STORE)
                    writeBlock(out, recipientIdKey)
                    writeBlock(out, senderIdKey)
                    out.writeLong(grantIssuedAt)
                    out.writeLong(grantExpiresAt)
                    writeBlock(out, grantSignature)
                    writeBlock(out, requestSignature)
                    out.writeLong(ttlMillis)
                    writeBlock(out, envelope)
                    out.flush()
                    DataInputStream(connection.input).readByte().toInt() == RelayProtocol.FRAME_ACK
                }
            }.getOrDefault(false)
        }
    } ?: false

    /** Returns null on any failure (unreachable, malformed response, rejected). */
    suspend fun fetch(relayOnion: String, recipientIdKey: ByteArray, proofSignature: ByteArray, timestamp: Long): List<FetchedEnvelope>? =
        withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                        val out = DataOutputStream(connection.output)
                        out.writeByte(RelayProtocol.FRAME_RELAY_FETCH)
                        writeBlock(out, recipientIdKey)
                        out.writeLong(timestamp)
                        writeBlock(out, proofSignature)
                        out.flush()

                        val input = DataInputStream(connection.input)
                        val type = input.readByte().toInt()
                        if (type != RelayProtocol.FRAME_RELAY_FETCH_RESPONSE) return@use null
                        val count = input.readInt()
                        require(count in 0..MAX_LOOKUP_RESULTS) { "Implausible fetch response count $count" }
                        (0 until count).map {
                            val sender = readBlock(input, MAX_IDENTITY_KEY_BYTES)
                            val hash = readBlock(input, MAX_ENVELOPE_HASH_BYTES)
                            val envelope = readBlock(input, RelayProtocol.MAX_RELAY_ENVELOPE_BYTES.toInt())
                            FetchedEnvelope(sender, hash, envelope)
                        }
                    }
                }.getOrNull()
            }
        }

    suspend fun deleteReceipt(relayOnion: String, recipientIdKey: ByteArray, envelopeHash: ByteArray, proofSignature: ByteArray): Boolean =
        withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                        val out = DataOutputStream(connection.output)
                        out.writeByte(RelayProtocol.FRAME_RELAY_DELETE_RECEIPT)
                        writeBlock(out, recipientIdKey)
                        writeBlock(out, envelopeHash)
                        writeBlock(out, proofSignature)
                        out.flush()
                    }
                    true
                }.getOrDefault(false)
            }
        } ?: false

    /** Returns null on any failure. An empty (non-null) list is a valid, successful answer. */
    suspend fun findNode(
        relayOnion: String,
        targetKey: ByteArray,
        requesterOnion: String,
        requesterNodeId: ByteArray,
    ): List<NodeInfo>? = withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
        withContext(Dispatchers.IO) {
            runCatching {
                transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                    val out = DataOutputStream(connection.output)
                    out.writeByte(RelayProtocol.FRAME_RELAY_FIND_NODE)
                    writeBlock(out, targetKey)
                    writeOnion(out, requesterOnion)
                    writeBlock(out, requesterNodeId)
                    out.flush()

                    val input = DataInputStream(connection.input)
                    val type = input.readByte().toInt()
                    if (type != RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE) return@use null
                    val count = input.readInt()
                    require(count in 0..MAX_LOOKUP_RESULTS) { "Implausible find_node response count $count" }
                    (0 until count).mapNotNull {
                        val nodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)
                        val onion = readOnion(input)
                        if (nodeId.size == RelayProtocol.NODE_ID_BYTES) NodeInfo(nodeId, onion) else null
                    }
                }
            }.getOrNull()
        }
    }

    /** Fire-and-forget liveness/keepalive gossip -- no response expected. */
    suspend fun announce(relayOnion: String, ownNodeId: ByteArray, ownOnion: String) {
        withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                        val out = DataOutputStream(connection.output)
                        out.writeByte(RelayProtocol.FRAME_RELAY_ANNOUNCE)
                        writeBlock(out, ownNodeId)
                        writeOnion(out, ownOnion)
                        out.flush()
                    }
                }
            }
        }
    }
}
