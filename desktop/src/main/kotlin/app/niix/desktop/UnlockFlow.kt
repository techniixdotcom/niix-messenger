package app.niix.desktop

import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Unlocking, as on the phone: one attempt at a time, the duress passcode wiping and opening a decoy
 * account that looks like an ordinary unlock, and messages that arrived while locked processed
 * afterwards.
 */
object UnlockFlow {

    private val attempts = Mutex()

    enum class Outcome { UNLOCKED, WRONG, THROTTLED }

    suspend fun attempt(container: DesktopContainer, passcode: CharArray): Outcome {
        val outcome = attempts.withLock { withContext(Dispatchers.Default) { tryUnlock(container, passcode) } }
        return when (outcome) {
            UnlockResult.SUCCESS -> {
                container.appScope.launch { runCatching { container.conversations.drainLockedInbox() } }
                Outcome.UNLOCKED
            }
            UnlockResult.DURESS -> Outcome.UNLOCKED
            UnlockResult.THROTTLED -> Outcome.THROTTLED
            UnlockResult.FAILED -> Outcome.WRONG
        }
    }

    /** Opens a database that has no passcode. */
    suspend fun openWithoutPasscode(container: DesktopContainer): Boolean = attempts.withLock {
        withContext(Dispatchers.Default) {
            val result = runCatching { container.storage.appLock.unlockWithoutPasscode() }
                .onFailure { DiagnosticLog.record("unlock", it.javaClass.simpleName) }
                .getOrDefault(UnlockResult.FAILED)
            if (result == UnlockResult.SUCCESS) {
                runCatching { container.expirySweeper.sweepOnce() }
                container.appScope.launch { runCatching { container.conversations.drainLockedInbox() } }
            }
            result == UnlockResult.SUCCESS
        }
    }

    private suspend fun tryUnlock(container: DesktopContainer, pass: CharArray): UnlockResult {
        return try {
            val result = container.storage.appLock.unlock(pass)
            if (result == UnlockResult.DURESS) {
                runCatching { container.wipeAllData(waitForOtherDevicesMillis = 0) }
                runCatching { container.storage.appLock.setPasscode(pass) }
                    .onFailure { DiagnosticLog.record("unlock", "decoy setup failed: ${it.javaClass.simpleName}") }
                runCatching {
                    container.crypto.ensureKeysInitialized()
                    container.conversations.seedDecoyContent(Strings.array("decoy_names"), Strings.array("decoy_messages"))
                }
            }
            if (result == UnlockResult.SUCCESS) runCatching { container.expirySweeper.sweepOnce() }
            if (result == UnlockResult.DURESS && !container.storage.appLock.isUnlocked()) UnlockResult.FAILED else result
        } catch (_: OutOfMemoryError) {
            DiagnosticLog.record("unlock", "out of memory while deriving the key")
            UnlockResult.FAILED
        } catch (e: Exception) {
            DiagnosticLog.record("unlock", e.javaClass.simpleName)
            UnlockResult.FAILED
        } finally {
            pass.fill('\u0000')
        }
    }
}
