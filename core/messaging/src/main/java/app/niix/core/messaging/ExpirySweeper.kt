package app.niix.core.messaging

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
            val expired = storage.messages.deleteExpired(System.currentTimeMillis())
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
