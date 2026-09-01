package app.niix.core.storage

import java.nio.CharBuffer
import java.security.SecureRandom

enum class UnlockResult {
    SUCCESS,
    FAILED,
    DURESS,

    THROTTLED,
}

class AppLockManager internal constructor(
    private val secureDatabase: SecureDatabase,
    private val secretProvider: DatabaseSecretProvider,
) {

    fun isSetUp(): Boolean = secretProvider.isInitialized()

    fun isPasscodeEnabled(): Boolean = secretProvider.isPasscodeConfigured()

    fun isUnlocked(): Boolean = secureDatabase.isOpen()

    fun isDuressSet(): Boolean = secretProvider.isDuressSet()

    fun isDisguiseEnabled(): Boolean = secretProvider.isDisguiseEnabled()

    fun setDisguiseEnabled(enabled: Boolean) = secretProvider.setDisguiseEnabled(enabled)

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

        if (matchesDuress(passcode)) {
            secretProvider.clearThrottleState()
            return UnlockResult.DURESS
        }

        val now = System.currentTimeMillis()
        if (throttleRemainingMillis(now) > 0) return UnlockResult.THROTTLED

        val deviceSecret = secretProvider.deviceSecret()
        val salt = secretProvider.passcodeSalt()
        val dbKey = deriveDbKey(passcode, salt, deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            secretProvider.clearThrottleState()
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            recordFailedAttempt(now)
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun throttleRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        val (failCount, lastFailureAtMillis) = secretProvider.readThrottleState()
        if (failCount < FREE_ATTEMPTS) return 0
        val allowedAt = lastFailureAtMillis + backoffDelayMillis(failCount)
        return (allowedAt - now).coerceAtLeast(0)
    }

    private fun recordFailedAttempt(now: Long) {
        val (failCount, _) = secretProvider.readThrottleState()
        secretProvider.writeThrottleState(failCount + 1, now)
    }

    private fun backoffDelayMillis(failCount: Int): Long {
        if (failCount < FREE_ATTEMPTS) return 0
        val step = (failCount - FREE_ATTEMPTS).coerceAtMost(20)
        val delay = BASE_DELAY_MILLIS shl step
        return delay.coerceIn(0, MAX_DELAY_MILLIS)
    }

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
        val candidateSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newKey = deriveDbKey(next, candidateSalt, deviceSecret)
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
        // Locking is the point at which the device should stop holding anything that describes
        // what the user was doing. The diagnostic buffer is in-memory only, but it survives
        // until explicitly cleared, so clear it here rather than leaving it readable behind a
        // lock screen.
        app.niix.core.model.DiagnosticLog.clear()
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
        private const val FREE_ATTEMPTS = 4
        private const val BASE_DELAY_MILLIS = 30_000L
        private const val MAX_DELAY_MILLIS = 60L * 60 * 1000
    }
}
