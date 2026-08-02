package app.niix.core.transport

import java.io.File

data class TorEndpoints(
    val socksHost: String,
    val socksPort: Int,
    val controlHost: String,
    val controlPort: Int,
    val auth: ControlAuth,
)

data class BridgeConfig(
    val enabled: Boolean = false,
    val bridgeLines: List<String> = emptyList(),
)

interface TorProcessProvider {
    suspend fun start(): TorEndpoints
    suspend fun stop()
}

interface OnionKeyStore {
    fun load(): String?
    fun save(privateKey: String)
    fun clear()
}

class InMemoryOnionKeyStore : OnionKeyStore {
    @Volatile
    private var key: String? = null
    override fun load(): String? = key
    override fun save(privateKey: String) { key = privateKey }
    override fun clear() { key = null }
}

/**
 * Connects to a Tor process that is already running and exposing a control port and a
 * SOCKS port (for example Orbot with "Allow background starts" and the control port
 * enabled). This provider does not own the process, so [stop] does not terminate it.
 *
 * To embed Tor inside the app instead, implement [TorProcessProvider] over a binary
 * provider such as kmp-tor and return its assigned SOCKS/control ports; the rest of the
 * transport is unchanged. Bridges/pluggable transports are configured in the embedded
 * torrc (or in Orbot when using this external provider).
 */
class ExternalTorProcessProvider(
    private val socksHost: String = DEFAULT_HOST,
    private val socksPort: Int = DEFAULT_SOCKS_PORT,
    private val controlHost: String = DEFAULT_HOST,
    private val controlPort: Int = DEFAULT_CONTROL_PORT,
    private val cookieFile: File? = null,
    private val controlPassword: String? = null,
) : TorProcessProvider {

    override suspend fun start(): TorEndpoints {
        val auth = when {
            cookieFile != null && cookieFile.isFile -> ControlAuth.Cookie(cookieFile.readBytes())
            controlPassword != null -> ControlAuth.Password(controlPassword)
            else -> ControlAuth.None
        }
        return TorEndpoints(socksHost, socksPort, controlHost, controlPort, auth)
    }

    override suspend fun stop() {
        // The process is owned externally; nothing to tear down here.
    }

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_SOCKS_PORT = 9050
        private const val DEFAULT_CONTROL_PORT = 9051
    }
}
