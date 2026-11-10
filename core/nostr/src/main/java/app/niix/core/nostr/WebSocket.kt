package app.niix.core.nostr

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC 6455 client: text frames, ping/pong and close, nothing else.
 *
 * We don't use OkHttp here because the socket has to be the one Tor's SOCKS proxy gave us. HTTP
 * clients behind a SOCKS proxy have a habit of resolving the host locally first, which would leak
 * every relay we use to the local DNS server. The handshake sends no User-Agent, Origin or cookies.
 */
internal class WebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : Closeable {

    private val writeLock = Any()
    private val rng = SecureRandom()

    @Volatile
    var lastReceivedAtMillis: Long = System.currentTimeMillis()
        private set

    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray())

    fun sendPing() = sendFrame(OP_PING, ByteArray(0))

    /**
     * Blocks for the next text message; null means the relay closed the connection cleanly.
     * [deadlineMillis] caps the whole wait. A read timeout alone isn't enough, since a relay that
     * keeps pinging but never answers resets it with every frame.
     */
    fun receiveText(deadlineMillis: Long = Long.MAX_VALUE): String? {
        val message = ByteArrayOutputStream()
        var inMessage = false
        while (true) {
            if (System.currentTimeMillis() > deadlineMillis) throw SocketTimeoutException("No reply before deadline")
            val b0 = readByte()
            val b1 = readByte()
            val opcode = b0 and 0x0F
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length < 0 || length > MAX_MESSAGE_BYTES) throw IOException("WebSocket frame too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also(::readFully) else null
            val payload = ByteArray(length.toInt()).also(::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            lastReceivedAtMillis = System.currentTimeMillis()

            when (opcode) {
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    runCatching { sendFrame(OP_CLOSE, ByteArray(0)) }
                    return null
                }
                OP_TEXT, OP_CONTINUATION -> {
                    if ((opcode == OP_CONTINUATION) != inMessage) throw IOException("Out-of-order WebSocket frame")
                    inMessage = true
                    if (message.size() + payload.size > MAX_MESSAGE_BYTES) throw IOException("WebSocket message too large")
                    message.write(payload)
                    if (b0 and 0x80 != 0) return message.toString(Charsets.UTF_8.name())
                }
                else -> throw IOException("Unexpected WebSocket opcode $opcode")
            }
        }
    }

    override fun close() {
        runCatching { sendFrame(OP_CLOSE, ByteArray(0)) }
        runCatching { socket.close() }
    }

    // Client frames must be masked (RFC 6455 5.3).
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(rng::nextBytes)
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WebSocket connection closed")
        return b
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("WebSocket connection closed")
            offset += n
        }
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        // Way above anything we publish or accept; limits what a hostile relay can make us buffer.
        private const val MAX_MESSAGE_BYTES = 512 * 1024
        private const val MAX_HANDSHAKE_BYTES = 16 * 1024
        private const val HANDSHAKE_TIMEOUT_MILLIS = 60_000
        private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Dials through [dialer], adds TLS for wss and does the upgrade. The certificate is checked
         * by the platform's HTTPS endpoint identification and, if given, by [hostnameVerifier] as
         * well.
         */
        fun connect(url: RelayUrl, dialer: Dialer, hostnameVerifier: HostnameVerifier?): WebSocket {
            val raw = dialer.dial(url.host, url.port)
            try {
                raw.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                val socket = if (url.tls) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host, url.port, true) as SSLSocket
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl.startHandshake()
                    if (hostnameVerifier != null && !hostnameVerifier.verify(url.host, ssl.session)) {
                        throw IOException("TLS certificate does not match ${url.host}")
                    }
                    ssl
                } else {
                    raw
                }
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
                val host = if (url.port == RelayUrl.defaultPort(url.tls)) url.host else "${url.host}:${url.port}"
                output.write(
                    ("GET ${url.path} HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
                )
                output.flush()

                val lines = readHandshake(input)
                val status = lines.firstOrNull() ?: throw IOException("Empty WebSocket handshake response")
                if (status.split(' ').getOrNull(1) != "101") throw IOException("Relay refused WebSocket upgrade: $status")
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray()),
                )
                val accept = lines.drop(1)
                    .map { it.split(':', limit = 2) }
                    .firstOrNull { it.size == 2 && it[0].trim().equals("Sec-WebSocket-Accept", ignoreCase = true) }
                    ?.get(1)?.trim()
                if (accept != expected) throw IOException("Bad Sec-WebSocket-Accept from relay")
                socket.soTimeout = 0
                return WebSocket(socket, input, output)
            } catch (t: Throwable) {
                runCatching { raw.close() }
                throw t
            }
        }

        private fun readHandshake(input: InputStream): List<String> {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Relay closed during WebSocket handshake")
                buffer.write(b)
                if (buffer.size() > MAX_HANDSHAKE_BYTES) throw IOException("WebSocket handshake response too large")
                if (b == '\n'.code && buffer.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
            }
            return buffer.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        }
    }
}
