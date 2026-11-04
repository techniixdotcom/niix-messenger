package app.niix.core.relay

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

data class FetchedEnvelope(
    val senderIdKey: ByteArray,
    val envelopeHash: ByteArray,
    val envelope: ByteArray,
)

data class NodeInfo(val nodeId: ByteArray, val onion: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeInfo) return false
        return nodeId.contentEquals(other.nodeId) && onion == other.onion
    }

    override fun hashCode(): Int = 31 * nodeId.contentHashCode() + onion.hashCode()
}

data class FindNodeResult(val responder: NodeInfo?, val candidates: List<NodeInfo>)

object RelayRejectReason {
    const val TOO_LARGE: Int = 1
    const val GRANT_EXPIRED: Int = 2
    const val BAD_GRANT_SIGNATURE: Int = 3
    const val BAD_REQUEST_SIGNATURE: Int = 4
    const val RECIPIENT_QUOTA_FULL: Int = 5
    const val RATE_LIMITED: Int = 6
    const val PROOF_INVALID: Int = 7
}
