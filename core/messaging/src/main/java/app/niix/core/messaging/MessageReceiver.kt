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

    fun start() {
        if (job != null) return
        job = scope.launch {
            transport.inbound.collect { connection ->
                launch(Dispatchers.IO) { conversationManager.handleConnection(connection) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
