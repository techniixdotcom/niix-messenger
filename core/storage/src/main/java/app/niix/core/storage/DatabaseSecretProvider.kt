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
    private val previousSaltFile = File(dir, PREVIOUS_SALT_FILENAME)

    init {
        // Move files over from their old, self-describing names. Done once and quietly: a user with
        // a duress code mustn't lose it because the file moved, and the old name can't be left
        // lying around next to the new one.
        migrate(LEGACY_DURESS_SALT, duressSaltFile)
        migrate(LEGACY_DURESS_VERIFIER, duressVerifierFile)
        // Markers left by the old calculator disguise. Nothing reads them now.
        RETIRED_FILENAMES.forEach { runCatching { File(dir, it).delete() } }
    }

    private fun migrate(legacyName: String, target: File) {
        runCatching {
            val legacy = File(dir, legacyName)
            if (legacy.exists() && !target.exists()) legacy.renameTo(target)
            // If the rename failed, the old file is still readable, which is no worse than before.
            // Otherwise make sure the revealing name is gone.
            if (legacy.exists() && target.exists()) legacy.delete()
        }
    }
    private val throttleFile = File(dir, THROTTLE_STATE_FILENAME)
    private val deviceKeyFallbackFile = File(dir, DEVICE_KEY_FALLBACK_FILENAME)
    private val inactivityWipeFile = File(dir, INACTIVITY_WIPE_FILENAME)

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
    /**
     * Stores the new salt and keeps the previous one.
     *
     * A rekey is two steps: re-encrypt the database, then store the salt for its key. A crash in
     * between would leave the database and the salt out of step, and neither passcode would unlock.
     * With the previous salt kept, unlock can try both. Written before the rekey so the two always
     * cover both possible states.
     */
    fun writePasscodeSalt(salt: ByteArray) {
        if (saltFile.exists()) {
            runCatching { writeAtomically(previousSaltFile, saltFile.readBytes()) }
        }
        writeAtomically(saltFile, salt)
    }

    /** The salt from before the last rekey, if there was one. */
    fun previousPasscodeSalt(): ByteArray? =
        if (previousSaltFile.exists()) runCatching { previousSaltFile.readBytes() }.getOrNull() else null

    /** Called once a rekey has definitely finished, so a stale salt isn't tried forever. */
    fun clearPreviousPasscodeSalt() {
        runCatching { previousSaltFile.delete() }
    }

    /** Undoes [writePasscodeSalt] after a rekey that failed: the previous salt is current again. */
    @Synchronized
    fun rollBackPasscodeSalt() {
        val previous = previousPasscodeSalt() ?: return
        writeAtomically(saltFile, previous)
        previousSaltFile.delete()
    }

    /**
     * Set while the database is being moved between a passcode key and the no-passcode key, in
     * either direction. Turning a passcode on records it before re-encrypting, and turning it off
     * re-encrypts before forgetting it, so a crash in between leaves a database the passcode can't
     * open. While this is set, unlock also tries the no-passcode key.
     */
    fun markDeviceKeyFallback() = writeAtomically(deviceKeyFallbackFile, byteArrayOf(1))

    fun clearDeviceKeyFallback() {
        runCatching { deviceKeyFallbackFile.delete() }
    }

    fun hasDeviceKeyFallback(): Boolean = deviceKeyFallbackFile.exists()

    fun isDuressSet(): Boolean = duressSaltFile.exists() && duressVerifierFile.exists()

    fun duressSalt(): ByteArray? = if (duressSaltFile.exists()) duressSaltFile.readBytes() else null

    /**
     * The duress verifier, unwrapped with the keystore key. It's wrapped so that someone imaging
     * the filesystem can't tell it apart from any other opaque blob; whether a duress code exists
     * is one of the most sensitive facts here.
     */
    fun duressVerifier(): ByteArray? {
        if (!duressVerifierFile.exists()) return null
        return runCatching {
            keystore.unwrap(WrappedBytes.deserialize(duressVerifierFile.readBytes()))
        }.getOrElse {
            // Written by an older build before wrapping existed. Read it as-is so the duress code
            // keeps working; it gets wrapped the next time it's set.
            runCatching { duressVerifierFile.readBytes() }.getOrNull()
        }
    }

    @Synchronized
    fun writeDuress(salt: ByteArray, verifier: ByteArray) {
        writeAtomically(duressSaltFile, salt)
        writeAtomically(duressVerifierFile, keystore.wrap(verifier).serialize())
    }

    @Synchronized
    fun clearPasscodeAndDuress() {
        saltFile.delete()
        // The rekey fallback too. It derives a key for a database we're in the middle of throwing
        // away.
        previousSaltFile.delete()
        duressSaltFile.delete()
        duressVerifierFile.delete()
    }

    /**
     * The inactivity wipe setting and the time of the last unlock, or null when it is off.
     *
     * Kept here rather than in the database because it has to be checked while the app is locked,
     * when the database can't be opened. Keystore-wrapped under a name that says nothing, like the
     * duress files: that it's switched on is itself worth hiding.
     */
    fun readInactivityWipe(): Pair<Int, Long>? {
        if (!inactivityWipeFile.exists()) return null
        return runCatching {
            val text = String(keystore.unwrap(WrappedBytes.deserialize(inactivityWipeFile.readBytes())), Charsets.UTF_8)
            val days = text.substringBefore(',').toInt()
            val last = text.substringAfter(',').toLong()
            if (days > 0) days to last else null
        }.getOrNull()
    }

    @Synchronized
    fun writeInactivityWipe(days: Int, lastUnlockMillis: Long) {
        writeAtomically(inactivityWipeFile, keystore.wrap("$days,$lastUnlockMillis".toByteArray(Charsets.UTF_8)).serialize())
    }

    @Synchronized
    fun clearInactivityWipe() {
        inactivityWipeFile.delete()
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
        // A wipe deletes the folder itself, and the next account is set up in the same process: a
        // duress unlock straight into the decoy, or setting up again after the wipe button.
        // Without this every write after a wipe failed, and so did both of those.
        target.parentFile?.mkdirs()
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
        /*
         * Opaque file names. "duress.verifier" would tell someone searching the phone exactly what
         * they'd found, and a duress code is the first thing a coercive search looks for. Anyone reading this source knows what these are, which is fine since the design is
         * public, but on a seized phone you'd have to know to look.
         */
        private const val DURESS_SALT_FILENAME = "k2.bin"
        private const val DURESS_VERIFIER_FILENAME = "k3.bin"

        /** Old names, migrated on first use so existing installs keep working. */
        private const val LEGACY_DURESS_SALT = "duress.salt"
        private const val LEGACY_DURESS_VERIFIER = "duress.verifier"
        private val RETIRED_FILENAMES = listOf("disguise.on", "disguise.off", "p1.bin")
        private const val PREVIOUS_SALT_FILENAME = "passcode.salt.prev"
        private const val DEVICE_KEY_FALLBACK_FILENAME = "k4.bin"
        /** The same name 1.0.1 used, so a setting made there carries over. */
        private const val INACTIVITY_WIPE_FILENAME = "p2.bin"
        private const val THROTTLE_STATE_FILENAME = "unlock.throttle"
        private const val THROTTLE_STATE_BYTES = 12
        private const val STORAGE_SUBDIR = "niix-secure"
    }
}
