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
            // Monotonic clock, not the wall clock: otherwise winding the clock back would stop
            // messages from ever expiring, which matters most against someone holding the phone.
            // Log it when the clock looks rolled back; it's usually a timezone or NTP hiccup,
            // sometimes someone trying to keep messages, and either way the user should be able to
            // find out why nothing is disappearing.
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
