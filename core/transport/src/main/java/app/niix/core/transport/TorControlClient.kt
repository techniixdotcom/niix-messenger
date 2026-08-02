package app.niix.core.transport

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

sealed class ControlAuth {
    data class Cookie(val bytes: ByteArray) : ControlAuth()
    data class Password(val value: String) : ControlAuth()
    object None : ControlAuth()
}

data class OnionService(val serviceId: String, val privateKey: String?)

data class ControlReply(val code: Int, val lines: List<String>) {
    val isOk: Boolean get() = code in 200..299
}

class TorControlException(message: String) : IOException(message)

class TorControlClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 15_000,
) {

    private lateinit var socket: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    fun connect() {
        socket = Socket().apply {
            connect(InetSocketAddress(host, port), connectTimeoutMillis)
            keepAlive = true
        }
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1))
    }

    fun authenticate(auth: ControlAuth) {
        val command = when (auth) {
            is ControlAuth.Cookie -> "AUTHENTICATE ${auth.bytes.toHex()}"
            is ControlAuth.Password -> "AUTHENTICATE \"${auth.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            ControlAuth.None -> "AUTHENTICATE"
        }
        send(command).requireOk("authentication failed")
    }

    fun addOnion(virtualPort: Int, targetPort: Int, existingPrivateKey: String?): OnionService {
        val keyToken = existingPrivateKey ?: "NEW:ED25519-V3"
        val reply = send("ADD_ONION $keyToken Port=$virtualPort,127.0.0.1:$targetPort")
            .requireOk("ADD_ONION failed")
        var serviceId: String? = null
        var privateKey: String? = null
        reply.lines.forEach { line ->
            when {
                line.startsWith("ServiceID=") -> serviceId = line.removePrefix("ServiceID=").trim()
                line.startsWith("PrivateKey=") -> privateKey = line.removePrefix("PrivateKey=").trim()
            }
        }
        val id = serviceId ?: throw TorControlException("ADD_ONION returned no ServiceID")
        return OnionService(id, privateKey ?: existingPrivateKey)
    }

    fun delOnion(serviceId: String) {
        runCatching { send("DEL_ONION $serviceId") }
    }

    fun close() {
        runCatching { send("QUIT") }
        runCatching { socket.close() }
    }

    @Synchronized
    private fun send(command: String): ControlReply {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return readReply()
    }

    private fun readReply(): ControlReply {
        val collected = mutableListOf<String>()
        var code = -1
        while (true) {
            val line = reader.readLine() ?: throw TorControlException("control connection closed")
            if (line.length < 4) {
                collected.add(line)
                continue
            }
            code = line.substring(0, 3).toIntOrNull() ?: code
            val separator = line[3]
            collected.add(line.substring(4))
            if (separator == '+') {
                while (true) {
                    val dataLine = reader.readLine() ?: throw TorControlException("control connection closed")
                    if (dataLine == ".") break
                    collected.add(dataLine)
                }
            } else if (separator == ' ') {
                break
            }
        }
        return ControlReply(code, collected)
    }

    private fun ControlReply.requireOk(message: String): ControlReply {
        if (!isOk) throw TorControlException("$message: ${lines.joinToString(" ")}")
        return this
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }
}
