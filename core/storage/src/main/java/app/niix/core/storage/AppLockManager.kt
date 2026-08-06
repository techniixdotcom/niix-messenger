package app.niix.core.storage

import java.nio.CharBuffer
import java.security.SecureRandom

enum class UnlockResult {
    SUCCESS,
    FAILED,
    DURESS,
}

class AppLockManager internal constructor(
    private val secureDatabase: SecureDatabase,
    private val secretProvider: DatabaseSecretProvider,
) {

    /** Has this device ever completed setup at all. True forever after onboarding, independent
     * of whether passcode protection is currently on or off. */
    fun isSetUp(): Boolean = secretProvider.isInitialized()

    /** Is a passcode currently required to open the database. Toggleable via [enablePasscode] /
     * [disablePasscode] on an already-set-up account. */
    fun isPasscodeEnabled(): Boolean = secretProvider.isPasscodeConfigured()

    fun isUnlocked(): Boolean = secureDatabase.isOpen()

    fun isDuressSet(): Boolean = secretProvider.isDuressSet()

    fun isDisguiseEnabled(): Boolean = secretProvider.isDisguiseEnabled()

    fun setDisguiseEnabled(enabled: Boolean) = secretProvider.setDisguiseEnabled(enabled)

    /** First-time setup with a passcode (the original onboarding flow, unchanged). */
    fun setPasscode(passcode: CharArray): Boolean {
        if (isSetUp()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val salt = secretProvider.passcodeSalt()
        val dbKey = deriveDbKey(passcode, salt, deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            true
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /** First-time setup with no passcode: creates the account keyed by the device secret alone. */
    fun setUpWithoutPasscode(): Boolean {
        if (isSetUp()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val dbKey = PassphraseKdf.deviceOnlyKey(deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            true
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun setDuressPasscode(passcode: CharArray): Boolean {
        if (!isPasscodeEnabled()) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val verifier = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            secretProvider.writeDuress(salt, verifier)
            verifier.fill(0)
            true
        } finally {
            passcodeBytes.fill(0)
        }
    }

    fun unlock(passcode: CharArray): UnlockResult {
        if (!isPasscodeEnabled()) return UnlockResult.FAILED
        if (isUnlocked()) return UnlockResult.SUCCESS
        if (matchesDuress(passcode)) return UnlockResult.DURESS
        val deviceSecret = secretProvider.deviceSecret()
        val salt = secretProvider.passcodeSalt()
        val dbKey = deriveDbKey(passcode, salt, deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /** Opens an already-set-up, passcode-disabled database with no user input needed. */
    fun unlockWithoutPasscode(): UnlockResult {
        if (!isSetUp() || isPasscodeEnabled()) return UnlockResult.FAILED
        if (isUnlocked()) return UnlockResult.SUCCESS
        val deviceSecret = secretProvider.deviceSecret()
        val dbKey = PassphraseKdf.deviceOnlyKey(deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun changePasscode(current: CharArray, next: CharArray): Boolean {
        if (unlock(current) != UnlockResult.SUCCESS) return false
        val deviceSecret = secretProvider.deviceSecret()
        val newSalt = secretProvider.rotatePasscodeSalt()
        val newKey = deriveDbKey(next, newSalt, deviceSecret)
        return try {
            secureDatabase.changePassphrase(newKey)
            true
        } finally {
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /**
     * Turns off passcode protection on an already-unlocked, already-set-up account: re-keys the
     * live database to a device-secret-only key, and only once that re-key has actually
     * succeeded, removes the passcode and duress records. If the re-key fails for any reason,
     * nothing is changed -- the database keeps working with the passcode exactly as before.
     */
    fun disablePasscode(): Boolean {
        if (!isUnlocked()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val newKey = PassphraseKdf.deviceOnlyKey(deviceSecret)
        return try {
            secureDatabase.rekeyWithBackup(newKey)
            secretProvider.clearPasscodeAndDuress()
            true
        } catch (_: Exception) {
            false
        } finally {
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /**
     * Turns on passcode protection on an already-unlocked, device-only-keyed account: re-keys
     * the live database to a fresh passcode-derived key, and only once that succeeds, persists
     * the salt that key depends on -- so the persisted "passcode enabled" state and the
     * database's actual key can never disagree with each other.
     */
    fun enablePasscode(passcode: CharArray): Boolean {
        if (!isUnlocked()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val candidateSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newKey = deriveDbKey(passcode, candidateSalt, deviceSecret)
        return try {
            secureDatabase.rekeyWithBackup(newKey)
            secretProvider.writePasscodeSalt(candidateSalt)
            true
        } catch (_: Exception) {
            false
        } finally {
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun lock() {
        secureDatabase.close()
    }

    private fun matchesDuress(passcode: CharArray): Boolean {
        if (!secretProvider.isDuressSet()) return false
        val salt = secretProvider.duressSalt() ?: return false
        val expected = secretProvider.duressVerifier() ?: return false
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val candidate = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            val equal = constantTimeEquals(candidate, expected)
            candidate.fill(0)
            equal
        } finally {
            passcodeBytes.fill(0)
        }
    }

    private fun deriveDbKey(passcode: CharArray, salt: ByteArray, deviceSecret: ByteArray): ByteArray {
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val passcodeKey = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            try {
                PassphraseKdf.combine(deviceSecret, passcodeKey)
            } finally {
                passcodeKey.fill(0)
            }
        } finally {
            passcodeBytes.fill(0)
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    companion object {
        private const val SALT_BYTES = 16
    }
}
