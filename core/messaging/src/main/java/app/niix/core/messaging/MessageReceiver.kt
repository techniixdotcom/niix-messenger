package app.niix.core.messaging

import app.niix.core.transport.TorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MessageReceiver(
    private val transport: TorTransport,
    private val conversationManager: ConversationManager,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var job: Job? = null

    // Caps inbound connections from unauthenticated (pre-session, pre-crypto) sources: generous
    // enough for normal multi-contact use, restrictive enough to blunt a connection-flood DoS --
    // rejected here, before a single frame byte is read, so it can't even reach the per-frame
    // validation in ConversationManager.handleConnection.
    private val connectionLimiter = SlidingWindowLimiter(maxWeight = 60, windowMillis = 10_000)

    fun start() {
        if (job != null) return
        job = scope.launch {
            transport.inbound.collect { connection ->
                if (!connectionLimiter.allow()) {
                    runCatching { connection.close() }
                    return@collect
                }
                launch(Dispatchers.IO) { conversationManager.handleConnection(connection) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
