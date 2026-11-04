package app.niix.core.nostr

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receiving through shared buckets, against a small relay running on loopback: the subscription
 * asks for buckets padded with decoys rather than for mailboxes, and of the envelopes in a bucket
 * only our own are delivered.
 */
class NostrBucketTest {

    /** Stores events and serves them to subscriptions, like a relay with no rules. */
    private class LoopbackRelay : AutoCloseable {
        private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        private val events = Collections.synchronizedList(ArrayList<String>())
        val requests = LinkedBlockingQueue<List<*>>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        val dialer = Dialer { _, _ -> Socket(InetAddress.getLoopbackAddress(), server.localPort) }

        private fun serve(socket: Socket) = socket.use {
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            handshake(input, output)
            while (true) {
                val message = Json.parseOrNull(readFrame(input) ?: return) as? List<*> ?: continue
                when (message[0]) {
                    "EVENT" -> {
                        val event = message[1] as Map<*, *>
                        events.add(rawEvent(event))
                        send(output, """["OK","${event["id"]}",true,""]""")
                    }
                    "REQ" -> {
                        requests.add(message)
                        val wanted = message.drop(2).flatMap { ((it as Map<*, *>)["#z"] as List<*>) }.toSet()
                        synchronized(events) { events.toList() }.forEach { raw ->
                            val event = NostrEvent.fromJson(Json.parseOrNull(raw))!!
                            if (event.tagValue("z") in wanted) send(output, """["EVENT","${message[1]}",$raw]""")
                        }
                        send(output, """["EOSE","${message[1]}"]""")
                    }
                }
            }
        }

        // The relay gets the event as parsed JSON; it goes back out as the same JSON.
        private fun rawEvent(event: Map<*, *>): String = NostrEvent.fromJson(event)!!.toJson()

        private fun handshake(input: DataInputStream, output: OutputStream) {
            val lines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            val key = lines.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            output.flush()
        }

        private fun readLine(input: DataInputStream): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) return line.toString().trimEnd('\r')
                line.append(c.toChar())
            }
        }

        /** The next text frame, answering pings on the way. Client frames are always masked. */
        private fun readFrame(input: DataInputStream): String? {
            while (true) {
                val b0 = input.read().takeIf { it >= 0 } ?: return null
                var length = (input.readUnsignedByte() and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
                val mask = ByteArray(4).also(input::readFully)
                val payload = ByteArray(length.toInt()).also(input::readFully)
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x1 -> return String(payload)
                    0x8 -> return null
                }
            }
        }

        private fun send(output: OutputStream, text: String) = synchronized(output) {
            val payload = text.toByteArray()
            output.write(0x81)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }

        override fun close() = server.close()
    }

    private class Directory(private val mailboxes: List<InboundMailbox>) : MailboxDirectory {
        override fun ownRelays() = listOf(RELAY)
        override fun inboundMailboxes() = mailboxes
        override fun decoySeed() = ByteArray(MailboxCrypto.SECRET_BYTES) { 9 }
        override fun loadCursor(): Long? = null
        override fun saveCursor(unixSeconds: Long) = Unit
        override fun loadSeen(): List<String> = emptyList()
        override fun saveSeen(ids: List<String>) = Unit
    }

    private val rng = SecureRandom()

    /** A different secret whose mailbox lands in the same bucket as [secret]'s this epoch. */
    private fun neighbourOf(secret: ByteArray, epoch: Long): ByteArray {
        while (true) {
            val other = MailboxCrypto.newSecret(rng)
            if (MailboxCrypto.tag(other, epoch) == MailboxCrypto.tag(secret, epoch)) return other
        }
    }

    @Test
    fun `only our envelopes are delivered from a shared bucket, and the subscription is padded`() {
        val epoch = MailboxCrypto.epochOf(System.currentTimeMillis() / 1000)
        val ours = MailboxCrypto.newSecret(rng)
        val stranger = neighbourOf(ours, epoch)

        LoopbackRelay().use { relay ->
            val sender = NostrMailbox(relay.dialer)
            runBlocking {
                assertEquals(1, sender.publish(listOf(RELAY), stranger, "someone else's".toByteArray()))
                assertEquals(1, sender.publish(listOf(RELAY), ours, "for us".toByteArray()))
            }

            val delivered = LinkedBlockingQueue<Pair<String, String>>()
            val receiver = NostrMailbox(relay.dialer)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                receiver.start(scope, Directory(listOf(InboundMailbox("alice", ours)))) { peer, ciphertext ->
                    delivered.add(peer to String(ciphertext))
                    true
                }
                val request = relay.requests.poll(10, TimeUnit.SECONDS)
                assertNotNull("the receiver subscribed", request)
                val tags = request!!.drop(2).flatMap { ((it as Map<*, *>)["#z"] as List<*>) }
                // Four epochs, each padded to sixteen buckets, though only one mailbox exists.
                assertEquals(4 * 16, tags.distinct().size)
                assertTrue(MailboxCrypto.tag(ours, epoch) in tags)

                assertEquals("alice" to "for us", delivered.poll(10, TimeUnit.SECONDS))
                assertNull("the stranger's envelope stays undelivered", delivered.poll(1, TimeUnit.SECONDS))
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `decoys are stable, avoid the real buckets and depend on the seed`() {
        val seed = ByteArray(MailboxCrypto.SECRET_BYTES) { 1 }
        val real = setOf(MailboxCrypto.tag(MailboxCrypto.newSecret(rng), 5))
        val decoys = MailboxCrypto.decoyTags(seed, 5, 15, real)
        assertEquals(15, decoys.distinct().size)
        assertTrue(decoys.none { it in real })
        assertEquals(decoys, MailboxCrypto.decoyTags(seed, 5, 15, real))
        assertTrue(decoys != MailboxCrypto.decoyTags(ByteArray(MailboxCrypto.SECRET_BYTES) { 2 }, 5, 15, real))
        assertTrue(decoys != MailboxCrypto.decoyTags(seed, 6, 15, real))
    }

    @Test
    fun `mailboxes share a small set of bucket tags`() {
        val buckets = (0 until MailboxCrypto.BUCKETS).map { MailboxCrypto.bucketTag(1, it) }.toSet()
        val tags = (1..5_000).map { MailboxCrypto.tag(MailboxCrypto.newSecret(rng), 1) }.toSet()
        assertTrue(tags.size < 5_000)
        assertTrue(tags.all { it in buckets })
    }

    private companion object {
        val RELAY = RelayUrl.parse("ws://" + "a".repeat(56) + ".onion")!!
    }
}
