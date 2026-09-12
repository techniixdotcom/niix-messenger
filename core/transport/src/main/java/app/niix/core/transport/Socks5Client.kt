package app.niix.core.transport

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class Socks5Exception(message: String) : IOException(message)

object Socks5Client {

    private const val VERSION = 0x05
    private const val NO_AUTH = 0x00
    private const val CMD_CONNECT = 0x01
    private const val ADDR_DOMAIN = 0x03
    private const val REPLY_SUCCESS = 0x00

    fun connect(
        socksHost: String,
        socksPort: Int,
        destinationHost: String,
        destinationPort: Int,
        connectTimeoutMillis: Int = 30_000,
        soTimeoutMillis: Int = 0,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMillis)
            socket.soTimeout = soTimeoutMillis
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            out.write(byteArrayOf(VERSION.toByte(), 0x01, NO_AUTH.toByte()))
            out.flush()
            val greetingVer = input.readUnsignedByte()
            val method = input.readUnsignedByte()
            if (greetingVer != VERSION || method != NO_AUTH) {
                throw Socks5Exception("SOCKS5 greeting rejected (ver=$greetingVer method=$method)")
            }

            val host = destinationHost.toByteArray(Charsets.US_ASCII)
            if (host.size > 255) throw Socks5Exception("Destination host too long")
            val request = ArrayList<Byte>(7 + host.size)
            request.add(VERSION.toByte())
            request.add(CMD_CONNECT.toByte())
            request.add(0x00)
            request.add(ADDR_DOMAIN.toByte())
            request.add(host.size.toByte())
            host.forEach { request.add(it) }
            request.add(((destinationPort shr 8) and 0xFF).toByte())
            request.add((destinationPort and 0xFF).toByte())
            out.write(request.toByteArray())
            out.flush()

            val replyVer = input.readUnsignedByte()
            val status = input.readUnsignedByte()
            input.readUnsignedByte()
            if (replyVer != VERSION) throw Socks5Exception("Bad SOCKS5 reply version $replyVer")
            if (status != REPLY_SUCCESS) throw Socks5Exception("SOCKS5 connect failed (status=$status)")
            consumeBoundAddress(input)
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun consumeBoundAddress(input: DataInputStream) {
        when (val type = input.readUnsignedByte()) {
            0x01 -> input.skipFully(4)
            ADDR_DOMAIN -> input.skipFully(input.readUnsignedByte())
            0x04 -> input.skipFully(16)
            else -> throw Socks5Exception("Unknown SOCKS5 address type $type")
        }
        input.skipFully(2)
    }

    private fun DataInputStream.skipFully(count: Int) {
        var remaining = count
        val buffer = ByteArray(minOf(count, 64).coerceAtLeast(1))
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size))
            if (read < 0) throw Socks5Exception("Unexpected end of SOCKS5 reply")
            remaining -= read
        }
    }
}
