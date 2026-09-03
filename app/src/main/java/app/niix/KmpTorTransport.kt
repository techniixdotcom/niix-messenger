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
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
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

class KmpTorTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val servicePort: Int,
    private val onionKeySeed: () -> ByteArray,
) : TorTransport {

    private val appContext = context.applicationContext
    private val torWorkDir = File(appContext.filesDir, TOR_DIR)

    /**
     * Tor's cache directory -- deliberately under filesDir, not cacheDir.
     *
     * This holds the cached network consensus and relay descriptors, which is exactly the data
     * that makes every bootstrap after the first one fast. Android empties cacheDir whenever it
     * wants disk space, with no warning; when that happens Tor has no consensus to start from
     * and has to fetch the whole thing again, turning a warm start back into a cold one. It's
     * named "cache" from Tor's point of view, but losing it costs tens of seconds of startup
     * rather than being free to discard, so it belongs somewhere that persists.
     */
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
                // Without this, a Tor process that went dormant during a period of no app
                // activity (Tor's own default: 24h idle) can stay dormant across a process
                // restart, sitting idle instead of actively reconnecting, until something
                // forces it awake. Canceling dormancy on every startup means the app always
                // tries to actually bootstrap right away instead of potentially waiting on a
                // dormant daemon to notice real traffic before it starts reconnecting.
                TorOption.DormantCanceledByStartup.configure(true)
                // How long Tor keeps reusing an established circuit before building a fresh one.
                // Every new circuit costs a full build (and, for an onion service, a rendezvous)
                // before a single byte moves -- which is the largest single latency cost in
                // sending a message. The default of 10 minutes means an ordinary back-and-forth
                // conversation repeatedly pays that cost; 30 minutes keeps a circuit warm across
                // a realistic exchange.
                //
                // The tradeoff is real and worth stating: a longer-lived circuit means more of
                // this device's traffic travels over the same relays, giving a malicious guard a
                // longer window to observe timing patterns. It's a milder tradeoff here than for
                // general browsing, because onion-service traffic never leaves the network via an
                // exit node -- no relay on the path ever sees a destination or plaintext. 30
                // minutes is a moderate step, not the maximum, deliberately.
                TorOption.MaxCircuitDirtiness.configure(30, IntervalUnit.MINUTES)
            }
        }
    }

    override suspend fun start() {
        if (_state.value == TransportState.RUNNING) return
        _state.value = TransportState.STARTING
        try {
            runtime.startDaemonAsync()
            // startDaemonAsync() only waits through phase 4 (control connection
            // established) -- NOT phase 5 (actual network bootstrap). Publishing the
            // hidden service needs a real, working network connection (it has to reach
            // HSDirs to announce itself), so proceeding before bootstrap is actually
            // finished doesn't skip the wait, it just moves it somewhere less visible --
            // the ADD_ONION call would sit there until the network really is ready anyway,
            // with no way for the app to show real progress in the meantime.
            awaitNetworkReady()
            _state.value = TransportState.RUNNING
        } catch (t: Throwable) {
            _state.value = TransportState.ERROR
            throw t
        }
    }

    private suspend fun awaitNetworkReady(timeoutMillis: Long = 120_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!runtime.isReady()) {
            _bootstrapProgress.value = runtime.state().daemon.bootstrap.toInt().coerceIn(0, 100)
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("Tor did not finish bootstrapping in time")
            }
            delay(250)
        }
        _bootstrapProgress.value = 100
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
            // Both copies of this key material have to go: the derived key object AND the raw
            // seed bytes it came from. OnionIdentityDao hands back a fresh array on every call
            // (a new cursor.getBlob(), not shared state), so zeroing it here can't affect any
            // other caller -- and leaving it un-zeroed would mean this device's onion service
            // private key sits recoverable in the heap for the life of the process, which
            // defeats the point of having moved it into the encrypted database at all.
            seed.fill(0)
            privateKey.destroy()
        }
    }

    /**
     * The SOCKS address Tor is *actually* listening on, read from the running process's own
     * listener set rather than assumed from [SOCKS_PORT]. See the interface doc for why the
     * configured port can't be trusted: kmp-tor reassigns it to an automatically-chosen free
     * port if the requested one is busy at start time, which is exactly the kind of thing that
     * happens when a previous run of the app hasn't fully released its socket yet -- producing
     * intermittent "can't reach the network" failures that clear up after a force-close.
     */
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
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { runtime.stopDaemonAsync() }
        _bootstrapProgress.value = 0
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
        private const val TOR_CACHE_DIR = "niix-tor-cache"

        /** The SOCKS port *requested* in configuration. Tor may end up listening somewhere else
         * entirely if this one is busy at start time (kmp-tor reassigns to an auto-chosen free
         * port rather than failing), so nothing may ever assume traffic goes here -- always ask
         * [socksAddress] where the listener actually is. */
        private const val SOCKS_PORT = 9055
    }
}
