package app.niix.core.transport

import app.niix.core.model.OnionAddress
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

class RealTorTransport(
    private val provider: TorProcessProvider,
    private val scope: CoroutineScope,
    private val onionKeyStore: OnionKeyStore = InMemoryOnionKeyStore(),
    private val bridgeConfig: BridgeConfig = BridgeConfig(),
) : TorTransport {

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<DuplexConnection>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<DuplexConnection> = _inbound.asSharedFlow()

    @Volatile
    private var endpoints: TorEndpoints? = null

    @Volatile
    private var control: TorControlClient? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var serviceId: String? = null

    override suspend fun start() {
        if (_state.value == TransportState.RUNNING) return
        _state.value = TransportState.STARTING
        try {
            val resolved = provider.start()
            endpoints = resolved
            val client = TorControlClient(resolved.controlHost, resolved.controlPort)
            withContext(Dispatchers.IO) {
                client.connect()
                client.authenticate(resolved.auth)
            }
            control = client
            _state.value = TransportState.RUNNING
        } catch (t: Throwable) {
            _state.value = TransportState.ERROR
            throw t
        }
    }

    override suspend fun publishOnionService(localPort: Int): OnionAddress = withContext(Dispatchers.IO) {
        val client = control ?: throw IllegalStateException("Transport not started")
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        }
        serverSocket = server
        startAcceptLoop(server)
        val existingKey = onionKeyStore.load()
        val onion = client.addOnion(virtualPort = localPort, targetPort = localPort, existingPrivateKey = existingKey)
        serviceId = onion.serviceId
        if (existingKey == null && onion.privateKey != null) {
            onionKeyStore.save(onion.privateKey)
        }
        OnionAddress.parse(onion.serviceId + OnionAddress.SUFFIX)
    }

    override suspend fun connect(address: OnionAddress, port: Int): DuplexConnection = withContext(Dispatchers.IO) {
        val resolved = endpoints ?: throw IllegalStateException("Transport not started")
        val socket = Socks5Client.connect(
            socksHost = resolved.socksHost,
            socksPort = resolved.socksPort,
            destinationHost = address.value,
            destinationPort = port,
        )
        SocketDuplexConnection(socket)
    }

    override suspend fun stop() {
        _state.value = TransportState.STOPPING
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serviceId?.let { id -> control?.delOnion(id) }
        serviceId = null
        runCatching { control?.close() }
        control = null
        provider.stop()
        endpoints = null
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
}
