package app.niix.core.messaging

import app.niix.core.transport.SocketDuplexConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PeerConnections against a receiver on loopback that speaks the same framing as the app. */
class PeerConnectionsTest {

    /**
     * Acknowledges every frame. With [oneFrame] it closes after the first, as builds before
     * persistent connections did; with [closeAfterMillis] it drops a connection once it has been
     * idle that long, like a receiver whose read timed out.
     */
    private class Receiver(val oneFrame: Boolean = false, val closeAfterMillis: Int = 0) : AutoCloseable {
        private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        val frames = AtomicInteger()
        val pings = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        private fun serve(socket: Socket) = socket.use {
            if (closeAfterMillis > 0) socket.soTimeout = closeAfterMillis
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (true) {
                val type = input.read()
                if (type < 0) return
                input.readFully(ByteArray(input.readInt()))
                input.readFully(ByteArray(input.readInt()))
                if (type == PING) pings.incrementAndGet() else frames.incrementAndGet()
                output.write(PeerConnections.ACK)
                output.flush()
                if (oneFrame) return
            }
        }

        fun dial(): SocketDuplexConnection = SocketDuplexConnection(Socket(InetAddress.getLoopbackAddress(), server.localPort))

        override fun close() = server.close()
    }

    private fun frame(type: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeByte(type)
            writeInt(4)
            write("self".toByteArray())
            writeInt(3)
            write(byteArrayOf(1, 2, 3))
        }
    }.toByteArray()

    private fun pool(receiver: Receiver, scope: CoroutineScope, linger: Long = 60_000, pingAfter: Long = 60_000) =
        PeerConnections(
            dial = { receiver.dial() },
            ping = { frame(PING) },
            scope = scope,
            lingerMillis = linger,
            pingAfterMillis = pingAfter,
            tickMillis = 50,
        )

    private fun waitFor(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `messages to one device share a connection`() = Receiver().use { receiver ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val connections = pool(receiver, scope)
            runBlocking { repeat(5) { assertEquals(PeerConnections.ACK, connections.exchange("peer", frame(MESSAGE))) } }
            assertEquals(5, receiver.frames.get())
            assertEquals(1, receiver.connections.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an older receiver that closes after each frame still gets every message`() = Receiver(oneFrame = true).use { receiver ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val connections = pool(receiver, scope)
            runBlocking { repeat(3) { assertEquals(PeerConnections.ACK, connections.exchange("peer", frame(MESSAGE))) } }
            assertEquals(3, receiver.frames.get())
            assertEquals(3, receiver.connections.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a connection the receiver dropped is replaced without losing the message`() =
        Receiver(closeAfterMillis = 200).use { receiver ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val connections = pool(receiver, scope)
                runBlocking { assertEquals(PeerConnections.ACK, connections.exchange("peer", frame(MESSAGE))) }
                Thread.sleep(500)
                runBlocking { assertEquals(PeerConnections.ACK, connections.exchange("peer", frame(MESSAGE))) }
                assertEquals(2, receiver.frames.get())
                assertEquals(2, receiver.connections.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `holding a device connects in advance and keeps the connection alive`() =
        Receiver(closeAfterMillis = 400).use { receiver ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val connections = pool(receiver, scope, pingAfter = 150)
                connections.hold(listOf("peer"))
                assertTrue("not connected in advance", waitFor { receiver.connections.get() == 1 && receiver.pings.get() >= 1 })
                // Well past the receiver's idle timeout: only the pings keep it open.
                Thread.sleep(1_200)
                runBlocking { assertEquals(PeerConnections.ACK, connections.exchange("peer", frame(MESSAGE))) }
                assertEquals("the held connection wasn't kept", 1, receiver.connections.get())
                assertTrue(receiver.pings.get() >= 3)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `an unheld connection is closed once it has lingered`() = Receiver().use { receiver ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val connections = pool(receiver, scope, linger = 300)
            runBlocking { connections.exchange("peer", frame(MESSAGE)) }
            Thread.sleep(700)
            // Closed meanwhile, so this one needs a new connection.
            runBlocking { connections.exchange("peer", frame(MESSAGE)) }
            assertEquals(2, receiver.connections.get())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val MESSAGE = 1
        const val PING = 18
    }
}
