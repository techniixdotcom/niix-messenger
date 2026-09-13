package app.niix.core.storage

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class EncryptedFileStore internal constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val baseDir: File = File(appContext.filesDir, FILES_SUBDIR).apply { mkdirs() }
    private val streamingAead: StreamingAead

    init {
        StreamingAeadConfig.register()
        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        streamingAead = handle.getPrimitive(StreamingAead::class.java)
    }

    fun encrypt(plaintext: InputStream, ciphertextOut: OutputStream, associatedData: ByteArray = EMPTY) {
        streamingAead.newEncryptingStream(ciphertextOut, associatedData).use { encrypting ->
            plaintext.copyTo(encrypting, BUFFER)
        }
    }

    fun decrypt(ciphertext: InputStream, plaintextOut: OutputStream, associatedData: ByteArray = EMPTY) {
        streamingAead.newDecryptingStream(ciphertext, associatedData).use { decrypting ->
            decrypting.copyTo(plaintextOut, BUFFER)
        }
    }

    fun encryptFile(source: File, destination: File, associatedData: ByteArray = EMPTY) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                encrypt(input, output, associatedData)
            }
        }
    }

    fun decryptFile(source: File, destination: File, associatedData: ByteArray = EMPTY) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                decrypt(input, output, associatedData)
            }
        }
    }

    fun putString(name: String, value: String) {
        File(baseDir, name).outputStream().use { output ->
            encrypt(value.byteInputStream(Charsets.UTF_8), output)
        }
    }

    fun getString(name: String): String? {
        val file = File(baseDir, name)
        if (!file.exists()) return null
        val out = java.io.ByteArrayOutputStream()
        file.inputStream().use { input -> decrypt(input, out) }
        return out.toString(Charsets.UTF_8.name())
    }

    fun delete(name: String) {
        File(baseDir, name).delete()
    }

    fun clear() {
        baseDir.deleteRecursively()
        appContext.deleteSharedPreferences(PREF_FILE)
    }

    companion object {
        private const val KEYSET_NAME = "niix_file_keyset"
        private const val PREF_FILE = "niix_file_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://niix_file_master"
        private const val KEY_TEMPLATE = "AES256_GCM_HKDF_1MB"
        private const val FILES_SUBDIR = "niix-files"
        private const val BUFFER = 16 * 1024
        private val EMPTY = ByteArray(0)
    }
}
