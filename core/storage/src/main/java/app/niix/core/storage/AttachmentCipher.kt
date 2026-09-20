package app.niix.core.storage

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

class AttachmentCipher {

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun encrypt(key: ByteArray, plaintext: InputStream, ciphertextOut: OutputStream, associatedData: ByteArray = EMPTY) {
        streaming(key).newEncryptingStream(ciphertextOut, associatedData).use { encrypting ->
            plaintext.copyTo(encrypting, BUFFER)
        }
    }

    fun decrypt(key: ByteArray, ciphertext: InputStream, plaintextOut: OutputStream, associatedData: ByteArray = EMPTY) {
        streaming(key).newDecryptingStream(ciphertext, associatedData).use { decrypting ->
            decrypting.copyTo(plaintextOut, BUFFER)
        }
    }

    fun encryptFile(key: ByteArray, source: File, destination: File, associatedData: ByteArray = EMPTY) {
        source.inputStream().use { input ->
            destination.outputStream().use { output -> encrypt(key, input, output, associatedData) }
        }
    }

    fun decryptFile(key: ByteArray, source: File, destination: File, associatedData: ByteArray = EMPTY) {
        source.inputStream().use { input ->
            destination.outputStream().use { output -> decrypt(key, input, output, associatedData) }
        }
    }

    private fun streaming(key: ByteArray): AesGcmHkdfStreaming =
        AesGcmHkdfStreaming(key, HKDF_ALGO, KEY_BYTES, SEGMENT_BYTES, 0)

    companion object {
        private const val KEY_BYTES = 32
        private const val SEGMENT_BYTES = 4096
        private const val BUFFER = 16 * 1024
        private const val HKDF_ALGO = "HmacSha256"
        private val EMPTY = ByteArray(0)
    }
}
