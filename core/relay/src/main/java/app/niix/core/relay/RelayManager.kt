package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
import app.niix.core.model.DiagnosticLog
import app.niix.core.relay.RelayProtocol.nodeId
import app.niix.core.relay.RelayProtocol.toBigEndianBytes
import app.niix.core.relay.RelayProtocol.toHex
import app.niix.core.storage.RelayGrantIssued
import app.niix.core.storage.RelayGrantReceived
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.TorTransport
import java.io.DataInputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope

class RelayManager(
    private val storage: SecureStorage,
    private val crypto: CryptoEngine,
    transport: TorTransport,
    servicePort: Int,
    private val selfOnionProvider: () -> String?,
) {

    val localNodeId: ByteArray by lazy { nodeId(crypto.localIdentityKey()) }

    val store: RelayStore = RelayStore(maxTotalRelayBytes = readStorageBudget())
    private val routingTable: RoutingTable = RoutingTable(localNodeId)
    private val rateLimiter: RelayRateLimiter = RelayRateLimiter()
    private val client: RelayClient = RelayClient(transport, servicePort, crypto)
    private val lookup: KademliaLookup = KademliaLookup(routingTable, client, localNodeId, selfOnionProvider)
    private val connectionHandler: RelayConnectionHandler = RelayConnectionHandler(
        store = store,
        routingTable = routingTable,
        rateLimiter = rateLimiter,
        crypto = crypto,
        hostingEnabled = ::isHostingEnabled,
        selfOnionProvider = selfOnionProvider,
    )
    private val sweeper: RelaySweeper = RelaySweeper(store)

    fun start(scope: CoroutineScope) {
        sweeper.start(scope)
    }

    fun stop() {
        sweeper.stop()
    }

    fun isHostingEnabled(): Boolean = storage.settings.getBool(SettingsStore.KEY_RELAY_MODE_ENABLED, false)

    fun setHostingEnabled(enabled: Boolean) {
        storage.settings.setBool(SettingsStore.KEY_RELAY_MODE_ENABLED, enabled)
        if (!enabled) {

            store.clear()
        }
    }

    fun storageBudgetBytes(): Long = readStorageBudget()

    fun setStorageBudgetBytes(bytes: Long) {
        val clamped = bytes.coerceAtLeast(0)
        storage.settings.setLong(SettingsStore.KEY_RELAY_STORAGE_BUDGET_BYTES, clamped)
        store.maxTotalRelayBytes = clamped
    }

    private fun readStorageBudget(): Long =
        storage.settings.getLong(SettingsStore.KEY_RELAY_STORAGE_BUDGET_BYTES, RelayProtocol.DEFAULT_MAX_TOTAL_RELAY_BYTES)

    suspend fun handleFrame(type: Int, input: DataInputStream, output: OutputStream) {
        connectionHandler.handleFrame(type, input, output)
    }

    fun isRelayFrameType(type: Int): Boolean = type in RelayProtocol.FRAME_RELAY_STORE..RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE

    fun onContactAnnouncedRelayCapability(peerOnion: String, peerIdentityKey: ByteArray, enabled: Boolean) {
        val peerNodeId = nodeId(peerIdentityKey)
        if (enabled) {
            routingTable.insertOrUpdate(NodeInfo(peerNodeId, peerOnion))
        } else {
            routingTable.remove(peerNodeId)
        }
    }

    fun knownRelayPeerCount(): Int = routingTable.size()

    fun grantDue(peerOnion: String, peerIdentityKey: ByteArray, now: Long = System.currentTimeMillis()): RelayGrant? {
        val existing = storage.relayGrants.getIssued(peerOnion)
        val needsIssue = existing == null || existing.expiresAt - now < RelayProtocol.GRANT_REISSUE_WINDOW_MILLIS
        if (!needsIssue) return null
        return RelaySigning.issueGrant(crypto, peerIdentityKey, now)
    }

    fun recordGrantIssued(peerOnion: String, grant: RelayGrant) {
        storage.relayGrants.upsertIssued(
            RelayGrantIssued(peerOnion, grant.granteeIdentityKey, grant.issuedAt, grant.expiresAt),
        )
    }

    fun recordGrantReceived(issuerOnion: String, issuerIdentityKey: ByteArray, issuedAt: Long, expiresAt: Long, signature: ByteArray) {
        storage.relayGrants.upsertReceived(
            RelayGrantReceived(issuerOnion, issuerIdentityKey, issuedAt, expiresAt, signature),
        )
    }

    suspend fun storeForOffline(recipientOnion: String, envelope: ByteArray): Boolean {
        if (envelope.size > RelayProtocol.MAX_RELAY_ENVELOPE_BYTES) return false
        val recipientIdKey = crypto.remoteIdentityKeyBytes(recipientOnion) ?: run {
            DiagnosticLog.record("relay", "cannot relay: no identity key stored for the recipient")
            return false
        }
        // Relaying to someone requires a grant they issued. Each of these was a silent false,
        // which made "message did not arrive" indistinguishable from "message was never sent" --
        // and this is the path that decides whether an offline peer gets a message at all.
        val grant = storage.relayGrants.getReceived(recipientOnion) ?: run {
            DiagnosticLog.record("relay", "cannot relay: recipient has not issued us a relay grant yet")
            return false
        }
        val now = System.currentTimeMillis()
        if (grant.expiresAt <= now) {
            DiagnosticLog.record("relay", "cannot relay: the recipient's grant has expired")
            return false
        }
        if (!grant.issuerIdentityKey.contentEquals(recipientIdKey)) {
            DiagnosticLog.record("relay", "cannot relay: grant was issued by a different identity than the recipient")
            return false
        }

        val localIdKey = crypto.localIdentityKey()
        val requestSig = RelaySigning.signStoreRequest(crypto, recipientIdKey, localIdKey, envelope, RelayProtocol.MAX_RELAY_TTL_MILLIS)
        val targetKey = nodeId(recipientIdKey)
        val candidates = lookup.lookup(targetKey)
        if (candidates.isEmpty()) return false

        var anySucceeded = false
        for (node in candidates) {
            val ok = client.store(
                relayOnion = node.onion,
                recipientIdKey = recipientIdKey,
                senderIdKey = localIdKey,
                grantIssuedAt = grant.issuedAt,
                grantExpiresAt = grant.expiresAt,
                grantSignature = grant.signature,
                requestSignature = requestSig,
                ttlMillis = RelayProtocol.MAX_RELAY_TTL_MILLIS,
                envelope = envelope,
            )
            if (ok) anySucceeded = true
        }
        return anySucceeded
    }

    /**
     * One envelope retrieved from a relay, along with what is needed to delete it afterwards.
     *
     * The delete is deliberately not performed here. It used to be, immediately after fetching --
     * which meant an envelope was removed from the relay before the caller had decrypted or
     * stored it. Any transient failure in between (a decrypt error, the process being killed,
     * the database not yet open) turned a deliverable message into a permanently lost one, with
     * nothing anywhere recording that it had existed.
     *
     * Leaving deletion to the caller makes delivery at-least-once: a message may be fetched
     * twice, which costs nothing because message ids are deduplicated on insert, but it is never
     * dropped because processing failed.
     */
    data class FetchedEnvelope(
        val senderIdKey: ByteArray,
        val envelope: ByteArray,
        val nodeOnion: String,
        val envelopeHash: ByteArray,
    )

    suspend fun fetchIncoming(): List<FetchedEnvelope> {
        val localIdKey = crypto.localIdentityKey()
        val targetKey = nodeId(localIdKey)
        val candidates = lookup.lookup(targetKey)
        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val proofSig = RelaySigning.signKeyPossessionProof(crypto, localIdKey, now.toBigEndianBytes())
        val seenHashes = HashSet<String>()
        val results = mutableListOf<FetchedEnvelope>()

        for (node in candidates) {
            val response = client.fetch(node.onion, localIdKey, proofSig, now) ?: continue
            for (item in response) {
                val hashHex = item.envelopeHash.toHex()
                if (!seenHashes.add(hashHex)) continue
                results.add(
                    FetchedEnvelope(item.senderIdKey, item.envelope, node.onion, item.envelopeHash),
                )
            }
        }
        return results
    }

    /** Removes an envelope from the relay, once the caller has durably accepted it. */
    suspend fun confirmProcessed(fetched: FetchedEnvelope) {
        val localIdKey = crypto.localIdentityKey()
        val deleteSig = RelaySigning.signKeyPossessionProof(crypto, localIdKey, fetched.envelopeHash)
        client.deleteReceipt(fetched.nodeOnion, localIdKey, fetched.envelopeHash, deleteSig)
    }
}
