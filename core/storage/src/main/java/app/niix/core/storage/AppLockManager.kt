package app.niix.core.storage

import java.nio.CharBuffer
import java.security.SecureRandom

enum class UnlockResult {
    SUCCESS,
    FAILED,
    DURESS,
    /** A wrong guess was made while the local rate limiter's cooldown is active -- see
     * [AppLockManager.unlock]. Distinct from [FAILED] so a UI that wants to (e.g.
     * PasscodeActivity) can show a "try again in X" message instead of a generic "wrong code"
     * one; a UI that wants to stay indistinguishable from a wrong guess either way (e.g.
     * CalculatorActivity's disguise) can just treat this the same as [FAILED]. */
    THROTTLED,
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

        // Checked first and unconditionally, before the rate limiter below ever gets a say: a
        // coerced unlock must always work immediately, no matter how many recent wrong guesses
        // there were. The limiter below exists to slow down someone who doesn't know the real
        // passcode or the duress code -- it must never be able to block the one escape hatch
        // this feature exists for.
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

    /** Milliseconds until the next unlock attempt is allowed, or 0 if allowed right now. Safe to
     * call before the person has typed anything (e.g. to disable a submit button or show a
     * countdown proactively) -- this never derives a key or touches the database, just reads the
     * small local failure counter. Never applies to the duress code itself, only to further
     * wrong guesses -- see [unlock]. */
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

    /** [FREE_ATTEMPTS] wrong guesses cost nothing (typos happen); every guess after that costs
     * exponentially longer, doubling from [BASE_DELAY_MILLIS] and capped at [MAX_DELAY_MILLIS] --
     * the same shape as the escalating lockouts iOS/Android already use for their own PIN
     * screens. `step` is capped before the shift purely as an overflow guard; the
     * [MAX_DELAY_MILLIS] coercion below is what actually bounds the real-world delay. */
    private fun backoffDelayMillis(failCount: Int): Long {
        if (failCount < FREE_ATTEMPTS) return 0
        val step = (failCount - FREE_ATTEMPTS).coerceAtMost(20)
        val delay = BASE_DELAY_MILLIS shl step
        return delay.coerceIn(0, MAX_DELAY_MILLIS)
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
        private const val FREE_ATTEMPTS = 4
        private const val BASE_DELAY_MILLIS = 30_000L // 30s
        private const val MAX_DELAY_MILLIS = 60L * 60 * 1000 // capped at 1 hour
    }
}
