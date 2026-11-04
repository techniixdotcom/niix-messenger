package app.niix.core.nostr

import app.niix.core.transport.Socks5Client
import app.niix.core.transport.TorTransport
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom

fun interface Dialer {
    fun dial(host: String, port: Int): Socket
}

/**
 * Connects through Tor's SOCKS port. The host name goes to Tor unresolved, so there's no local DNS
 * lookup, and each connection uses a random SOCKS username. With Tor's default IsolateSOCKSAuth
 * that puts every connection on its own circuit.
 */
class TorDialer(private val transport: TorTransport) : Dialer {

    private val rng = SecureRandom()

    override fun dial(host: String, port: Int): Socket {
        val (socksHost, socksPort) = transport.socksAddress() ?: throw IOException("Tor is not running")
        return Socks5Client.connect(
            socksHost = socksHost,
            socksPort = socksPort,
            destinationHost = host,
            destinationPort = port,
            connectTimeoutMillis = 60_000,
            isolationToken = Hex.encode(ByteArray(16).also(rng::nextBytes)),
        )
    }
}
