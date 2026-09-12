package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
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

class RelayClient(
    private val transport: TorTransport,
    private val servicePort: Int,
    private val crypto: CryptoEngine,
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

    suspend fun findNode(
        relayOnion: String,
        targetKey: ByteArray,
        requesterOnion: String,
        requesterNodeId: ByteArray,
    ): FindNodeResult? = withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
        withContext(Dispatchers.IO) {
            runCatching {
                transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                    val out = DataOutputStream(connection.output)
                    out.writeByte(RelayProtocol.FRAME_RELAY_FIND_NODE)
                    writeBlock(out, targetKey)
                    writeOnion(out, requesterOnion)
                    writeBlock(out, requesterNodeId)
                    writeBlock(out, crypto.localIdentityKey())
                    writeBlock(out, RelaySigning.signNodeIdentity(crypto, requesterNodeId, requesterOnion))
                    out.flush()

                    val input = DataInputStream(connection.input)
                    val type = input.readByte().toInt()
                    if (type != RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE) return@use null

                    val responderNodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)
                    val responderIdentityKey = readBlock(input, MAX_IDENTITY_KEY_BYTES)
                    val responderSignature = readBlock(input, MAX_SIGNATURE_BYTES)
                    val responder = if (
                        responderNodeId.size == RelayProtocol.NODE_ID_BYTES &&
                        RelaySigning.verifyNodeIdentity(crypto, responderIdentityKey, responderNodeId, relayOnion, responderSignature)
                    ) {
                        NodeInfo(responderNodeId, relayOnion)
                    } else {
                        null
                    }

                    val count = input.readInt()
                    require(count in 0..MAX_LOOKUP_RESULTS) { "Implausible find_node response count $count" }
                    val candidates = (0 until count).mapNotNull {
                        val nodeId = readBlock(input, RelayProtocol.NODE_ID_BYTES)
                        val onion = readOnion(input)
                        if (nodeId.size == RelayProtocol.NODE_ID_BYTES) NodeInfo(nodeId, onion) else null
                    }
                    FindNodeResult(responder, candidates)
                }
            }.getOrNull()
        }
    }

    suspend fun announce(relayOnion: String, ownNodeId: ByteArray, ownOnion: String) {
        withTimeoutOrNull(RelayProtocol.RPC_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    transport.connect(OnionAddress.parse(relayOnion), servicePort).use { connection ->
                        val out = DataOutputStream(connection.output)
                        out.writeByte(RelayProtocol.FRAME_RELAY_ANNOUNCE)
                        writeBlock(out, ownNodeId)
                        writeOnion(out, ownOnion)
                        writeBlock(out, crypto.localIdentityKey())
                        writeBlock(out, RelaySigning.signNodeIdentity(crypto, ownNodeId, ownOnion))
                        out.flush()
                    }
                }
            }
        }
    }
}
