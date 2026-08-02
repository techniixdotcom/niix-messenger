package app.niix

import android.content.Context
import app.niix.core.model.OnionAddress
import app.niix.core.transport.DuplexConnection
import app.niix.core.transport.SocketDuplexConnection
import app.niix.core.transport.Socks5Client
import app.niix.core.transport.TorTransport
import app.niix.core.transport.TransportState
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeral
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs an embedded Tor process (bundled by kmp-tor) so the app needs no external Tor / Orbot.
 *
 * The v3 onion service is defined via configuration (HiddenServiceDir), so tor persists the
 * service key across restarts (stable address) and writes the address to `<hs>/hostname`.
 * Inbound onion traffic is forwarded to a local ServerSocket; outbound dialing goes through
 * tor's SOCKS port using the existing [Socks5Client].
 */
class KmpTorTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val servicePort: Int,
) : TorTransport {

    private val appContext = context.applicationContext
    private val torWorkDir = File(appContext.filesDir, TOR_DIR)
    private val torCacheDir = File(appContext.cacheDir, TOR_DIR)
    private val hiddenServiceDir = File(torWorkDir, "hs")
    private val hostnameFile = File(hiddenServiceDir, "hostname")

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<DuplexConnection>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<DuplexConnection> = _inbound.asSharedFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private val runtime: TorRuntime by lazy {
        torWorkDir.mkdirs()
        torCacheDir.mkdirs()
        val environment = TorRuntime.Environment.Builder(
            workDirectory = torWorkDir.path.toFile(),
            cacheDirectory = torCacheDir.path.toFile(),
            loader = ResourceLoaderTorExec::getOrCreate,
        ) {}
        TorRuntime.Builder(environment) {
            config { _ ->
                TorOption.__SocksPort.configure { port(SOCKS_PORT.toPortEphemeral()) }
                TorOption.HiddenServiceDir.tryConfigure {
                    directory(hiddenServiceDir.path.toFile())
                    version(3)
                    port(virtual = servicePort.toPort()) {
                        target(port = servicePort.toPort())
                    }
                }
            }
        }
    }

    override suspend fun start() {
        if (_state.value == TransportState.RUNNING) return
        _state.value = TransportState.STARTING
        try {
            runtime.startDaemonAsync()
            _state.value = TransportState.RUNNING
        } catch (t: Throwable) {
            _state.value = TransportState.ERROR
            throw t
        }
    }

    override suspend fun publishOnionService(localPort: Int): OnionAddress = withContext(Dispatchers.IO) {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        }
        serverSocket = server
        startAcceptLoop(server)
        OnionAddress.parse(awaitHostname())
    }

    override suspend fun connect(address: OnionAddress, port: Int): DuplexConnection = withContext(Dispatchers.IO) {
        val socket = Socks5Client.connect(
            socksHost = SOCKS_HOST,
            socksPort = SOCKS_PORT,
            destinationHost = address.value,
            destinationPort = port,
            soTimeoutMillis = 60_000,
        )
        SocketDuplexConnection(socket)
    }

    override suspend fun stop() {
        _state.value = TransportState.STOPPING
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { runtime.stopDaemonAsync() }
        _state.value = TransportState.STOPPED
    }

    private suspend fun awaitHostname(timeoutMillis: Long = 90_000L): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (hostnameFile.isFile) {
                val text = hostnameFile.readText().trim()
                if (text.endsWith(OnionAddress.SUFFIX)) return text
            }
            delay(500)
        }
        throw IllegalStateException("Tor onion service address was not published in time")
    }

    private fun startAcceptLoop(server: ServerSocket) {
        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                _inbound.tryEmit(SocketDuplexConnection(socket))
            }
        }
    }

    companion object {
        private const val TOR_DIR = "niix-tor"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 9055
    }
}
