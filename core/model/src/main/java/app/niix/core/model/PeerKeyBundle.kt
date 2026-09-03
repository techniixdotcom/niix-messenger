package app.niix.core.model

class PeerKeyBundle(
    val ownerOnionAddress: OnionAddress,
    val serialized: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerKeyBundle) return false
        return ownerOnionAddress == other.ownerOnionAddress &&
            serialized.contentEquals(other.serialized)
    }

    override fun hashCode(): Int =
        31 * ownerOnionAddress.hashCode() + serialized.contentHashCode()
}
