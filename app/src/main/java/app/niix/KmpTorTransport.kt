package app.niix

import android.content.Context
import app.niix.core.model.DiagnosticLog
import app.niix.core.model.OnionAddress
import app.niix.core.transport.DuplexConnection
import app.niix.core.transport.SocketDuplexConnection
import app.niix.core.transport.Socks5Client
import app.niix.core.transport.TorTransport
import app.niix.core.transport.TransportState
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.runtime.NetworkObserver
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import java.io.File
import java.net.InetAddress
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class KmpTorTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val network: NetworkObserver,
    private val onionKeySeed: () -> ByteArray,
) : TorTransport {

    private val appContext = context.applicationContext
    private val torWorkDir = File(appContext.filesDir, TOR_DIR)
    private val torCacheDir = File(appContext.filesDir, TOR_CACHE_DIR)

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow(0)
    override val bootstrapProgress: StateFlow<Int> = _bootstrapProgress.asStateFlow()

    private val _inbound = MutableSharedFlow<DuplexConnection>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<DuplexConnection> = _inbound.asSharedFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    /** Set once the service is up, until stop(). Publishing again would collide in Tor. */
    @Volatile
    private var published: OnionAddress? = null

    /** The onion's port, and the loopback port Tor forwards it to. Tor keeps the latter until stop(). */
    @Volatile
    private var virtualPort = 0

    @Volatile
    private var forwardPort = 0

    /**
     * Publishing, repairing and taking down the service, one at a time. Two publishes at once used
     * to happen when the app was opened twice while Tor was starting: the second closed the first
     * one's listener, Tor turned the second down as a duplicate, and the address was left pointing
     * at a closed port. Nothing direct arrived after that until the app restarted.
     */
    private val serviceLock = Mutex()

    private val runtime: TorRuntime by lazy {
        torWorkDir.mkdirs()
        torCacheDir.mkdirs()
        val environment = TorRuntime.Environment.Builder(
            workDirectory = torWorkDir.path.toFile(),
            cacheDirectory = torCacheDir.path.toFile(),
            loader = ResourceLoaderTorExec::getOrCreate,
        ) {}
        TorRuntime.Builder(environment) {
            // Tells Tor when the phone's network goes and comes back, so it drops connections over
            // a network that's gone and reconnects at once instead of waiting out its own backoff.
            networkObserver = network
            config { _ ->
                // A port Tor picks each start, not a fixed one, so another app can't tell NiiX is
                // running by trying a known port. socksAddress() asks Tor which one it chose.
                TorOption.__SocksPort.configure { auto() }
                // Tor goes dormant after 24 hours without activity and can stay that way across a
                // process restart. Waking it on every start means we bootstrap right away instead
                // of waiting for traffic to wake it.
                TorOption.DormantCanceledByStartup.configure(true)
                // How long Tor reuses a circuit before building a new one. Every new circuit (plus
                // a rendezvous for an onion service) is the biggest latency cost in sending a
                // message, and the 10 minute default means an ordinary conversation keeps paying
                // it. 30 minutes keeps a circuit warm through a normal exchange.
                //
                // The cost: more of our traffic goes over the same relays, so a malicious guard
                // gets a longer look at timing. That matters less here than for browsing, since
                // onion traffic never leaves through an exit.
                TorOption.MaxCircuitDirtiness.configure(30, IntervalUnit.MINUTES)

                // Tor's padding options for mobile. The manual suggests offering both where
                // bandwidth is expensive, and both are off by default.
                //
                // They cut down padding and how long connections stay open, which means fewer radio
                // wakeups and less battery while idle. Padding exists to make traffic analysis
                // harder, so this does make our patterns a bit easier to fingerprint from the
                // guard. Against the global passive adversary we already don't defend against, it
                // changes nothing.
                TorOption.ReducedConnectionPadding.configure(true)
                TorOption.ReducedCircuitPadding.configure(true)
            }
        }
    }

    override suspend fun start() {
        if (_state.value == TransportState.RUNNING) return
        _state.value = TransportState.STARTING
        try {
            runtime.startDaemonAsync()
            // startDaemonAsync() only waits for the control connection, not for the network
            // bootstrap. Publishing the onion service needs a working network anyway, so wait here
            // where we can show real progress.
            awaitNetworkReady()
            _state.value = TransportState.RUNNING
        } catch (t: Throwable) {
            _state.value = TransportState.ERROR
            throw t
        }
    }

    private suspend fun awaitNetworkReady(timeoutMillis: Long = 120_000L) {
        // elapsedRealtime, not the wall clock: a time sync mid-bootstrap could make the deadline
        // expire at once or stretch by minutes.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (!runtime.isReady()) {
            _bootstrapProgress.value = runtime.state().daemon.bootstrap.toInt().coerceIn(0, 100)
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                throw IllegalStateException("Tor did not finish bootstrapping in time")
            }
            delay(250)
        }
        _bootstrapProgress.value = 100
    }

    override suspend fun publishOnionService(virtualPort: Int): OnionAddress = serviceLock.withLock {
        withContext(Dispatchers.IO) { publishLocked(virtualPort) }
    }

    private suspend fun publishLocked(virtualPort: Int): OnionAddress {
        // The service is started on every return to the app; the onion only needs adding once.
        published?.let { return it }
        // Tor forwards the onion service to a loopback port, and every app on the phone can reach
        // loopback. A port the system picks, different each time, means another app can't find
        // NiiX by knocking on a well-known port. What arrives there is treated exactly like
        // traffic from Tor anyway.
        runCatching { serverSocket?.close() }
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        startAcceptLoop(server)

        val seed = onionKeySeed()
        val privateKey = ED25519_V3.PrivateKey.generate(seed, offset = 0)
        try {
            val entry = runtime.executeAsync(
                TorCmd.Onion.Add.existing(privateKey) {
                    port(virtual = virtualPort.toPort()) {
                        target(port = server.localPort.toPort())
                    }
                },
            )

            this.virtualPort = virtualPort
            forwardPort = server.localPort
            return OnionAddress.parse(entry.publicKey.address().value + OnionAddress.SUFFIX).also { published = it }
        } finally {
            // Zero both the key object and the seed bytes. The DAO returns a fresh array each call,
            // so this can't affect anyone else, and leaving it would keep the onion private key in
            // the heap for the life of the process.
            seed.fill(0)
            privateKey.destroy()
        }
    }

    override suspend fun checkOnionService(): Boolean {
        val address = published ?: return false
        if (!ensureListening()) return false
        return try {
            connect(address, virtualPort).use { connection ->
                // Not a frame type, so the receiving side just closes the connection. Getting this
                // far means Tor reached the service and the service reached our listener.
                connection.output.write(PROBE_BYTE)
                connection.output.flush()
            }
            true
        } catch (e: Exception) {
            DiagnosticLog.record("network", "own address didn't answer (${e::class.java.simpleName})")
            false
        }
    }

    /** Opens the listener again if it has closed. It has to be on the same port, since that's where Tor sends. */
    private suspend fun ensureListening(): Boolean = serviceLock.withLock {
        withContext(Dispatchers.IO) {
            if (published == null) return@withContext false
            val server = serverSocket
            if (server != null && !server.isClosed && acceptJob?.isActive == true) return@withContext true
            runCatching { server?.close() }
            val replacement = runCatching { ServerSocket(forwardPort, 0, InetAddress.getByName("127.0.0.1")) }.getOrNull()
                ?: return@withContext false
            serverSocket = replacement
            startAcceptLoop(replacement)
            DiagnosticLog.record("network", "incoming listener had stopped and was reopened")
            true
        }
    }

    override fun socksAddress(): Pair<String, Int>? {
        val socks = runCatching { runtime.listeners().socks }.getOrNull() ?: return null
        val listener = socks.firstOrNull() ?: return null
        return listener.address.canonicalHostName() to listener.port.value
    }

    override suspend fun connect(address: OnionAddress, port: Int): DuplexConnection = withContext(Dispatchers.IO) {
        val (host, socksPort) = socksAddress()
            ?: throw IllegalStateException("Tor is not running -- no SOCKS listener available")
        val socket = Socks5Client.connect(
            socksHost = host,
            socksPort = socksPort,
            destinationHost = address.value,
            destinationPort = port,
            soTimeoutMillis = 60_000,
        )
        SocketDuplexConnection(socket)
    }

    override suspend fun stop() {
        _state.value = TransportState.STOPPING
        serviceLock.withLock {
            acceptJob?.cancel()
            acceptJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
            published = null
        }
        runCatching { runtime.stopDaemonAsync() }
        // Only if nothing restarted the connection meanwhile; a slow stop shouldn't mark a running
        // connection as stopped.
        if (_state.compareAndSet(TransportState.STOPPING, TransportState.STOPPED)) {
            _bootstrapProgress.value = 0
        }
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
                // A read deadline on every inbound socket. Without one, a connection that opens and
                // never sends anything ties up a coroutine and a socket forever, which makes a
                // slow-loris attack cheap.
                runCatching { socket.soTimeout = INBOUND_READ_TIMEOUT_MILLIS }
                // Refused when the queue is full; closed then, or it would stay open for good.
                if (!_inbound.tryEmit(SocketDuplexConnection(socket))) runCatching { socket.close() }
            }
        }
    }

    companion object {
        private const val INBOUND_READ_TIMEOUT_MILLIS = 30_000
        private const val PROBE_BYTE = 0

        private const val TOR_DIR = "niix-tor"
        private const val TOR_CACHE_DIR = "niix-tor-cache"
    }
}
