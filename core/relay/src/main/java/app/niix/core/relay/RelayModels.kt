package app.niix.core.relay

/**
 * A certificate proving that [granteeIdentityKey] -- a specific contact's Signal identity key --
 * is authorized to store offline messages *for the issuer* via opt-in relay nodes, until
 * [expiresAt]. See item 11 (decentralized offline mailbox/relay) of the build spec.
 *
 * Two of these exist for any pair of contacts, one issued by each side. This is the "I authorize
 * you to leave messages for me" certificate: [issuerIdentityKey] is whoever signed it (the future
 * *recipient* of a relayed message), and [signature] is that issuer's own identity-key signature
 * over (granteeIdentityKey || issuedAt || expiresAt) -- see [RelaySigning]. A relay verifies
 * [signature] against [issuerIdentityKey] before ever accepting a FRAME_RELAY_STORE naming
 * [issuerIdentityKey] as the recipient -- see [RelayConnectionHandler]. This is deliberately the
 * *only* thing that authorizes a store: never a shared secret, never a self-reported claim, never
 * relay-node or contact-list membership.
 */
data class RelayGrant(
    val issuerIdentityKey: ByteArray,
    val granteeIdentityKey: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayGrant) return false
        return issuerIdentityKey.contentEquals(other.issuerIdentityKey) &&
            granteeIdentityKey.contentEquals(other.granteeIdentityKey) &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = issuerIdentityKey.contentHashCode()
        result = 31 * result + granteeIdentityKey.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * One relay-held offline message. In-memory only, never persisted -- see [RelayStore]'s class
 * doc for why this type deliberately never gets a `storage.*` DAO / SQLCipher table the way
 * every other stateful model in this app does.
 */
data class StoredEnvelope(
    val senderIdKey: ByteArray,
    val envelope: ByteArray,
    val envelopeHash: ByteArray,
    val storedAt: Long,
    val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredEnvelope) return false
        return senderIdKey.contentEquals(other.senderIdKey) &&
            envelope.contentEquals(other.envelope) &&
            envelopeHash.contentEquals(other.envelopeHash) &&
            storedAt == other.storedAt &&
            expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var result = senderIdKey.contentHashCode()
        result = 31 * result + envelope.contentHashCode()
        result = 31 * result + envelopeHash.contentHashCode()
        result = 31 * result + storedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

/** One entry returned by a FRAME_RELAY_FETCH_RESPONSE: an envelope plus who it's addressed
 * from and the hash a subsequent FRAME_RELAY_DELETE_RECEIPT must reference. */
data class FetchedEnvelope(
    val senderIdKey: ByteArray,
    val envelopeHash: ByteArray,
    val envelope: ByteArray,
)

/**
 * One peer in the relay Kademlia overlay: reachable at [onion] -- the same niix onion service
 * (and port) that peer already listens for ordinary messaging traffic on, since relay frames
 * share that hidden service and are distinguished purely by frame type (see [RelayProtocol]) --
 * identified by [nodeId] = SHA-256(that peer's Signal identity key), see [RelayProtocol.nodeId].
 */
data class NodeInfo(val nodeId: ByteArray, val onion: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeInfo) return false
        return nodeId.contentEquals(other.nodeId) && onion == other.onion
    }

    override fun hashCode(): Int = 31 * nodeId.contentHashCode() + onion.hashCode()
}

/** 1-byte reason codes sent back in a FRAME_RELAY_REJECT frame. Codes 1-6 are exactly the ones
 * enumerated for FRAME_RELAY_STORE by build spec item 11.2. [PROOF_INVALID] is this
 * implementation's own extension of that numbering for FRAME_RELAY_FETCH / FRAME_RELAY_STORE
 * request-proof failures, which the spec requires be rejected but doesn't itself assign a code
 * to. */
object RelayRejectReason {
    const val TOO_LARGE: Int = 1
    const val GRANT_EXPIRED: Int = 2
    const val BAD_GRANT_SIGNATURE: Int = 3
    const val BAD_REQUEST_SIGNATURE: Int = 4
    const val RECIPIENT_QUOTA_FULL: Int = 5
    const val RATE_LIMITED: Int = 6
    const val PROOF_INVALID: Int = 7
}
