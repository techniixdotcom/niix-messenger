package app.niix.core.nostr

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint

/** BIP-340 verification, straight from the spec. Only tests need it; the app never verifies. */
internal object Bip340Verifier {

    private val params = CustomNamedCurves.getByName("secp256k1")
    private val n = params.n
    private val p = params.curve.field.characteristic

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val point = liftX(BigInteger(1, publicKey)) ?: return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= p || s >= n) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + publicKey + message)).mod(n)
        val rPoint = params.g.multiply(s).add(point.multiply(n - e)).normalize()
        return !rPoint.isInfinity && !rPoint.affineYCoord.toBigInteger().testBit(0) && rPoint.affineXCoord.toBigInteger() == r
    }

    fun verifyEvent(event: NostrEvent): Boolean {
        val id = NostrEvent.computeId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        return Hex.encode(id) == event.id && verify(Hex.decode(event.pubkey), id, Hex.decode(event.sig))
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x >= p) return null
        val c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.modPow(BigInteger.TWO, p) != c) return null
        return params.curve.createPoint(x, if (y.testBit(0)) p - y else y)
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        return MessageDigest.getInstance("SHA-256").digest(tagHash + tagHash + data)
    }
}
