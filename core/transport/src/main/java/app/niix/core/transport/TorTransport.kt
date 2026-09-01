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

    /** Real bootstrap progress, 0-100, as reported by the Tor process itself -- not a guess,
     * not a fixed-duration animation. Stays at 0 until [start] actually begins bootstrapping,
     * and only reaches 100 once the network genuinely is ready, matching what [state]
     * transitioning to [TransportState.RUNNING] also depends on. */
    val bootstrapProgress: StateFlow<Int>

    val inbound: SharedFlow<DuplexConnection>

    suspend fun start()

    suspend fun stop()

    suspend fun publishOnionService(localPort: Int): OnionAddress

    suspend fun connect(address: OnionAddress, port: Int): DuplexConnection
}
