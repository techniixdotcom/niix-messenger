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

    /**
     * Host and port of the SOCKS proxy the *running* Tor process is actually listening on, or
     * null if it isn't running yet.
     *
     * This must never be assumed to be whatever port was requested in configuration. If the
     * configured port is already in use when Tor starts (a lingering socket from a previous
     * run of this app, another app, anything), the underlying runtime silently reassigns it to
     * an automatically-chosen free port instead of failing. Anything that hardcodes the
     * requested port will then connect to a port with nothing behind it and fail intermittently
     * in a way that looks like a network problem but isn't.
     */
    fun socksAddress(): Pair<String, Int>?

    val inbound: SharedFlow<DuplexConnection>

    suspend fun start()

    suspend fun stop()

    suspend fun publishOnionService(localPort: Int): OnionAddress

    suspend fun connect(address: OnionAddress, port: Int): DuplexConnection
}
