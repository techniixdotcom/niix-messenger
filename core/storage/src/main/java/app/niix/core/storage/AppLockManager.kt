package app.niix.core.storage

import app.niix.core.model.DiagnosticLog
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

enum class UnlockResult {
    SUCCESS,
    FAILED,
    DURESS,

    THROTTLED,
}

class AppLockManager internal constructor(
    private val secureDatabase: SecureDatabase,
    private val secretProvider: DatabaseSecretProvider,
    private val inactivityClock: InactivityClock,
) {

    private fun meetsPolicy(passcode: CharArray): Boolean = passcode.size >= MIN_PASSCODE_LENGTH

    fun isSetUp(): Boolean = secretProvider.isInitialized()

    fun isPasscodeEnabled(): Boolean = secretProvider.isPasscodeConfigured()

    fun isUnlocked(): Boolean = secureDatabase.isOpen()

    fun isDuressSet(): Boolean = secretProvider.isDuressSet()

    /**
     * Inactivity wipe: erase everything if the app hasn't been unlocked for this many days, or 0
     * when it's off.
     *
     * Remote wipe fails in exactly the case a careful adversary creates, a phone kept offline. This
     * works without the network: it's checked when the lock screen opens and by the background
     * service, and counts from the last unlock, with or without a passcode. A duress unlock doesn't
     * count.
     *
     * Time is counted by [InactivityClock], so winding the phone's clock back doesn't delay it. The
     * one thing it can't count is time the phone spends switched off with its clock wound back.
     */
    fun inactivityWipeDays(): Int = secretProvider.readInactivityWipe()?.days ?: 0

    /** 0 turns it off. Turning it on starts the countdown now, not from the last unlock. */
    fun setInactivityWipeDays(days: Int) {
        if (days <= 0) {
            secretProvider.clearInactivityWipe()
            return
        }
        val mark = inactivityClock.advance(secretProvider.readInactivityWipe()?.mark)
        secretProvider.writeInactivityWipe(DatabaseSecretProvider.InactivityWipe(days, mark.time, mark))
    }

    /**
     * Whether the time is up. Also saves how far the clock got, so a reboot or a clock wound back
     * later can't lose what's been counted so far.
     */
    fun inactivityWipeDue(): Boolean {
        val wipe = secretProvider.readInactivityWipe() ?: return false
        val mark = inactivityClock.advance(wipe.mark)
        runCatching { secretProvider.writeInactivityWipe(wipe.copy(mark = mark)) }
        return mark.time - wipe.lastUse > wipe.days * DAY_MILLIS
    }

    /**
     * Restarts the inactivity countdown while the app is being used. Written at most once an hour,
     * since this runs every time a screen opens.
     */
    fun recordUse() = restartCountdown(onlyIfStale = true)

    /** Restarts the inactivity countdown after an unlock. */
    private fun checkedIn(result: UnlockResult): UnlockResult {
        if (result == UnlockResult.SUCCESS) restartCountdown(onlyIfStale = false)
        return result
    }

    private fun restartCountdown(onlyIfStale: Boolean) {
        runCatching {
            val wipe = secretProvider.readInactivityWipe() ?: return
            val mark = inactivityClock.advance(wipe.mark)
            if (!onlyIfStale || mark.time - wipe.lastUse >= USE_RECORD_INTERVAL_MILLIS) {
                secretProvider.writeInactivityWipe(wipe.copy(lastUse = mark.time, mark = mark))
            }
        }
    }

    fun setPasscode(passcode: CharArray): Boolean {
        if (isSetUp()) return false
        if (!meetsPolicy(passcode)) return false
        val (dbKey, deviceSecret) = deriveDbKeyAndSecret(passcode, secretProvider.passcodeSalt())
        return try {
            secureDatabase.openWith(dbKey)
            true
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /** First-time setup for someone who chose not to have a passcode. Mirrors [setPasscode]. */
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
        // The duress code matters as much as the real one (knowing it lets someone destroy the
        // data), so it has the same minimum.
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

    fun unlock(passcode: CharArray): UnlockResult = checkedIn(tryUnlock(passcode))

    private fun tryUnlock(passcode: CharArray): UnlockResult {
        if (!isPasscodeEnabled()) return UnlockResult.FAILED
        if (isUnlocked()) return UnlockResult.SUCCESS

        // Throttle before testing anything, including the duress code. Otherwise duress codes could
        // be guessed at full speed, even by someone already throttled out of the real passcode.
        val now = System.currentTimeMillis()
        if (throttleRemainingMillis(now) > 0) return UnlockResult.THROTTLED

        // Counted before anything is tested and cleared on success. Counting after a failure left a
        // second or more in which killing the app made a wrong guess free. Free guesses for the
        // h4x0r holding your phone = bad.
        val before = secretProvider.readThrottleState()
        recordFailedAttempt(now)

        // The real passcode goes first, so a normal unlock costs one Argon2 run instead of two. A
        // wrong code and the duress code both pay for two, so timing can't tell them apart. If the
        // duress code is the same as the real passcode, the real one wins.
        val started = System.nanoTime()
        val (dbKey, deviceSecret) = try {
            deriveDbKeyAndSecret(passcode, secretProvider.passcodeSalt())
        } catch (t: Throwable) {
            // Out of memory, or the keystore refusing for a moment: the passcode was never tested,
            // so the attempt doesn't count.
            secretProvider.writeThrottleState(before.first, before.second)
            throw t
        }
        val derived = System.nanoTime()
        return try {
            secureDatabase.openWith(dbKey)
            // Opened with the current salt, so the last rekey finished and the old salt isn't
            // needed any more.
            secretProvider.clearPreviousPasscodeSalt()
            secretProvider.clearThrottleState()
            lastFailureElapsed = null
            DiagnosticLog.record(
                "unlock",
                "key ${(derived - started) / 1_000_000} ms (Argon2 ${PassphraseKdf.implementation}), " +
                    "database ${(System.nanoTime() - derived) / 1_000_000} ms",
            )
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            if (matchesDuress(passcode)) {
                secretProvider.clearThrottleState()
                lastFailureElapsed = null
                return UnlockResult.DURESS
            }
            // Try the salt from before the last passcode change. If a crash hit between writing the
            // salt and re-encrypting the database, the right passcode would otherwise fail. It's
            // not a weaker check: the passcode still has to be correct and the key still has to
            // open the database.
            val previous = secretProvider.previousPasscodeSalt()
            if (previous != null) {
                val fallbackKey = deriveDbKey(passcode, previous, deviceSecret)
                try {
                    secureDatabase.openWith(fallbackKey)
                    // Opened with the old salt, so the rekey didn't finish. Put the working salt
                    // back so the next unlock is normal.
                    secretProvider.writePasscodeSalt(previous)
                    secretProvider.clearPreviousPasscodeSalt()
                    secretProvider.clearThrottleState()
                    lastFailureElapsed = null
                    return UnlockResult.SUCCESS
                } catch (_: Exception) {
                    // Wrong passcode; fall through to the failure path.
                } finally {
                    fallbackKey.fill(0)
                }
            }
            if (secretProvider.hasDeviceKeyFallback() && openWithDeviceKey(deviceSecret)) return UnlockResult.SUCCESS
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /**
     * How much of the throttle is left.
     *
     * Someone holding the phone can wind the wall clock back, so elapsed realtime is checked as
     * well, since it can't be set. But elapsed realtime resets on reboot, so wall-clock time is
     * still needed. The longer of the two wins.
     */
    fun throttleRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        val (failCount, lastFailureAtMillis) = secretProvider.readThrottleState()
        if (failCount < FREE_ATTEMPTS) return 0
        val backoff = backoffDelayMillis(failCount)

        val byWallClock = (lastFailureAtMillis + backoff - now).coerceAtLeast(0)

        // Only meaningful if the device hasn't rebooted since the failure.
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

    fun unlockWithoutPasscode(): UnlockResult = checkedIn(tryUnlockWithoutPasscode())

    private fun tryUnlockWithoutPasscode(): UnlockResult {
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

    fun disablePasscode(): Boolean {
        if (!isUnlocked()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val newKey = PassphraseKdf.deviceOnlyKey(deviceSecret)
        return try {
            // Rekey, then forget the passcode. A crash in between is covered by the fallback.
            secretProvider.markDeviceKeyFallback()
            secureDatabase.rekeyWithBackup(newKey)
            secretProvider.clearPasscodeAndDuress()
            true
        } catch (_: Exception) {
            false
        } finally {
            secretProvider.clearDeviceKeyFallback()
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /** Sets a passcode, or changes it. */
    fun enablePasscode(passcode: CharArray): Boolean {
        if (!isUnlocked()) return false
        if (!meetsPolicy(passcode)) return false
        val hadPasscode = isPasscodeEnabled()
        val deviceSecret = secretProvider.deviceSecret()
        val candidateSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newKey = deriveDbKey(passcode, candidateSalt, deviceSecret)
        return try {
            // Salt first, then rekey. A crash in between is covered in unlock: by the previous salt
            // when changing a passcode, by the no-passcode fallback when setting the first one.
            if (!hadPasscode) secretProvider.markDeviceKeyFallback()
            secretProvider.writePasscodeSalt(candidateSalt)
            secureDatabase.rekeyWithBackup(newKey)
            true
        } catch (_: Exception) {
            // The rekey put the database back under its old key, so the old state comes back too.
            // Left as it was, the app would ask for a passcode nothing could open.
            if (hadPasscode) secretProvider.rollBackPasscodeSalt() else secretProvider.clearPasscodeAndDuress()
            false
        } finally {
            if (!hadPasscode) secretProvider.clearDeviceKeyFallback()
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    /**
     * For a database left under the no-passcode key by a crash while a passcode was being set or
     * removed. Opening it means there was never a working passcode on it, so the passcode files
     * go and the state matches the database again.
     */
    private fun openWithDeviceKey(deviceSecret: ByteArray): Boolean {
        val key = PassphraseKdf.deviceOnlyKey(deviceSecret)
        return try {
            secureDatabase.openWith(key)
            secretProvider.clearPasscodeAndDuress()
            secretProvider.clearDeviceKeyFallback()
            secretProvider.clearThrottleState()
            lastFailureElapsed = null
            DiagnosticLog.record("unlock", "recovered from an interrupted passcode change: the database has no passcode")
            true
        } catch (_: Exception) {
            false
        } finally {
            key.fill(0)
        }
    }

    fun lock() {
        secureDatabase.close()
        // Locking is when we stop holding anything that describes what the user was doing. The
        // diagnostic log is in memory only but would otherwise survive behind the lock screen.
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

    /**
     * The database key, and the device secret for the fallback that may need it again.
     *
     * Argon2 and the keystore unwrap don't depend on each other, so the unwrap runs on a second
     * thread meanwhile. It's slow on phones that keep keys in a separate secure chip (StrongBox),
     * and this way it costs nothing extra.
     */
    private fun deriveDbKeyAndSecret(passcode: CharArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val secretTask = FutureTask(secretProvider::deviceSecret)
        Thread(secretTask, "niix-unlock-keystore").start()
        val passcodeKey = try {
            passcodeKey(passcode, salt)
        } catch (t: Throwable) {
            runCatching { secretTask.get().fill(0) }
            throw t
        }
        val deviceSecret = try {
            secretTask.get()
        } catch (e: ExecutionException) {
            passcodeKey.fill(0)
            throw e.cause ?: e
        }
        return try {
            PassphraseKdf.combine(deviceSecret, passcodeKey) to deviceSecret
        } finally {
            passcodeKey.fill(0)
        }
    }

    private fun passcodeKey(passcode: CharArray, salt: ByteArray): ByteArray {
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
        } finally {
            passcodeBytes.fill(0)
        }
    }

    private fun deriveDbKey(passcode: CharArray, salt: ByteArray, deviceSecret: ByteArray): ByteArray {
        val passcodeKey = passcodeKey(passcode, salt)
        return try {
            PassphraseKdf.combine(deviceSecret, passcodeKey)
        } finally {
            passcodeKey.fill(0)
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
        /**
         * For new passcodes, duress codes included. Matches what the UI asks for, and lives here
         * because this is where it's enforced. Unlocking doesn't check it, so a 6-digit passcode
         * set before it was raised keeps working.
         */
        const val MIN_PASSCODE_LENGTH = 8

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val USE_RECORD_INTERVAL_MILLIS = 60L * 60 * 1000

        private const val SALT_BYTES = 16
        private const val FREE_ATTEMPTS = 4
        private const val BASE_DELAY_MILLIS = 30_000L
        private const val MAX_DELAY_MILLIS = 60L * 60 * 1000
    }
}
