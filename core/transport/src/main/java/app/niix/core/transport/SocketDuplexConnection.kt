package app.niix.core.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class SocketDuplexConnection(private val socket: Socket) : DuplexConnection {

    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override fun close() {
        runCatching { socket.close() }
    }
}
