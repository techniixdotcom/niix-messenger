package app.niix.core.messaging

import app.niix.core.model.OnionAddress
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * Onion addresses for the duress decoy's made-up contacts.
 *
 * They have to pass for real ones. A v3 address carries a checksum and a version, and random
 * base32 fails both, which anyone looking closely would spot. These are built the way Tor builds
 * them, from a real Ed25519 public key whose private key is thrown away.
 */
internal object DecoyAddress {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val VERSION: Byte = 3

    fun random(rng: SecureRandom): String {
        val publicKey = Ed25519PrivateKeyParameters(rng).generatePublicKey().encoded
        val digest = SHA3Digest(256)
        val prefix = ".onion checksum".toByteArray()
        digest.update(prefix, 0, prefix.size)
        digest.update(publicKey, 0, publicKey.size)
        digest.update(VERSION)
        val checksum = ByteArray(32).also { digest.doFinal(it, 0) }
        return base32(publicKey + checksum.copyOf(2) + VERSION) + OnionAddress.SUFFIX
    }

    /** RFC 4648 base32, lowercase, no padding. 35 bytes come out as exactly 56 characters. */
    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
