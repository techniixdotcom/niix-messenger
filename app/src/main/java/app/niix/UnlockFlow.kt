package app.niix

import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UnlockFlow {

    suspend fun attempt(container: AppContainer, raw: String): Boolean {
        val outcome = withContext(Dispatchers.Default) {
            val pass = raw.toCharArray()
            try {

                container.storage.appLock.lock()
                val result = container.storage.appLock.unlock(pass)
                if (result == UnlockResult.DURESS) {

                    container.wipeAllData()
                    runCatching {
                        container.storage.appLock.setPasscode(pass)
                        container.crypto.ensureKeysInitialized()
                        container.conversations.seedDecoyContent()
                    }

                    LauncherAlias.apply(container.context, container.storage.appLock.isDisguiseEnabled())
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
            UnlockResult.SUCCESS, UnlockResult.DURESS -> {
                container.lock.reset()
                true
            }

            UnlockResult.FAILED, UnlockResult.THROTTLED -> false
        }
    }

    fun throttleRemainingMillis(container: AppContainer): Long =
        container.storage.appLock.throttleRemainingMillis()
}
