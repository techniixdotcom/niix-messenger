package app.niix.core.nostr

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves

/**
 * BIP-340 Schnorr signing over secp256k1, which relays require on every event.
 *
 * We only sign with single-use keys, so the signature proves nothing about who sent an envelope.
 * That's also why it doesn't matter that it isn't post-quantum.
 */
internal object Bip340 {

    private val params = CustomNamedCurves.getByName("secp256k1")
    private val n: BigInteger = params.n

    fun isValidSecret(secret: ByteArray): Boolean {
        if (secret.size != 32) return false
        val d = BigInteger(1, secret)
        return d.signum() > 0 && d < n
    }

    /** x-only public key, 32 bytes. */
    fun publicKey(secret: ByteArray): ByteArray {
        require(isValidSecret(secret)) { "Invalid secp256k1 secret key" }
        return bytes32(params.g.multiply(BigInteger(1, secret)).normalize().affineXCoord.toBigInteger())
    }

    fun sign(secret: ByteArray, message: ByteArray, auxRand: ByteArray): ByteArray {
        require(isValidSecret(secret)) { "Invalid secp256k1 secret key" }
        require(auxRand.size == 32) { "auxRand must be 32 bytes" }
        val point = params.g.multiply(BigInteger(1, secret)).normalize()
        val d = BigInteger(1, secret).let { if (point.affineYCoord.toBigInteger().testBit(0)) n - it else it }
        val px = bytes32(point.affineXCoord.toBigInteger())

        val t = bytes32(d).also { masked ->
            val aux = taggedHash("BIP0340/aux", auxRand)
            for (i in masked.indices) masked[i] = (masked[i].toInt() xor aux[i].toInt()).toByte()
        }
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t, px, message)).mod(n)
        check(k0.signum() != 0) { "Nonce is zero" }
        val r = params.g.multiply(k0).normalize()
        val k = if (r.affineYCoord.toBigInteger().testBit(0)) n - k0 else k0
        val rx = bytes32(r.affineXCoord.toBigInteger())
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx, px, message)).mod(n)
        return rx + bytes32(k.add(e.multiply(d)).mod(n))
    }

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        val md = MessageDigest.getInstance("SHA-256")
        md.update(tagHash)
        md.update(tagHash)
        parts.forEach(md::update)
        return md.digest()
    }

    // BigInteger.toByteArray() is signed and minimal; pad or trim to exactly 32 bytes.
    private fun bytes32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("Value does not fit in 32 bytes")
        }
    }
}
