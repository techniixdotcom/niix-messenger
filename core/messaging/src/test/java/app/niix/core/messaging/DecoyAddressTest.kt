package app.niix.core.messaging

import app.niix.core.model.OnionAddress
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA3Digest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecoyAddressTest {

    @Test
    fun `decoy addresses are well-formed v3 onions`() {
        val rng = SecureRandom()
        repeat(50) {
            val address = DecoyAddress.random(rng)
            OnionAddress.parse(address)
            val decoded = base32Decode(address.removeSuffix(".onion"))
            assertEquals(35, decoded.size)
            assertEquals(3, decoded[34].toInt())
            val digest = SHA3Digest(256)
            val prefix = ".onion checksum".toByteArray()
            digest.update(prefix, 0, prefix.size)
            digest.update(decoded, 0, 32)
            digest.update(3)
            val checksum = ByteArray(32).also { digest.doFinal(it, 0) }
            assertArrayEquals(checksum.copyOf(2), decoded.copyOfRange(32, 34))
        }
    }

    @Test
    fun `each decoy is different`() {
        val rng = SecureRandom()
        assertNotEquals(DecoyAddress.random(rng), DecoyAddress.random(rng))
    }

    private fun base32Decode(text: String): ByteArray {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
