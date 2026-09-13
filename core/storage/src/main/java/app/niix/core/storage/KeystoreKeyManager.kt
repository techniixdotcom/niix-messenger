package app.niix.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class KeystoreKeyManager(
    private val keyAlias: String = DEFAULT_ALIAS,
) {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun wrap(plaintext: ByteArray): WrappedBytes {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return WrappedBytes(iv = iv, ciphertext = ciphertext)
    }

    fun unwrap(wrapped: WrappedBytes): ByteArray {
        val key = existingKey()
            ?: throw IllegalStateException("Keystore key '$keyAlias' is missing; cannot unwrap")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: createKey()

    private fun existingKey(): SecretKey? =
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun createKey(): SecretKey {
        return try {
            generateKey(strongBox = supportsStrongBox())
        } catch (_: Exception) {
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                // Both require API 28; the app's minimum is 29, so they always apply.
                setUnlockedDeviceRequired(true)
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** StrongBox is an API 28 feature and the app requires 29, so the platform always exposes
     * it. Whether the hardware actually provides it is a separate question, answered by the
     * StrongBoxUnavailableException the caller already handles. */
    private fun supportsStrongBox(): Boolean = true

    fun clear() {
        runCatching {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val DEFAULT_ALIAS = "niix_storage_master"
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
