package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
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
        val recipientIdKey = crypto.remoteIdentityKeyBytes(recipientOnion) ?: return false
        val grant = storage.relayGrants.getReceived(recipientOnion) ?: return false
        val now = System.currentTimeMillis()
        if (grant.expiresAt <= now) return false
        if (!grant.issuerIdentityKey.contentEquals(recipientIdKey)) return false

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

    suspend fun fetchIncoming(): List<Pair<ByteArray, ByteArray>> {
        val localIdKey = crypto.localIdentityKey()
        val targetKey = nodeId(localIdKey)
        val candidates = lookup.lookup(targetKey)
        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val proofSig = RelaySigning.signKeyPossessionProof(crypto, localIdKey, now.toBigEndianBytes())
        val seenHashes = HashSet<String>()
        val results = mutableListOf<Pair<ByteArray, ByteArray>>()

        for (node in candidates) {
            val response = client.fetch(node.onion, localIdKey, proofSig, now) ?: continue
            for (item in response) {
                val hashHex = item.envelopeHash.toHex()
                if (!seenHashes.add(hashHex)) continue
                results.add(item.senderIdKey to item.envelope)
                val deleteSig = RelaySigning.signKeyPossessionProof(crypto, localIdKey, item.envelopeHash)
                client.deleteReceipt(node.onion, localIdKey, item.envelopeHash, deleteSig)
            }
        }
        return results
    }
}
