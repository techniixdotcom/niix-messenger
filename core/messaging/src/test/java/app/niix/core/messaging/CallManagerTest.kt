package app.niix.core.messaging

import app.niix.core.transport.DuplexConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Two (or three) CallManagers talking to each other for real: signalling handed straight across as
 * the messaging layer would after decrypting it, and the audio over loopback sockets standing in
 * for Tor. Only the phone's audio is faked.
 */
class CallManagerTest {

    private lateinit var scope: CoroutineScope
    private val peers = ConcurrentHashMap<String, Peer>()

    private val alice = "a".repeat(56) + ".onion"
    private val bob = "b".repeat(56) + ".onion"
    private val carol = "c".repeat(56) + ".onion"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        peers.values.forEach { it.close() }
        scope.cancel()
    }

    private inner class Peer(val account: String, val marker: Byte) {
        /** Who this side allows calls with. */
        val allows: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** What this side believes the other side allows; normally the truth. */
        val believedAllowedBy: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile var clockSkewMillis = 0L
        @Volatile var audioFails = false
        @Volatile var unreachable = false
        @Volatile var deliveryDelayMillis = 0L
        val heard = CopyOnWriteArrayList<ByteArray>()
        val missed = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        val manager: CallManager = CallManager(
            host = object : CallHost {
                override fun selfAccount() = account
                override fun selfDevice() = account
                override fun canCall(account: String) = account in allows && account in believedAllowedBy
                override fun canRing(account: String) = account in allows
                override suspend fun sendToDevices(account: String, wire: WireMessage, deviceIds: Set<Int>?): Set<Int> {
                    val other = peers[account] ?: return emptySet()
                    if (other.unreachable) return emptySet()
                    if (deliveryDelayMillis > 0) kotlinx.coroutines.delay(deliveryDelayMillis)
                    other.receive(this@Peer.account, wire)
                    return setOf(1)
                }
                override fun deviceIds(account: String) = setOf(1)
                override fun deviceOnion(account: String, deviceId: Int) = if (deviceId == 1) account else null
                override suspend fun connect(onion: String): DuplexConnection =
                    SocketConnection(Socket(InetAddress.getLoopbackAddress(), peers.getValue(onion).port))
                override fun wallClockMillis() = System.currentTimeMillis() + clockSkewMillis
            },
            audio = CallAudio {
                if (audioFails) throw IllegalStateException("No microphone")
                object : CallAudioSession {
                    private var n = 0
                    override val codec = CallCodecs.OPUS
                    override fun capture(): List<ByteArray> {
                        Thread.sleep(MediaRecord.RECORD_MILLIS)
                        return List(3) { byteArrayOf(marker, (n++).toByte()) }
                    }
                    override fun play(codec: Int, packets: List<ByteArray>) {
                        heard.addAll(packets)
                    }
                    override fun setSpeaker(on: Boolean) = Unit
                    override fun close() = Unit
                }
            },
            scope = scope,
        )

        val port: Int get() = server.localPort

        init {
            peers[account] = this
            scope.launch { manager.missed.collect { missed.add(it) } }
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    scope.launch {
                        val connection = SocketConnection(socket)
                        try {
                            val input = DataInputStream(connection.input)
                            if (input.readUnsignedByte() != CallManager.FRAME_CALL) return@launch
                            val sender = String(ByteArray(input.readInt()).also(input::readFully), Charsets.UTF_8)
                            manager.serveMedia(sender, input, connection) {}
                        } catch (_: Exception) {
                        } finally {
                            connection.close()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        /** What ConversationManager's dispatch does with call messages. */
        fun receive(from: String, wire: WireMessage) {
            when (wire) {
                is WireMessage.CallOffer -> manager.onOffer(from, 1, wire)
                is WireMessage.CallAnswer -> manager.onAnswer(from, 1, wire)
                is WireMessage.CallEnd -> manager.onEnd(from, 1, wire)
                else -> Unit
            }
        }

        fun close() {
            manager.endAll()
            runCatching { server.close() }
        }
    }

    private class SocketConnection(private val socket: Socket) : DuplexConnection {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()
        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** Two people who have both allowed calls with each other. */
    private fun pair(): Pair<Peer, Peer> {
        val a = Peer(alice, 'A'.code.toByte())
        val b = Peer(bob, 'B'.code.toByte())
        a.allows += bob; a.believedAllowedBy += bob
        b.allows += alice; b.believedAllowedBy += alice
        return a to b
    }

    private fun awaitPhase(peer: Peer, phase: CallPhase, timeoutMillis: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (peer.manager.state.value.phase == phase) return
            Thread.sleep(20)
        }
        fail("${peer.account.take(1)} never reached $phase; it's ${peer.manager.state.value}")
    }

    private fun awaitEnded(peer: Peer, reason: CallEnded? = null, timeoutMillis: Long = 8_000) {
        awaitPhase(peer, CallPhase.ENDED, timeoutMillis)
        if (reason != null) assertEquals(reason, peer.manager.state.value.ended)
    }

    private fun awaitCondition(what: String, timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun `a call connects, audio flows both ways, and hanging up ends it on both sides`() {
        val (a, b) = pair()
        assertTrue(a.manager.startCall(bob))
        awaitPhase(b, CallPhase.INCOMING)
        assertEquals(alice, b.manager.state.value.peer)
        assertEquals(CallPhase.OUTGOING, a.manager.state.value.phase)

        b.manager.answer()
        awaitPhase(a, CallPhase.ACTIVE)
        awaitPhase(b, CallPhase.ACTIVE)
        awaitCondition("audio both ways") { a.heard.size > 6 && b.heard.size > 6 }
        val code = a.manager.state.value.safetyCode
        assertTrue(code != null && code == b.manager.state.value.safetyCode)
        assertTrue(a.heard.all { it[0] == 'B'.code.toByte() })
        assertTrue(b.heard.all { it[0] == 'A'.code.toByte() })
        // In order: the record stream is ordered and authenticated.
        val fromA = b.heard.map { it[1].toInt() and 0xff }
        assertEquals(fromA.indices.map { it % 256 }, fromA.take(fromA.size))

        a.manager.hangUp()
        awaitEnded(a, CallEnded.HUNG_UP)
        awaitEnded(b, CallEnded.REMOTE_HUNG_UP)
        assertTrue(a.missed.isEmpty() && b.missed.isEmpty())
    }

    @Test
    fun `muted audio still sends records but no packets`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.answer()
        awaitPhase(b, CallPhase.ACTIVE)
        a.manager.setMuted(true)
        Thread.sleep(300)
        val before = b.heard.size
        Thread.sleep(600)
        assertEquals(before, b.heard.size)
        assertEquals(CallPhase.ACTIVE, b.manager.state.value.phase)
        a.manager.setMuted(false)
        awaitCondition("audio again after unmuting") { b.heard.size > before + 3 }
        b.manager.hangUp()
        awaitEnded(a, CallEnded.REMOTE_HUNG_UP)
    }

    @Test
    fun `a call to someone who has not allowed it never rings`() {
        val (a, b) = pair()
        b.allows.clear()
        // Alice's phone still thinks Bob allows it, say because his withdrawal hasn't arrived.
        a.manager.startCall(bob)
        awaitEnded(a, CallEnded.NOT_PERMITTED)
        Thread.sleep(200)
        assertEquals(CallPhase.IDLE, b.manager.state.value.phase)
        assertTrue(b.missed.isEmpty())
    }

    @Test
    fun `the caller can't call without allowing it too`() {
        val (a, b) = pair()
        a.allows.clear()
        a.manager.startCall(bob)
        awaitEnded(a, CallEnded.NOT_PERMITTED)
        Thread.sleep(200)
        assertEquals(CallPhase.IDLE, b.manager.state.value.phase)
    }

    @Test
    fun `declining ends it for the caller`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.decline()
        awaitEnded(b, CallEnded.DECLINED)
        awaitEnded(a, CallEnded.DECLINED)
    }

    @Test
    fun `cancelling while it rings is a missed call`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        a.manager.hangUp()
        awaitEnded(a, CallEnded.HUNG_UP)
        awaitEnded(b, CallEnded.MISSED)
        awaitCondition("a missed call") { b.missed == listOf(alice) }
    }

    @Test
    fun `an unreachable callee ends the call`() {
        val (a, b) = pair()
        b.unreachable = true
        a.manager.startCall(bob)
        awaitEnded(a, CallEnded.UNREACHABLE)
    }

    @Test
    fun `an offer that waited too long is not rung, only reported`() {
        val (a, b) = pair()
        b.clockSkewMillis = CallManager.OFFER_MAX_AGE_MILLIS + 60_000
        a.manager.startCall(bob)
        awaitCondition("a missed call") { b.missed == listOf(alice) }
        assertEquals(CallPhase.IDLE, b.manager.state.value.phase)
        a.manager.hangUp()
    }

    @Test
    fun `a second caller gets busy`() {
        val (a, b) = pair()
        val c = Peer(carol, 'C'.code.toByte())
        c.allows += bob; c.believedAllowedBy += bob
        b.allows += carol; b.believedAllowedBy += carol
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.answer()
        awaitPhase(b, CallPhase.ACTIVE)

        c.manager.startCall(bob)
        awaitEnded(c, CallEnded.BUSY)
        awaitCondition("a missed call from the second caller") { b.missed == listOf(carol) }
        assertEquals(CallPhase.ACTIVE, b.manager.state.value.phase)
        assertEquals(alice, b.manager.state.value.peer)
        a.manager.hangUp()
    }

    @Test
    fun `two people calling each other at once end up in one call`() {
        val (a, b) = pair()
        // Neither offer arrives before both have dialled.
        a.deliveryDelayMillis = 300
        b.deliveryDelayMillis = 300
        assertTrue(a.manager.startCall(bob))
        assertTrue(b.manager.startCall(alice))
        awaitPhase(a, CallPhase.ACTIVE)
        awaitPhase(b, CallPhase.ACTIVE)
        awaitCondition("audio both ways") { a.heard.size > 3 && b.heard.size > 3 }
        a.manager.hangUp()
        awaitEnded(b, CallEnded.REMOTE_HUNG_UP)
    }

    @Test
    fun `a connection without the call's proof is refused, and the real call still connects`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        Socket(InetAddress.getLoopbackAddress(), b.port).use { socket ->
            DataOutputStream(socket.getOutputStream()).run {
                val sender = alice.toByteArray()
                val id = java.util.UUID.randomUUID().toString().toByteArray()
                writeByte(CallManager.FRAME_CALL)
                writeInt(sender.size); write(sender)
                writeInt(id.size); write(id)
                writeInt(32); write(ByteArray(32))
                flush()
            }
            socket.soTimeout = 5_000
            assertEquals(-1, socket.getInputStream().read())
        }
        b.manager.answer()
        awaitPhase(a, CallPhase.ACTIVE)
        awaitPhase(b, CallPhase.ACTIVE)
        a.manager.hangUp()
    }

    @Test
    fun `a callee whose microphone fails ends the call on both sides`() {
        val (a, b) = pair()
        b.audioFails = true
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.answer()
        awaitEnded(b, CallEnded.AUDIO_FAILED)
        awaitEnded(a)
    }

    @Test
    fun `withdrawing permission mid-call ends it`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.answer()
        awaitPhase(a, CallPhase.ACTIVE)
        b.manager.permissionWithdrawn(alice, byThem = false)
        awaitEnded(b, CallEnded.HUNG_UP)
        awaitEnded(a)
    }

    @Test
    fun `only one call at a time`() {
        val (a, _) = pair()
        assertTrue(a.manager.startCall(bob))
        assertTrue(!a.manager.startCall(carol))
        a.manager.hangUp()
    }

    @Test
    fun `after ending, the state goes back to idle`() {
        val (a, b) = pair()
        a.manager.startCall(bob)
        awaitPhase(b, CallPhase.INCOMING)
        b.manager.decline()
        awaitEnded(a)
        awaitPhase(a, CallPhase.IDLE, timeoutMillis = 10_000)
        assertTrue(a.manager.startCall(bob))
        a.manager.hangUp()
    }
}
