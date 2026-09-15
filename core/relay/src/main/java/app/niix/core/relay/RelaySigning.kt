package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
import app.niix.core.relay.RelayProtocol.concat
import app.niix.core.relay.RelayProtocol.toBigEndianBytes

object RelaySigning {

    fun issueGrant(crypto: CryptoEngine, granteeIdentityKey: ByteArray, now: Long): RelayGrant {
        val expiresAt = now + RelayProtocol.GRANT_VALIDITY_MILLIS
        val signature = crypto.signWithIdentityKey(grantMessage(granteeIdentityKey, now, expiresAt))
        return RelayGrant(
            issuerIdentityKey = crypto.localIdentityKey(),
            granteeIdentityKey = granteeIdentityKey,
            issuedAt = now,
            expiresAt = expiresAt,
            signature = signature,
        )
    }

    private fun grantMessage(granteeIdentityKey: ByteArray, issuedAt: Long, expiresAt: Long): ByteArray =
        concat(granteeIdentityKey, issuedAt.toBigEndianBytes(), expiresAt.toBigEndianBytes())

    fun verifyGrantSignature(
        crypto: CryptoEngine,
        issuerIdentityKey: ByteArray,
        granteeIdentityKey: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        signature: ByteArray,
    ): Boolean = crypto.verifyIdentitySignature(
        issuerIdentityKey,
        grantMessage(granteeIdentityKey, issuedAt, expiresAt),
        signature,
    )

    fun signStoreRequest(
        crypto: CryptoEngine,
        recipientIdKey: ByteArray,
        senderIdKey: ByteArray,
        envelope: ByteArray,
        ttlMillis: Long,
    ): ByteArray = crypto.signWithIdentityKey(storeRequestMessage(recipientIdKey, senderIdKey, envelope, ttlMillis))

    fun verifyStoreRequestSignature(
        crypto: CryptoEngine,
        senderIdKey: ByteArray,
        recipientIdKey: ByteArray,
        envelope: ByteArray,
        ttlMillis: Long,
        signature: ByteArray,
    ): Boolean = crypto.verifyIdentitySignature(
        senderIdKey,
        storeRequestMessage(recipientIdKey, senderIdKey, envelope, ttlMillis),
        signature,
    )

    private fun storeRequestMessage(recipientIdKey: ByteArray, senderIdKey: ByteArray, envelope: ByteArray, ttlMillis: Long): ByteArray =
        concat(recipientIdKey, senderIdKey, envelope, ttlMillis.toBigEndianBytes())

    /**
     * Proof that the sender holds the identity key, valid only at [relayOnion].
     *
     * The relay's address is part of what is signed, so a proof observed by one relay cannot be
     * presented to another. Without it a single proof worked everywhere for the length of its
     * window, and any relay on the path could collect the recipient's envelopes from every other
     * relay holding them.
     *
     * Domain-separated from the delete proof so neither can be repurposed as the other.
     */
    fun signFetchProof(crypto: CryptoEngine, ownIdentityKey: ByteArray, timestampMillis: Long, relayOnion: String): ByteArray =
        signKeyPossessionProof(
            crypto,
            ownIdentityKey,
            concat("niix-fetch".toByteArray(), timestampMillis.toBigEndianBytes(), relayOnion.toByteArray()),
        )

    fun verifyFetchProof(
        crypto: CryptoEngine,
        claimedIdentityKey: ByteArray,
        timestampMillis: Long,
        relayOnion: String,
        signature: ByteArray,
    ): Boolean = verifyKeyPossessionProof(
        crypto,
        claimedIdentityKey,
        concat("niix-fetch".toByteArray(), timestampMillis.toBigEndianBytes(), relayOnion.toByteArray()),
        signature,
    )

    /** As above, for deleting a fetched envelope. Same reasoning: a delete proof seen by one
     * relay must not delete the same envelope held by another. */
    fun signDeleteProof(crypto: CryptoEngine, ownIdentityKey: ByteArray, envelopeHash: ByteArray, relayOnion: String): ByteArray =
        signKeyPossessionProof(
            crypto,
            ownIdentityKey,
            concat("niix-delete".toByteArray(), envelopeHash, relayOnion.toByteArray()),
        )

    fun verifyDeleteProof(
        crypto: CryptoEngine,
        claimedIdentityKey: ByteArray,
        envelopeHash: ByteArray,
        relayOnion: String,
        signature: ByteArray,
    ): Boolean = verifyKeyPossessionProof(
        crypto,
        claimedIdentityKey,
        concat("niix-delete".toByteArray(), envelopeHash, relayOnion.toByteArray()),
        signature,
    )

    fun signKeyPossessionProof(crypto: CryptoEngine, ownIdentityKey: ByteArray, subject: ByteArray): ByteArray =
        crypto.signWithIdentityKey(concat(ownIdentityKey, subject))

    fun verifyKeyPossessionProof(
        crypto: CryptoEngine,
        claimedIdentityKey: ByteArray,
        subject: ByteArray,
        signature: ByteArray,
    ): Boolean = crypto.verifyIdentitySignature(claimedIdentityKey, concat(claimedIdentityKey, subject), signature)

    fun signNodeIdentity(crypto: CryptoEngine, nodeId: ByteArray, onion: String): ByteArray =
        crypto.signWithIdentityKey(nodeIdentityMessage(nodeId, onion))

    fun verifyNodeIdentity(
        crypto: CryptoEngine,
        claimedIdentityKey: ByteArray,
        nodeId: ByteArray,
        onion: String,
        signature: ByteArray,
    ): Boolean {
        if (!RelayProtocol.nodeId(claimedIdentityKey).contentEquals(nodeId)) return false
        return crypto.verifyIdentitySignature(claimedIdentityKey, nodeIdentityMessage(nodeId, onion), signature)
    }

    private fun nodeIdentityMessage(nodeId: ByteArray, onion: String): ByteArray =
        concat(nodeId, onion.toByteArray(Charsets.US_ASCII))
}
