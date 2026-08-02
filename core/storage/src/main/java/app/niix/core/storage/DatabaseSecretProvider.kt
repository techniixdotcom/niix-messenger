package app.niix.core.storage

import android.content.Context
import java.io.File
import java.security.SecureRandom

internal class DatabaseSecretProvider(
    context: Context,
    private val keystore: KeystoreKeyManager = KeystoreKeyManager(),
) {

    private val dir: File = File(context.noBackupFilesDir, STORAGE_SUBDIR).apply { mkdirs() }
    private val secretFile = File(dir, WRAPPED_SECRET_FILENAME)
    private val saltFile = File(dir, PASSCODE_SALT_FILENAME)
    private val duressSaltFile = File(dir, DURESS_SALT_FILENAME)
    private val duressVerifierFile = File(dir, DURESS_VERIFIER_FILENAME)

    fun isInitialized(): Boolean = secretFile.exists() && saltFile.exists()

    @Synchronized
    fun deviceSecret(): ByteArray {
        if (secretFile.exists()) {
            return keystore.unwrap(WrappedBytes.deserialize(secretFile.readBytes()))
        }
        val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        writeAtomically(secretFile, keystore.wrap(secret).serialize())
        return secret
    }

    @Synchronized
    fun passcodeSalt(): ByteArray {
        if (saltFile.exists()) return saltFile.readBytes()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        writeAtomically(saltFile, salt)
        return salt
    }

    @Synchronized
    fun rotatePasscodeSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        writeAtomically(saltFile, salt)
        return salt
    }

    fun isDuressSet(): Boolean = duressSaltFile.exists() && duressVerifierFile.exists()

    fun duressSalt(): ByteArray? = if (duressSaltFile.exists()) duressSaltFile.readBytes() else null

    fun duressVerifier(): ByteArray? = if (duressVerifierFile.exists()) duressVerifierFile.readBytes() else null

    @Synchronized
    fun writeDuress(salt: ByteArray, verifier: ByteArray) {
        writeAtomically(duressSaltFile, salt)
        writeAtomically(duressVerifierFile, verifier)
    }

    @Synchronized
    fun clearAll() {
        keystore.clear()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { it.write(data); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Unable to persist secret material" }
        }
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SALT_BYTES = 16
        private const val WRAPPED_SECRET_FILENAME = "db.secret"
        private const val PASSCODE_SALT_FILENAME = "passcode.salt"
        private const val DURESS_SALT_FILENAME = "duress.salt"
        private const val DURESS_VERIFIER_FILENAME = "duress.verifier"
        private const val STORAGE_SUBDIR = "niix-secure"
    }
}
