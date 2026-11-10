package app.niix.core.storage

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * The desktop version of the Android keystore wrapper: AES-256-GCM with a key from [KeyVault].
 * The alias is bound in as associated data, so a blob wrapped for one purpose can't be passed off
 * as another. [unlockedDeviceRequired] has no desktop equivalent; the keyring is open while the
 * user's session is.
 */
internal class KeystoreKeyManager(
    private val keyAlias: String = DEFAULT_ALIAS,
    @Suppress("unused") private val unlockedDeviceRequired: Boolean = true,
) {

    fun wrap(plaintext: ByteArray): WrappedBytes {
        val key = KeyVault.key(keyAlias, create = true) ?: error("Key '$keyAlias' could not be created")
        try {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))
            return WrappedBytes(iv = iv, ciphertext = cipher.doFinal(plaintext))
        } finally {
            key.fill(0)
        }
    }

    fun unwrap(wrapped: WrappedBytes): ByteArray {
        val key = KeyVault.key(keyAlias, create = false)
            ?: throw IllegalStateException("Key '$keyAlias' is missing; cannot unwrap")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
            cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(wrapped.ciphertext)
        } finally {
            key.fill(0)
        }
    }

    fun clear() {
        KeyVault.delete(keyAlias)
    }

    companion object {
        const val DEFAULT_ALIAS = "niix_storage_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private val random = SecureRandom()
    }
}

internal class WrappedBytes(val iv: ByteArray, val ciphertext: ByteArray) {
    fun serialize(): ByteArray {
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
        return out
    }

    companion object {
        fun deserialize(bytes: ByteArray): WrappedBytes {
            require(bytes.isNotEmpty()) { "Empty wrapped blob" }
            val ivLen = bytes[0].toInt() and 0xFF
            require(bytes.size > 1 + ivLen) { "Corrupt wrapped blob" }
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
            return WrappedBytes(iv, ciphertext)
        }
    }
}
