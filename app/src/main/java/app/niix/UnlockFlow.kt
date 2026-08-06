package app.niix

import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single place that decides what a passcode/duress attempt does: verify it, and on a
 * duress match, wipe and rebuild a seeded decoy identity keyed by the same code. Both
 * CalculatorActivity (disguised entry) and PasscodeActivity (plain entry, used when the
 * disguise is turned off) call this so the two can never quietly drift apart.
 */
object UnlockFlow {

    /** Attempts [raw] as a passcode. Returns true if the app should navigate to Home now
     * (a real success, or a duress match that opened a decoy) -- false if nothing matched and
     * the caller should stay on its lock screen. */
    suspend fun attempt(container: AppContainer, raw: String): Boolean {
        val outcome = withContext(Dispatchers.Default) {
            val pass = raw.toCharArray()
            try {
                // Force a real verification: never trust an already-open database.
                container.storage.appLock.lock()
                val result = container.storage.appLock.unlock(pass)
                if (result == UnlockResult.DURESS) {
                    // Panic wipe: destroy everything, then rebuild a decoy identity keyed by
                    // the same code just entered, seeded with plausible fake conversations,
                    // and open into it -- so a coerced unlock looks like it genuinely worked.
                    container.storage.wipeAllData()
                    runCatching {
                        container.storage.appLock.setPasscode(pass)
                        container.crypto.ensureKeysInitialized()
                        container.conversations.seedDecoyContent()
                    }
                    // A wipe resets the disguise flag to its default (enabled); make sure the
                    // actual launcher icon follows that reset rather than being left showing
                    // whatever was active before the wipe.
                    LauncherAlias.apply(container.context, container.storage.appLock.isDisguiseEnabled())
                }
                if (result == UnlockResult.DURESS && !container.storage.appLock.isUnlocked()) {
                    // Decoy setup didn't complete; fail safe rather than open onto a broken screen.
                    UnlockResult.FAILED
                } else {
                    result
                }
            } finally {
                pass.fill('\u0000')
            }
        }
        return when (outcome) {
            UnlockResult.SUCCESS, UnlockResult.DURESS -> {
                container.lock.reset()
                true
            }
            UnlockResult.FAILED -> false
        }
    }
}
