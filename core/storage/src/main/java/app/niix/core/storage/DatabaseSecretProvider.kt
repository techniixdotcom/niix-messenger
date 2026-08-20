package app.niix.core.storage

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
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
    private val disguiseDisabledFile = File(dir, DISGUISE_DISABLED_FILENAME)
    private val throttleFile = File(dir, THROTTLE_STATE_FILENAME)

    /** Has this device ever completed setup at all (device secret exists). True forever after
     * onboarding, regardless of whether passcode protection is currently on or off. */
    fun isInitialized(): Boolean = secretFile.exists()

    /** Is a passcode currently the active way to open the database. Toggleable independently of
     * [isInitialized] via [writePasscodeSalt] / [clearPasscodeAndDuress]. */
    fun isPasscodeConfigured(): Boolean = saltFile.exists()

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

    /** Persists a passcode salt that was already used to key the database (see
     * [AppLockManager.enablePasscode]) -- written only after the re-key it belongs to has
     * already succeeded, so this file and the database's actual key never disagree. */
    @Synchronized
    fun writePasscodeSalt(salt: ByteArray) = writeAtomically(saltFile, salt)

    fun isDuressSet(): Boolean = duressSaltFile.exists() && duressVerifierFile.exists()

    fun duressSalt(): ByteArray? = if (duressSaltFile.exists()) duressSaltFile.readBytes() else null

    fun duressVerifier(): ByteArray? = if (duressVerifierFile.exists()) duressVerifierFile.readBytes() else null

    @Synchronized
    fun writeDuress(salt: ByteArray, verifier: ByteArray) {
        writeAtomically(duressSaltFile, salt)
        writeAtomically(duressVerifierFile, verifier)
    }

    /** Turns off passcode protection: removes the passcode salt and any duress code, since
     * duress has nothing left to gate once there's no passcode screen to type it into. Called
     * only after the database has already been re-keyed to a device-only key. */
    @Synchronized
    fun clearPasscodeAndDuress() {
        saltFile.delete()
        duressSaltFile.delete()
        duressVerifierFile.delete()
    }

    fun isDisguiseEnabled(): Boolean = !disguiseDisabledFile.exists()

    @Synchronized
    fun setDisguiseEnabled(enabled: Boolean) {
        if (enabled) disguiseDisabledFile.delete() else writeAtomically(disguiseDisabledFile, byteArrayOf(1))
    }

    @Synchronized
    fun clearAll() {
        keystore.clear()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** (failCount, lastFailureAtMillis) for the local unlock-attempt rate limiter -- see
     * [AppLockManager.unlock]. Defaults to (0, 0) if no failure has ever been recorded, same as
     * every other lazily-created file here. Deliberately plain, unencrypted local files (same as
     * the salts above): the failure count and timestamp aren't secret, and the whole point is
     * that they must be readable before the passcode is known, i.e. before there's any key to
     * encrypt them with. */
    @Synchronized
    fun readThrottleState(): Pair<Int, Long> {
        if (!throttleFile.exists()) return 0 to 0L
        val bytes = throttleFile.readBytes()
        if (bytes.size != THROTTLE_STATE_BYTES) return 0 to 0L
        val buffer = ByteBuffer.wrap(bytes)
        val failCount = buffer.int
        val lastFailureAtMillis = buffer.long
        return failCount to lastFailureAtMillis
    }

    @Synchronized
    fun writeThrottleState(failCount: Int, lastFailureAtMillis: Long) {
        val buffer = ByteBuffer.allocate(THROTTLE_STATE_BYTES)
        buffer.putInt(failCount)
        buffer.putLong(lastFailureAtMillis)
        writeAtomically(throttleFile, buffer.array())
    }

    @Synchronized
    fun clearThrottleState() {
        throttleFile.delete()
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
        private const val DISGUISE_DISABLED_FILENAME = "disguise.off"
        private const val THROTTLE_STATE_FILENAME = "unlock.throttle"
        private const val THROTTLE_STATE_BYTES = 12 // 4-byte failCount + 8-byte lastFailureAtMillis
        private const val STORAGE_SUBDIR = "niix-secure"
    }
}
