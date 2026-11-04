package app.niix.core.messaging

import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ExpirySweeper(
    private val storage: SecureStorage,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {

    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sweepOnce()
                delay(intervalMillis)
            }
        }
    }

    fun sweepOnce() {
        runCatching {
            // The monotonic clock, not the wall clock.
            //
            // Expiry is a timestamp compared against now, so a device clock wound backwards made
            // messages that should have been destroyed stay indefinitely. That is worth nothing
            // against a remote attacker and everything against someone holding the phone, which
            // is who disappearing messages are for.
            // Worth telling the user about. A clock reading earlier than one already seen is
            // usually a timezone or NTP oddity, and occasionally someone trying to stop messages
            // expiring. Either way they should be able to find out why nothing is disappearing.
            if (storage.clock.looksRolledBack()) {
                DiagnosticLog.record(
                    "clock",
                    "the device clock reads earlier than a time already seen; expiry uses the later one",
                )
            }
            val expired = storage.messages.deleteExpired(storage.clock.now())
            expired.forEach { entry ->
                val attachmentId = entry.attachmentId ?: return@forEach

                storage.deleteAttachmentFile(attachmentId)
                storage.attachments.delete(attachmentId)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS = 15_000L
    }
}
