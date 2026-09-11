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
    /**
     * Marks that the calculator disguise has been explicitly turned on.
     *
     * Inverted from the previous "disabled" marker so the disguise is opt-in rather than opt-out.
     * A messenger that presents itself as a calculator by default surprises people who never
     * asked for it -- and the disguise only means anything alongside a passcode, which is also
     * opt-in. Anyone who wants it turns it on deliberately, which is the same moment they are
     * prompted to set a passcode.
     */
    private val disguiseEnabledFile = File(dir, DISGUISE_ENABLED_FILENAME)
    private val throttleFile = File(dir, THROTTLE_STATE_FILENAME)

    fun isInitialized(): Boolean = secretFile.exists()

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
    fun writePasscodeSalt(salt: ByteArray) = writeAtomically(saltFile, salt)

    fun isDuressSet(): Boolean = duressSaltFile.exists() && duressVerifierFile.exists()

    fun duressSalt(): ByteArray? = if (duressSaltFile.exists()) duressSaltFile.readBytes() else null

    fun duressVerifier(): ByteArray? = if (duressVerifierFile.exists()) duressVerifierFile.readBytes() else null

    @Synchronized
    fun writeDuress(salt: ByteArray, verifier: ByteArray) {
        writeAtomically(duressSaltFile, salt)
        writeAtomically(duressVerifierFile, verifier)
    }

    @Synchronized
    fun clearPasscodeAndDuress() {
        saltFile.delete()
        duressSaltFile.delete()
        duressVerifierFile.delete()
    }

    fun isDisguiseEnabled(): Boolean = disguiseEnabledFile.exists()

    @Synchronized
    fun setDisguiseEnabled(enabled: Boolean) {
        if (enabled) writeAtomically(disguiseEnabledFile, byteArrayOf(1)) else disguiseEnabledFile.delete()
    }

    @Synchronized
    fun clearAll() {
        keystore.clear()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

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
        /**
         * Deliberately a different filename from the old "disguise.off" marker.
         *
         * The meaning of the marker inverted: it used to record that the disguise was turned
         * off, and now records that it was turned on. Reusing the name would read every existing
         * install's "I disabled this" marker as "I enabled this" -- silently turning the app into
         * a calculator for exactly the people who had chosen not to have one. A new name means
         * existing installs simply have no marker, which is the correct new default.
         */
        private const val DISGUISE_ENABLED_FILENAME = "disguise.on"
        private const val THROTTLE_STATE_FILENAME = "unlock.throttle"
        private const val THROTTLE_STATE_BYTES = 12
        private const val STORAGE_SUBDIR = "niix-secure"
    }
}
