package app.niix.core.messaging

import app.niix.core.transport.TorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import app.niix.core.model.DiagnosticLog
import java.util.concurrent.Semaphore
import kotlinx.coroutines.launch

class MessageReceiver(
    private val transport: TorTransport,
    private val conversationManager: ConversationManager,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var job: Job? = null

    private val connectionLimiter = SlidingWindowLimiter(maxWeight = 60, windowMillis = 10_000)

    private val activeConnections = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    fun start() {
        if (job != null) return
        job = scope.launch {
            transport.inbound.collect { connection ->
                if (!connectionLimiter.allow()) {
                    runCatching { connection.close() }
                    return@collect
                }
                if (!activeConnections.tryAcquire()) {
                    DiagnosticLog.record("receive", "refused connection: too many already in flight")
                    runCatching { connection.close() }
                    return@collect
                }
                launch(Dispatchers.IO) {
                    try {
                        conversationManager.handleConnection(connection)
                    } finally {
                        activeConnections.release()
                    }
                }
            }
        }
    }

    private companion object {
        /** Generous for real use, inbound connections are short-lived single frames, while
         * still bounding what an attacker can hold open. */
        const val MAX_CONCURRENT_CONNECTIONS = 24
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
