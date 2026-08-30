package app.niix.core.transport

import app.niix.core.model.OnionAddress
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

interface DuplexConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

interface TorTransport {

    val state: StateFlow<TransportState>

    val inbound: SharedFlow<DuplexConnection>

    suspend fun start()

    suspend fun stop()

    suspend fun publishOnionService(localPort: Int): OnionAddress

    suspend fun connect(address: OnionAddress, port: Int): DuplexConnection
}
