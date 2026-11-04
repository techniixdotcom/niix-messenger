package app.niix.core.messaging

import app.niix.core.transport.DuplexConnection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Open connections to other devices, kept for the next message.
 *
 * Opening a connection through Tor to an onion service costs a round trip over six relays before
 * anything is sent, and a full circuit when the device hasn't been reached for a while. One
 * connection per message paid that every time. Here a connection stays open after a message for
 * [lingerMillis], or for as long as a conversation with the device is on screen ([hold]), so the
 * next message costs one trip and its acknowledgement one back. Holding also opens the connection
 * in advance, so the first message after opening a chat doesn't wait for it either.
 *
 * Messages to one device go one at a time over its connection, in order. A connection that turns
 * out to be dead is replaced and the frame sent once more; the receiver drops a copy it already
 * has, so that can't deliver anything twice. A device on an older build closes after one frame,
 * which looks just the same, so it simply gets a new connection each time, as before.
 *
 * A quiet connection is pinged before the receiver's read timeout would close it.
 */
internal class PeerConnections(
    private val dial: suspend (onion: String) -> DuplexConnection,
    private val ping: () -> ByteArray,
    private val scope: CoroutineScope,
    private val lingerMillis: Long = LINGER_MILLIS,
    private val pingAfterMillis: Long = PING_AFTER_MILLIS,
    private val tickMillis: Long = TICK_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Peer {
        val lock = Mutex()
        @Volatile var connection: DuplexConnection? = null
        @Volatile var lastFrameAt = 0L
        @Volatile var lastMessageAt = 0L
        @Volatile var holds = 0
        @Volatile var warmFailures = 0
        @Volatile var nextWarmAt = 0L
    }

    // Never shrinks while running: a peer is one per device ever messaged, and removing one while a
    // send was about to use it would leave a zombie connection nothing ever closes. No thx.
    private val peers = ConcurrentHashMap<String, Peer>()
    private var maintenance: Job? = null

    private fun peer(onion: String) = peers.computeIfAbsent(onion) { Peer() }

    /**
     * Sends [frame] to [onion] and returns the first byte of the reply, or -1 if there was none.
     * Throws if no connection could be opened.
     */
    suspend fun exchange(onion: String, frame: ByteArray): Int = withContext(Dispatchers.IO) {
        ensureMaintenance()
        val peer = peer(onion)
        peer.lock.withLock {
            val reused = peer.connection != null
            val first = attempt(peer, onion, frame)
            // Only a connection that had been sitting open can have gone stale. A fresh one that
            // fails has really failed, and retrying it is just wasting everyone's damn time.
            val reply = if (first < 0 && reused) attempt(peer, onion, frame) else first
            if (reply >= 0) peer.lastMessageAt = clock()
            reply
        }
    }

    /** Must be called with the peer's lock held. */
    private suspend fun attempt(peer: Peer, onion: String, frame: ByteArray): Int {
        val connection = peer.connection ?: dial(onion).also { peer.connection = it }
        val reply = try {
            connection.output.write(frame)
            connection.output.flush()
            connection.input.read()
        } catch (_: Exception) {
            -1
        }
        if (reply < 0 || reply != ACK) {
            // Refused or gone; either way the receiver has closed its end.
            close(peer)
        } else {
            peer.lastFrameAt = clock()
        }
        return reply
    }

    /**
     * Keeps connections to [onions] open, opening them now if they aren't, until [release] with the
     * same devices. Used while a conversation is on screen.
     */
    fun hold(onions: Collection<String>) {
        ensureMaintenance()
        onions.forEach { onion ->
            val peer = peer(onion)
            synchronized(peer) {
                peer.holds++
                peer.nextWarmAt = 0
            }
        }
        maintain()
    }

    fun release(onions: Collection<String>) {
        onions.forEach { onion -> peers[onion]?.let { synchronized(it) { it.holds = (it.holds - 1).coerceAtLeast(0) } } }
    }

    /** Closes everything, for a wipe or when Tor restarts. */
    fun closeAll() {
        peers.values.forEach { close(it) }
        peers.clear()
    }

    private fun close(peer: Peer) {
        runCatching { peer.connection?.close() }
        peer.connection = null
    }

    @Synchronized
    private fun ensureMaintenance() {
        if (maintenance?.isActive == true) return
        maintenance = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(tickMillis)
                maintain()
            }
        }
    }

    /** Looks after every device at once, so one slow to reach doesn't hold up the others. */
    private fun maintain() {
        peers.forEach { (onion, peer) -> scope.launch(Dispatchers.IO) { tend(onion, peer) } }
    }

    /**
     * Pings a connection that's wanted and has gone quiet, closes one that isn't wanted any more,
     * and opens one for a held device that has none. Skips a device whose lock is taken: a message
     * is going out on it right now, which keeps it alive anyway.
     */
    private suspend fun tend(onion: String, peer: Peer) {
        if (!peer.lock.tryLock()) return
        try {
            val now = clock()
            val held = peer.holds > 0
            val wanted = held || now - peer.lastMessageAt < lingerMillis
            val connection = peer.connection
            when {
                connection != null && !wanted -> close(peer)
                connection != null && now - peer.lastFrameAt >= pingAfterMillis -> sendPing(peer, onion)
                connection == null && held && now >= peer.nextWarmAt -> warm(peer, onion)
            }
        } finally {
            peer.lock.unlock()
        }
    }

    private suspend fun sendPing(peer: Peer, onion: String) {
        if (attempt(peer, onion, ping()) != ACK) peer.nextWarmAt = clock() + warmBackoff(peer)
    }

    /**
     * Opens a held device's connection ahead of the first message. A device that can't be reached,
     * or that's on an older build and closes the connection, is tried less and less often.
     */
    private suspend fun warm(peer: Peer, onion: String) {
        val ok = runCatching { attempt(peer, onion, ping()) == ACK }.getOrDefault(false)
        if (ok) {
            peer.warmFailures = 0
        } else {
            peer.warmFailures++
            peer.nextWarmAt = clock() + warmBackoff(peer)
        }
    }

    private fun warmBackoff(peer: Peer): Long =
        (WARM_RETRY_MIN_MILLIS shl (peer.warmFailures - 1).coerceIn(0, 4)).coerceAtMost(WARM_RETRY_MAX_MILLIS)

    companion object {
        /** The reply that means a frame was taken. Matches ConversationManager's FRAME_ACK. */
        const val ACK = 4

        /** How long a connection stays open after the last message, when no chat holds it. */
        const val LINGER_MILLIS = 90_000L

        /** Below the receiver's 30-second read timeout, with room for a slow circuit. */
        const val PING_AFTER_MILLIS = 20_000L
        const val TICK_MILLIS = 5_000L
        const val WARM_RETRY_MIN_MILLIS = 30_000L
        const val WARM_RETRY_MAX_MILLIS = 5L * 60 * 1000
    }
}
