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
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeral
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KmpTorTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val servicePort: Int,
    private val onionKeySeed: () -> ByteArray,
) : TorTransport {

    private val appContext = context.applicationContext
    private val torWorkDir = File(appContext.filesDir, TOR_DIR)
    private val torCacheDir = File(appContext.cacheDir, TOR_DIR)

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

        val seed = onionKeySeed()
        val privateKey = ED25519_V3.PrivateKey.generate(seed, offset = 0)
        try {
            val entry = runtime.executeAsync(
                TorCmd.Onion.Add.existing(privateKey) {
                    port(virtual = servicePort.toPort()) {
                        target(port = servicePort.toPort())
                    }
                },
            )

            OnionAddress.parse(entry.publicKey.address().value + OnionAddress.SUFFIX)
        } finally {

            privateKey.destroy()
        }
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
