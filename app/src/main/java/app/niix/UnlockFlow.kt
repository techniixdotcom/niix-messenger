package app.niix

import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object UnlockFlow {

    // The lock screen only allows one attempt at a time, but a recreated screen is a new instance.
    // This keeps two attempts from ever deriving keys side by side.
    private val attempts = Mutex()

    suspend fun attempt(container: AppContainer, raw: String): Boolean {
        val outcome = attempts.withLock { withContext(Dispatchers.Default) { tryUnlock(container, raw) } }
        return when (outcome) {
            UnlockResult.SUCCESS -> {
                container.lock.reset()
                // Messages that arrived while locked were held as ciphertext; now the database is
                // open they can be processed.
                container.appScope.launch {
                    runCatching { container.conversations.drainLockedInbox() }
                }
                true
            }

            UnlockResult.DURESS -> {
                container.lock.reset()
                // Deliberately not drained. A duress unlock just destroyed the real account, and
                // writing its messages into the decoy would leak them and give the decoy away.
                // wipeAllData clears the queue.
                true
            }

            UnlockResult.FAILED, UnlockResult.THROTTLED -> false
        }
    }

    private suspend fun tryUnlock(container: AppContainer, raw: String): UnlockResult {
        val pass = raw.toCharArray()
        return try {
            val result = container.storage.appLock.unlock(pass)
            if (result == UnlockResult.DURESS) {
                // Wipe, then open a fresh account under the duress code with some ordinary-looking
                // chats in it, so it looks as if the app simply unlocked. A wipe that partly failed
                // still continues: showing "wrong passcode" here would give the game away.
                runCatching { container.wipeAllData(waitForOtherDevicesMillis = 0) }
                runCatching { container.storage.appLock.setPasscode(pass) }
                    .onFailure { DiagnosticLog.record("unlock", "decoy setup failed: ${it.javaClass.simpleName}") }
                runCatching {
                    container.crypto.ensureKeysInitialized()
                    container.conversations.seedDecoyContent(
                        container.context.resources.getStringArray(R.array.decoy_names).toList(),
                        container.context.resources.getStringArray(R.array.decoy_messages).toList(),
                    )
                }
            }
            // Before anything is shown: messages that expired while the database was closed couldn't
            // be deleted then, and shouldn't appear even for a moment now.
            if (result == UnlockResult.SUCCESS) container.expirySweeper.sweepOnce()
            if (result == UnlockResult.DURESS && !container.storage.appLock.isUnlocked()) UnlockResult.FAILED else result
        } catch (_: OutOfMemoryError) {
            // Argon2 needs about 64 MB. On a phone that's short of memory, report a failed
            // attempt instead of crashing; nothing was tested, so AppLockManager didn't count it.
            DiagnosticLog.record("unlock", "out of memory while deriving the key")
            UnlockResult.FAILED
        } catch (e: Exception) {
            // The keystore can refuse for a moment, just after boot for instance. Same again:
            // not a wrong passcode, so not counted, and not a crash.
            DiagnosticLog.record("unlock", e.javaClass.simpleName)
            UnlockResult.FAILED
        } finally {
            pass.fill('\u0000')
        }
    }

    fun throttleRemainingMillis(container: AppContainer): Long =
        container.storage.appLock.throttleRemainingMillis()
}
