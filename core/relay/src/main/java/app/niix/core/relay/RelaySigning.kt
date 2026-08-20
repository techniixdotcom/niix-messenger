package app.niix.core.relay

import app.niix.core.crypto.CryptoEngine
import app.niix.core.relay.RelayProtocol.concat
import app.niix.core.relay.RelayProtocol.toBigEndianBytes

/**
 * Builds and verifies every signed message the relay protocol uses. Every one of these is a
 * plain XEdDSA signature (see [CryptoEngine.signWithIdentityKey] /
 * [CryptoEngine.verifyIdentitySignature]) over a fixed concatenation of fields -- never a shared
 * secret, and never anything the relay itself holds any private material for. See build spec
 * items 11.1/11.2 for the exact byte layouts reproduced here.
 */
object RelaySigning {

    /** Issues a fresh RelayGrant naming [granteeIdentityKey] as authorized to relay-store for
     * this device (the issuer), valid for [RelayProtocol.GRANT_VALIDITY_MILLIS] from [now]. */
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

    /** Verifies a RelayGrant's signature was produced by [issuerIdentityKey] over exactly the
     * fields it claims (grantee/issuedAt/expiresAt) -- does not check expiry, which is a
     * separate, caller-side check (see [RelayConnectionHandler]). */
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

    /** The sender's own signature over a specific FRAME_RELAY_STORE request, proving *this*
     * request genuinely came from the key the grant names rather than a replay of a past grant
     * by a third party who merely observed it (build spec item 11.2). */
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

    /** Proves the fetcher actually holds the private key for the identity being queried, so a
     * stranger who only knows a target's public identity key can't enumerate/drain their
     * mailbox (build spec item 11.2). Also reused, with the envelope hash in place of the
     * timestamp, for a FRAME_RELAY_DELETE_RECEIPT's proof. */
    fun signKeyPossessionProof(crypto: CryptoEngine, ownIdentityKey: ByteArray, subject: ByteArray): ByteArray =
        crypto.signWithIdentityKey(concat(ownIdentityKey, subject))

    fun verifyKeyPossessionProof(
        crypto: CryptoEngine,
        claimedIdentityKey: ByteArray,
        subject: ByteArray,
        signature: ByteArray,
    ): Boolean = crypto.verifyIdentitySignature(claimedIdentityKey, concat(claimedIdentityKey, subject), signature)
}
