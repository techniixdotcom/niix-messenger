package app.niix.core.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Frame types, size limits, and low-level (de)serialization for the raw relay protocol -- item
 * 11.2 of the build spec ("unauthenticated transport, authenticated payload"). These frames are
 * self-contained: unlike [app.niix.core.messaging.WireMessage]'s outer
 * `[type][senderOnionLen][senderOnion][payloadLen][payload]` envelope (used for ordinary,
 * already-authenticated pairwise traffic), a relay frame carries whatever identity-key/signature
 * fields it needs directly, because relay operations are between strangers with no pairwise
 * Signal session -- authentication here is per-request public-key signature verification, never
 * connection-level identity. See [RelayConnectionHandler] (server side) and [RelayClient]
 * (client side), which are the only two places that read/write these.
 */
object RelayProtocol {

    // ---------------- Frame type bytes ----------------
    // 1-5 are already used by app.niix.core.messaging.ConversationManager's own frame types
    // (FRAME_MESSAGE, FRAME_BUNDLE_REQUEST/RESPONSE, FRAME_ACK, FRAME_ATTACHMENT); these
    // continue the same numbering space on the same wire so a single byte always disambiguates
    // every frame type this app ever sends or receives.
    const val FRAME_RELAY_STORE = 6
    const val FRAME_RELAY_FETCH = 7
    const val FRAME_RELAY_FETCH_RESPONSE = 8
    const val FRAME_RELAY_DELETE_RECEIPT = 9
    const val FRAME_RELAY_ANNOUNCE = 10
    const val FRAME_RELAY_REJECT = 11
    const val FRAME_RELAY_FIND_NODE = 12
    const val FRAME_RELAY_FIND_NODE_RESPONSE = 13

    // Reuses the existing generic FRAME_ACK type (=4) for a successful STORE -- see
    // ConversationManager.FRAME_ACK. Kept here too so relay code never needs to import from
    // core:messaging (which depends on core:relay, not the other way around).
    const val FRAME_ACK = 4

    // ---------------- Size / time limits (build spec item 11.2) ----------------

    /** Text-only envelope cap -- no attachments ever through this path. */
    const val MAX_RELAY_ENVELOPE_BYTES = 8 * 1024L

    /** Per recipient, per relay. */
    const val MAX_RELAY_ENVELOPES_PER_HASH = 50

    /** Relay enforces this cap even if a store request asks for longer. */
    const val MAX_RELAY_TTL_MILLIS = 6L * 60 * 60 * 1000

    /** A fetch/delete proof's timestamp must be within this window of the relay's own clock. */
    const val RELAY_FETCH_PROOF_WINDOW_MS = 2L * 60 * 1000

    // ---------------- Rate limiting / quotas (build spec item 11.5) ----------------

    const val DEFAULT_MAX_TOTAL_RELAY_BYTES = 50L * 1024 * 1024
    const val DEFAULT_MAX_STORES_PER_SENDER_PER_HOUR = 20
    const val RATE_LIMIT_WINDOW_MILLIS = 60L * 60 * 1000

    // ---------------- RelayGrant lifecycle (build spec item 11.1) ----------------

    const val GRANT_VALIDITY_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val GRANT_REISSUE_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

    // ---------------- Kademlia overlay (build spec item 11.3) ----------------

    /** k-bucket size / number of peers returned per FIND_NODE_RESPONSE. */
    const val KADEMLIA_K = 8

    /** Concurrency for an iterative lookup. */
    const val KADEMLIA_ALPHA = 3

    /** How many of the closest known nodes are actually contacted for a store or fetch. */
    const val LOOKUP_K = 5

    const val NODE_ID_BYTES = 32 // SHA-256

    // ---------------- Defensive parsing caps (not part of the spec's own numbers, but every
    // length-prefixed field read off the wire needs *some* upper bound before allocating a
    // buffer for it, the same way MAX_ONION_BYTES/MAX_PAYLOAD_BYTES already bound
    // ConversationManager's own frame parsing) ----------------

    const val MAX_IDENTITY_KEY_BYTES = 64
    const val MAX_SIGNATURE_BYTES = 128
    const val MAX_ONION_BYTES = 256
    const val MAX_LOOKUP_RESULTS = 64 // defensive cap on a claimed `count` field
    const val MAX_ENVELOPE_HASH_BYTES = 64 // SHA-256 = 32, capped generously above that

    /** Wall-clock time a single relay RPC (connect + one request/response) is allowed to take
     * before the caller gives up on that node and moves to the next. */
    const val RPC_TIMEOUT_MILLIS = 20_000L

    // ---------------- Byte helpers ----------------

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun nodeId(identityKeyBytes: ByteArray): ByteArray = sha256(identityKeyBytes)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun Long.toBigEndianBytes(): ByteArray {
        val out = ByteArrayOutputStream(8)
        DataOutputStream(out).writeLong(this)
        return out.toByteArray()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** XOR-distance ordering between two equal-length node ids, expressed as a comparator so
     * [RoutingTable]/[KademliaLookup] can sort candidates by closeness to a target without
     * needing BigInteger: lexicographic comparison of the XOR byte array is equivalent to
     * numeric distance comparison for same-length unsigned big-endian byte arrays. */
    fun xorDistanceComparator(target: ByteArray): Comparator<NodeInfo> = Comparator { a, b ->
        val da = xor(a.nodeId, target)
        val db = xor(b.nodeId, target)
        for (i in da.indices) {
            val cmp = (da[i].toInt() and 0xFF).compareTo(db[i].toInt() and 0xFF)
            if (cmp != 0) return@Comparator cmp
        }
        0
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    fun writeBlock(s: DataOutputStream, data: ByteArray) {
        s.writeInt(data.size)
        s.write(data)
    }

    fun readBlock(s: DataInputStream, maxLen: Int): ByteArray {
        val length = s.readInt()
        require(length in 0..maxLen) { "Invalid block length $length (max $maxLen)" }
        return ByteArray(length).also { s.readFully(it) }
    }

    fun writeOnion(s: DataOutputStream, onion: String) = writeBlock(s, onion.toByteArray(Charsets.UTF_8))

    fun readOnion(s: DataInputStream): String =
        String(readBlock(s, MAX_ONION_BYTES), Charsets.UTF_8)
}
