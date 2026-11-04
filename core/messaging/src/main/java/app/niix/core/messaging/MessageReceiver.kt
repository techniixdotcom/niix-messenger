package app.niix.core.messaging

import app.niix.core.model.DiagnosticLog
import app.niix.core.transport.TorTransport
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
                    // Each read has its own timeout, but a sender trickling a byte just inside it
                    // could hold a connection open indefinitely, and with only so many allowed at
                    // once, a few of those would shut out everyone else. So every connection gets
                    // an overall deadline, lifted only for an authenticated attachment transfer or
                    // a contact whose messages decrypt, who may keep it open for the next one.
                    val deadline = launch {
                        delay(CONNECTION_DEADLINE_MILLIS)
                        DiagnosticLog.record("receive", "closed a connection that went on too long")
                        runCatching { connection.close() }
                    }
                    try {
                        conversationManager.handleConnection(connection) { deadline.cancel() }
                    } finally {
                        deadline.cancel()
                        activeConnections.release()
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        /**
         * Room for contacts keeping a connection open while they chat (see PeerConnections), while
         * limiting what an attacker can hold open.
         */
        const val MAX_CONCURRENT_CONNECTIONS = 48

        /** Far longer than a message, a bundle request or a wipe needs, even over a slow circuit. */
        const val CONNECTION_DEADLINE_MILLIS = 90_000L
    }
}
