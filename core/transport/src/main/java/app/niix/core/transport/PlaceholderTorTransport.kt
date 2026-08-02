package app.niix.core.transport

import app.niix.core.model.OnionAddress
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A non-routable placeholder so the rest of the app can be built and run before a
 * real Tor provider is integrated. It does NOT carry traffic over Tor. [connect]
 * deliberately fails. Replace this with a C-Tor or Arti backed implementation of
 * [TorTransport]; see docs/ARCHITECTURE.md for the integration seam.
 */
class PlaceholderTorTransport : TorTransport {

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<DuplexConnection>()
    override val inbound: SharedFlow<DuplexConnection> = _inbound.asSharedFlow()

    @Volatile
    private var onionAddress: OnionAddress? = null

    @Volatile
    private var publishedPort: Int? = null

    override suspend fun start() {
        _state.value = TransportState.STARTING
        _state.value = TransportState.RUNNING
    }

    override suspend fun stop() {
        _state.value = TransportState.STOPPING
        _state.value = TransportState.STOPPED
    }

    override suspend fun publishOnionService(localPort: Int): OnionAddress {
        publishedPort = localPort
        return onionAddress ?: generatePlaceholderAddress().also { onionAddress = it }
    }

    override suspend fun connect(address: OnionAddress, port: Int): DuplexConnection {
        throw UnsupportedOperationException(
            "PlaceholderTorTransport cannot route over Tor. Integrate a real Tor provider.",
        )
    }

    private fun generatePlaceholderAddress(): OnionAddress {
        val raw = ByteArray(35).also { SecureRandom().nextBytes(it) }
        val encoded = Base32.encodeLower(raw).take(56)
        return OnionAddress.parse("$encoded.onion")
    }
}
