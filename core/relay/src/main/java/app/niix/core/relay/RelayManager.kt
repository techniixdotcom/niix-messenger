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

/**
 * Top-level facade for item 11 (decentralized offline mailbox/relay), owned by the app the same
 * way [app.niix.core.messaging.ConversationManager] owns messaging. Splits cleanly into two
 * roles that are independent of each other:
 *
 *  - **Client** (always active, regardless of this device's own "Enable relay mode" setting):
 *    [storeForOffline] / [fetchIncoming] / grant issuance ([grantDue]/[recordGrantIssued]) are
 *    what let *this device's own messages* get a durable offline path via *other* people's opt-in
 *    relay nodes. A device that never hosts anything for strangers can still fully use the
 *    network this way.
 *  - **Host** ([handleFrame], delegated to [RelayConnectionHandler]): whether this device stores
 *    *other* people's messages and participates in the DHT as a queryable node. Gated entirely
 *    by [isHostingEnabled] -- see [RelayConnectionHandler]'s class doc for why that's the layer
 *    responsible for "zero effect on any user who never turns it on".
 *
 * Deliberately has no dependency on `core:messaging`/`ConversationManager` (dependency direction
 * is the other way -- `core:messaging` depends on `core:relay`) so sending the actual
 * [app.niix.core.messaging.WireMessage.RelayGrant] / [app.niix.core.messaging.WireMessage.RelayCapabilityUpdate]
 * wire messages stays [app.niix.core.messaging.ConversationManager]'s job; this class only ever
 * hands back plain data for the caller to send.
 */
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
    private val client: RelayClient = RelayClient(transport, servicePort)
    private val lookup: KademliaLookup = KademliaLookup(routingTable, client, localNodeId, selfOnionProvider)
    private val connectionHandler: RelayConnectionHandler = RelayConnectionHandler(
        store = store,
        routingTable = routingTable,
        rateLimiter = rateLimiter,
        crypto = crypto,
        hostingEnabled = ::isHostingEnabled,
    )
    private val sweeper: RelaySweeper = RelaySweeper(store)

    // ---------------- Lifecycle ----------------

    fun start(scope: CoroutineScope) {
        sweeper.start(scope)
    }

    fun stop() {
        sweeper.stop()
    }

    // ---------------- Settings (build spec item 11.8) ----------------

    fun isHostingEnabled(): Boolean = storage.settings.getBool(SettingsStore.KEY_RELAY_MODE_ENABLED, false)

    fun setHostingEnabled(enabled: Boolean) {
        storage.settings.setBool(SettingsStore.KEY_RELAY_MODE_ENABLED, enabled)
        if (!enabled) {
            // Nothing this device was holding for strangers should linger in memory once the
            // person opts back out.
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

    // ---------------- Host role: inbound frames ----------------

    /** [type] is the frame-type byte the caller already consumed from [input]. See
     * [RelayConnectionHandler]. */
    suspend fun handleFrame(type: Int, input: DataInputStream, output: OutputStream) {
        connectionHandler.handleFrame(type, input, output)
    }

    fun isRelayFrameType(type: Int): Boolean = type in RelayProtocol.FRAME_RELAY_STORE..RelayProtocol.FRAME_RELAY_FIND_NODE_RESPONSE

    // ---------------- Overlay bootstrap (build spec item 11.3) ----------------

    /** Seeds/updates the routing table from a contact's RelayCapabilityUpdate announcement --
     * purely a bootstrap/cold-start convenience over an already-encrypted channel; membership in
     * the wider overlay this then discovers via FIND_NODE is never restricted to contacts. */
    fun onContactAnnouncedRelayCapability(peerOnion: String, peerIdentityKey: ByteArray, enabled: Boolean) {
        val peerNodeId = nodeId(peerIdentityKey)
        if (enabled) {
            routingTable.insertOrUpdate(NodeInfo(peerNodeId, peerOnion))
        } else {
            routingTable.remove(peerNodeId)
        }
    }

    fun knownRelayPeerCount(): Int = routingTable.size()

    // ---------------- RelayGrant issuance (build spec item 11.1) ----------------

    /** Returns a freshly-built, signed grant to send to [peerOnion] if one is due -- no grant on
     * file yet, or the one on file expires within [RelayProtocol.GRANT_REISSUE_WINDOW_MILLIS] --
     * or null if the existing one is still comfortably valid. Does not persist anything; call
     * [recordGrantIssued] once the caller has actually delivered it. */
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

    /** Persists a grant received from a contact ([issuerOnion], the future recipient of any
     * message we relay-store on their behalf) after [app.niix.core.messaging.ConversationManager]
     * has already checked it actually names our own identity key as grantee. */
    fun recordGrantReceived(issuerOnion: String, issuerIdentityKey: ByteArray, issuedAt: Long, expiresAt: Long, signature: ByteArray) {
        storage.relayGrants.upsertReceived(
            RelayGrantReceived(issuerOnion, issuerIdentityKey, issuedAt, expiresAt, signature),
        )
    }

    // ---------------- Client role: sending via relay when direct delivery fails ----------------

    /**
     * Attempts to leave [envelope] (an already-Signal-encrypted ciphertext -- the exact same
     * bytes a direct send would have transmitted, encrypted exactly once, never re-encrypted
     * here) at up to [RelayProtocol.LOOKUP_K] relay nodes for [recipientOnion] to fetch later.
     * Requires a still-valid [RelayGrant] this device previously received *from* that contact
     * (see [recordGrantReceived]) -- without one, this can never succeed, by design (build spec
     * item 11.1: never a self-reported claim, never a shared secret).
     */
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

    /**
     * Checks every currently-known relay node for envelopes addressed to this device, sending a
     * delete receipt for each one retrieved (so it doesn't linger for the relay's own TTL once
     * we already have it) regardless of whether decryption later succeeds. Returns
     * (senderIdentityKey, envelope) pairs for the caller to resolve a sender onion for, decrypt,
     * and dispatch exactly as if they'd arrived directly (see
     * [app.niix.core.messaging.ConversationManager.fetchRelayedMessages]).
     */
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
