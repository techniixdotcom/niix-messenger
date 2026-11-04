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

    val bootstrapProgress: StateFlow<Int>

    /**
     * Host and port the running Tor's SOCKS proxy is actually listening on, or null if it isn't
     * running.
     *
     * Tor picks the port at each start, so this is the only way to find it.
     */
    fun socksAddress(): Pair<String, Int>?

    val inbound: SharedFlow<DuplexConnection>

    suspend fun start()

    suspend fun stop()

    /** Publishes our onion service on [virtualPort] and starts accepting connections from it. */
    suspend fun publishOnionService(virtualPort: Int): OnionAddress

    /**
     * Whether our onion service still answers, checked by connecting to it through Tor the way a
     * contact would. If the local end has stopped listening it's repaired first. False means it
     * can't be reached; restarting Tor is up to the caller.
     */
    suspend fun checkOnionService(): Boolean

    suspend fun connect(address: OnionAddress, port: Int): DuplexConnection
}
