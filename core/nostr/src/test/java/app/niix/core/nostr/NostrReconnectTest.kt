package app.niix.core.nostr

import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NostrReconnectTest {

    /** Fails every dial, as with no network, and records when each one happened. */
    private class OfflineDialer : Dialer {
        val dials = LinkedBlockingQueue<Long>()

        override fun dial(host: String, port: Int): Socket {
            dials.add(System.currentTimeMillis())
            throw IOException("no network")
        }
    }

    private class OneMailbox : MailboxDirectory {
        override fun ownRelays() = listOf(RelayUrl.parse("wss://relay.example.com")!!)
        override fun inboundMailboxes() = listOf(InboundMailbox("peer", ByteArray(MailboxCrypto.SECRET_BYTES) { 7 }))
        override fun decoySeed() = ByteArray(MailboxCrypto.SECRET_BYTES) { 3 }
        override fun loadCursor(): Long? = null
        override fun saveCursor(unixSeconds: Long) = Unit
        override fun loadSeen(): List<String> = emptyList()
        override fun saveSeen(ids: List<String>) = Unit
    }

    @Test
    fun `a network change redials at once instead of waiting out the backoff`() {
        val dialer = OfflineDialer()
        val mailbox = NostrMailbox(dialer)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            mailbox.start(scope, OneMailbox()) { _, _ -> true }
            assertNotNull("first dial", dialer.dials.poll(5, TimeUnit.SECONDS))
            // The backoff after a failure is at least five seconds.
            assertNull("no redial during the backoff", dialer.dials.poll(2, TimeUnit.SECONDS))

            mailbox.networkChanged()
            assertNotNull("redial right after the network changed", dialer.dials.poll(1, TimeUnit.SECONDS))
        } finally {
            mailbox.stop()
            scope.cancel()
        }
    }
}
