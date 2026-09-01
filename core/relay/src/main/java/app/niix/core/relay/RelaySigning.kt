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
