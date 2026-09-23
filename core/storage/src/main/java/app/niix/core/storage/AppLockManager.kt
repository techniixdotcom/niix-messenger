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

    private fun meetsPolicy(passcode: CharArray): Boolean = passcode.size >= MIN_PASSCODE_LENGTH

    fun isSetUp(): Boolean = secretProvider.isInitialized()

    fun isPasscodeEnabled(): Boolean = secretProvider.isPasscodeConfigured()

    fun isUnlocked(): Boolean = secureDatabase.isOpen()

    fun isDuressSet(): Boolean = secretProvider.isDuressSet()

    fun isDisguiseEnabled(): Boolean = secretProvider.isDisguiseEnabled()

    fun setDisguiseEnabled(enabled: Boolean) = secretProvider.setDisguiseEnabled(enabled)

    fun setPasscode(passcode: CharArray): Boolean {
        if (isSetUp()) return false
        if (!meetsPolicy(passcode)) return false
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
 // The duress code is a secret of the same weight as the real one, learning it lets
 // someone destroy the data, so it is held to the same minimum.
        if (!meetsPolicy(passcode)) return false
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

 // Throttle first, before testing any candidate.
 //
 // The duress check used to run ahead of this, which meant duress passcodes could be
 // guessed at unlimited rate, and that an attacker already throttled out of the real
 // passcode could still test duress candidates freely. Both defeat the point: the duress
 // code is a secret of exactly the same importance as the real one, and learning it lets
 // someone trigger a wipe.
        val now = System.currentTimeMillis()
        if (throttleRemainingMillis(now) > 0) return UnlockResult.THROTTLED

        if (matchesDuress(passcode)) {
            secretProvider.clearThrottleState()
            lastFailureElapsed = null
            return UnlockResult.DURESS
        }

        val deviceSecret = secretProvider.deviceSecret()
        val dbKey = deriveDbKey(passcode, secretProvider.passcodeSalt(), deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            // Opened under the current salt, so the rekey that produced it completed. The old
            // one is no longer a recovery path and keeping it around only widens what an
            // attacker could work with.
            secretProvider.clearPreviousPasscodeSalt()
            secretProvider.clearThrottleState()
            lastFailureElapsed = null
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            // Retry with the salt from before the last passcode change.
            //
            // A rekey writes the salt and re-encrypts the database as two steps. Crash between
            // them and the stored salt derives a key the database is not using, so the correct
            // passcode fails and the user is locked out of everything. Trying the previous salt
            // covers that gap. It is not a weaker check: the passcode still has to be right, and
            // the key still has to open the database.
            val previous = secretProvider.previousPasscodeSalt()
            if (previous != null) {
                val fallbackKey = deriveDbKey(passcode, previous, deviceSecret)
                try {
                    secureDatabase.openWith(fallbackKey)
                    // Opened under the old salt, so the rekey did not finish. Put the salt that
                    // actually works back in place, so the next unlock is a normal one.
                    secretProvider.writePasscodeSalt(previous)
                    secretProvider.clearPreviousPasscodeSalt()
                    secretProvider.clearThrottleState()
                    lastFailureElapsed = null
                    return UnlockResult.SUCCESS
                } catch (_: Exception) {
                    // Genuinely wrong passcode; fall through to the failure path below.
                } finally {
                    fallbackKey.fill(0)
                }
            }
            recordFailedAttempt(now)
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /**
     * How long the throttle still has to run.
     *
     * Wall-clock alone was defeatable by moving the device clock backwards, an attacker holding
     * the phone can change the system time freely, which reset the backoff and returned
     * brute-force protection to nothing. The elapsed-realtime clock cannot be set and does not
     * move backwards, so it is checked as well and the longer of the two wins.
     *
     * Neither alone is sufficient: monotonic time resets to zero on reboot, so it would forget a
     * throttle across a restart, and wall-clock is what survives that. Taking the maximum means
     * the throttle holds if *either* clock says it should.
     */
    fun throttleRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        val (failCount, lastFailureAtMillis) = secretProvider.readThrottleState()
        if (failCount < FREE_ATTEMPTS) return 0
        val backoff = backoffDelayMillis(failCount)

        val byWallClock = (lastFailureAtMillis + backoff - now).coerceAtLeast(0)

 // Only meaningful while the device has not rebooted since the failure was recorded.
        val monotonicRemaining = lastFailureElapsed?.let { recordedAt ->
            (recordedAt + backoff - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        } ?: 0L

        return maxOf(byWallClock, monotonicRemaining)
    }

    @Volatile
    private var lastFailureElapsed: Long? = null

    private fun recordFailedAttempt(now: Long) {
        lastFailureElapsed = android.os.SystemClock.elapsedRealtime()
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
        if (!meetsPolicy(next)) return false
        if (unlock(current) != UnlockResult.SUCCESS) return false
        val deviceSecret = secretProvider.deviceSecret()
        val candidateSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newKey = deriveDbKey(next, candidateSalt, deviceSecret)
        return try {
            // Salt first, then the rekey. Either order leaves a crash window; writing first
            // means the window is covered by the previous-salt fallback in unlock.
            secretProvider.writePasscodeSalt(candidateSalt)
            secureDatabase.rekeyWithBackup(newKey)
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
        if (!meetsPolicy(passcode)) return false
        val deviceSecret = secretProvider.deviceSecret()
        val candidateSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newKey = deriveDbKey(passcode, candidateSalt, deviceSecret)
        return try {
            // Salt first, then the rekey. Either order leaves a crash window; writing first
            // means the window is covered by the previous-salt fallback in unlock.
            secretProvider.writePasscodeSalt(candidateSalt)
            secureDatabase.rekeyWithBackup(newKey)
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
        /** Matches what the UI asks for. Defined here because this is where it is enforced. */
        const val MIN_PASSCODE_LENGTH = 6

        private const val SALT_BYTES = 16
        private const val FREE_ATTEMPTS = 4
        private const val BASE_DELAY_MILLIS = 30_000L
        private const val MAX_DELAY_MILLIS = 60L * 60 * 1000
    }
}
