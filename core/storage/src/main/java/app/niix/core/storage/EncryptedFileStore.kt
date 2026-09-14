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

    /**
     * Resolves a logical name to a file inside the store, and refuses anything that escapes it.
     *
     * Callers pass constants today, so nothing currently abuses this, but a name that reaches
     * here from anywhere else would be a path, and "../" would write outside the directory. The
     * canonical-path check is what makes that structurally impossible rather than merely
     * unlikely: it catches traversal regardless of how the string was constructed.
     */
    private fun resolve(name: String): File {
        require(name.isNotBlank()) { "Empty file store name" }
        require(!name.contains('/') && !name.contains('\\')) { "File store name must not contain a path" }
        require(name != "." && name != "..") { "Invalid file store name" }
        val file = File(baseDir, name)
        val base = baseDir.canonicalPath
        val resolved = file.canonicalPath
        require(resolved.startsWith("$base${File.separator}")) { "File store name escapes the store" }
        return file
    }

    fun putString(name: String, value: String) {
        resolve(name).outputStream().use { output ->
            encrypt(value.byteInputStream(Charsets.UTF_8), output)
        }
    }

    fun getString(name: String): String? {
        val file = resolve(name)
        if (!file.exists()) return null
        val out = java.io.ByteArrayOutputStream()
        file.inputStream().use { input -> decrypt(input, out) }
        return out.toString(Charsets.UTF_8.name())
    }

    fun delete(name: String) {
 // Same validation as the read and write paths: a traversal here would delete files
 // outside the store, which is if anything worse than writing them.
        runCatching { resolve(name).delete() }
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
