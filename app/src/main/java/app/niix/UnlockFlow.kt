package app.niix

import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UnlockFlow {

    suspend fun attempt(container: AppContainer, raw: String): Boolean {
        val outcome = withContext(Dispatchers.Default) {
            val pass = raw.toCharArray()
            try {

                container.lock()
                val result = container.storage.appLock.unlock(pass)
                if (result == UnlockResult.DURESS) {

                    container.wipeAllData()
                    runCatching {
                        container.storage.appLock.setPasscode(pass)
                        container.crypto.ensureKeysInitialized()
                        container.conversations.seedDecoyContent()
                    }

                    container.applyLauncherIcon()
                }
                if (result == UnlockResult.DURESS && !container.storage.appLock.isUnlocked()) {

                    UnlockResult.FAILED
                } else {
                    result
                }
            } finally {
                pass.fill('\u0000')
            }
        }
        return when (outcome) {
            UnlockResult.SUCCESS -> {
                container.lock.reset()
                // Anything that arrived while locked was held as ciphertext and can only be
                // processed now the database is open again.
                container.appScope.launch {
                    runCatching { container.conversations.drainLockedInbox() }
                }
                true
            }

            UnlockResult.DURESS -> {
                container.lock.reset()
                // Deliberately not drained. A duress unlock has just destroyed the real account;
                // processing messages that arrived for it would write them into the decoy, which
                // is both a data leak and a tell. wipeAllData clears the queue outright.
                true
            }

            UnlockResult.FAILED, UnlockResult.THROTTLED -> false
        }
    }

    fun throttleRemainingMillis(container: AppContainer): Long =
        container.storage.appLock.throttleRemainingMillis()
}
