package app.niix.core.nostr

import app.niix.core.model.DiagnosticLog
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Envelopes filed under this mailbox's tags come from [peer]. */
class InboundMailbox(val peer: String, val secret: ByteArray)

interface MailboxDirectory {
    fun ownRelays(): List<RelayUrl>

    /** Throws while the app is locked, because the settings database is closed. */
    fun inboundMailboxes(): List<InboundMailbox>

    /** A secret of this device's, for choosing decoy buckets. The same every time. */
    fun decoySeed(): ByteArray

    fun loadCursor(): Long?
    fun saveCursor(unixSeconds: Long)
    fun loadSeen(): List<String>
    fun saveSeen(ids: List<String>)
}

fun interface EnvelopeSink {
    /** Returns whether the envelope was accepted. */
    suspend fun accept(peer: String, ciphertext: ByteArray): Boolean
}

/**
 * Offline delivery through Nostr relays.
 *
 * Nostr is just the transport. We publish the same Signal ciphertext a direct send would carry,
 * sealed again with the recipient's mailbox key (see [MailboxCrypto]) and signed with a one-off
 * Nostr key. None of the Nostr DM NIPs are used, so a relay never sees an identity key or a
 * recipient pubkey. All it gets is a rotating tag, a size and a timestamp.
 */
class NostrMailbox(
    private val dialer: Dialer,
    private val hostnameVerifier: HostnameVerifier? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val rng = SecureRandom()

    // Publishing

    private val createdAtLock = Any()
    private var lastCreatedAt = 0L

    // Strictly increasing, so the recipient can replay stored events in send order. A group key and
    // the message after it would otherwise often share the same second.
    private fun nextCreatedAt(): Long = synchronized(createdAtLock) {
        lastCreatedAt = maxOf(clock() / 1000, lastCreatedAt + 1)
        lastCreatedAt
    }

    /** Publishes outlive the call that started them; see [publish]. */
    private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Publishes to all [relays] in parallel. Returns as soon as one has accepted the event, with
     * the rest carrying on in the background, so a message counts as stored without waiting for
     * the slowest relay; 0 if none accepted it.
     */
    suspend fun publish(relays: List<RelayUrl>, secret: ByteArray, envelope: ByteArray): Int {
        val targets = relays.distinct().take(MAX_PUBLISH_RELAYS)
        if (targets.isEmpty()) return 0
        require(envelope.size <= MAX_ENVELOPE_BYTES) { "Envelope too large for a relay" }
        val createdAt = nextCreatedAt()
        val epoch = MailboxCrypto.epochOf(createdAt)
        val tags = listOf(
            listOf(TAG_NAME, MailboxCrypto.tag(secret, epoch)),
            listOf("expiration", (createdAt + EVENT_LIFETIME_SECONDS).toString()),
        )
        val content = MailboxCrypto.seal(secret, epoch, envelope, rng)
        val event = NostrEvent.signWithThrowawayKey(createdAt, KIND, tags, content, rng)
        val message = "[\"EVENT\",${event.toJson()}]"
        val firstAccepted = CompletableDeferred<Boolean>()
        val remaining = AtomicInteger(targets.size)
        targets.forEach { relay ->
            publishScope.launch {
                val accepted = runCatching { publishOne(relay, event.id, message) }.getOrDefault(false)
                if (accepted) firstAccepted.complete(true)
                if (remaining.decrementAndGet() == 0) firstAccepted.complete(false)
            }
        }
        return if (firstAccepted.await()) 1 else 0
    }

    // A fresh connection (and Tor circuit) per publish, so a relay can't link two envelopes to the
    // same sender by the connection they arrived on.
    private fun publishOne(relay: RelayUrl, eventId: String, message: String): Boolean {
        val ws = try {
            WebSocket.connect(relay, dialer, hostnameVerifier)
        } catch (e: Exception) {
            DiagnosticLog.record("nostr", "publish: could not reach a relay (${e::class.java.simpleName})")
            return false
        }
        try {
            ws.setReadTimeout(PUBLISH_REPLY_TIMEOUT_MILLIS)
            val deadline = System.currentTimeMillis() + PUBLISH_REPLY_TIMEOUT_MILLIS
            ws.sendText(message)
            repeat(MAX_REPLIES_BEFORE_OK) {
                val reply = Json.parseOrNull(ws.receiveText(deadline) ?: return false) as? List<*> ?: return@repeat
                if (reply.getOrNull(0) == "OK" && reply.getOrNull(1) == eventId) {
                    val reason = reply.getOrNull(3) as? String ?: ""
                    if (reply.getOrNull(2) == true || reason.startsWith("duplicate:")) return true
                    DiagnosticLog.record("nostr", "publish: relay refused the event (${reason.take(80)})")
                    return false
                }
            }
            return false
        } catch (e: Exception) {
            DiagnosticLog.record("nostr", "publish: relay connection failed (${e::class.java.simpleName})")
            return false
        } finally {
            ws.close()
        }
    }

    // Receiving

    // Holds derived per-epoch keys only, never the mailbox secrets themselves. A bucket's tag
    // maps to the mailboxes of ours in it, and a decoy's to none. Listening only makes sense once
    // someone has a mailbox; before that, an idle connection would only tell the relay we run NiiX.
    private class Snapshot(
        val relays: List<RelayUrl>,
        val byTag: Map<String, List<Pair<String, ByteArray>>>,
        val epoch: Long,
    ) {
        val listening: Boolean get() = byTag.values.any { it.isNotEmpty() }
    }

    @Volatile private var snapshot = EMPTY_SNAPSHOT
    @Volatile private var directory: MailboxDirectory? = null
    @Volatile private var sink: EnvelopeSink? = null
    private val generation = MutableStateFlow(0L)
    private val networkChanges = MutableStateFlow(0L)
    private var job: Job? = null
    private val jobLock = Any()
    private val openSockets: MutableSet<WebSocket> = Collections.synchronizedSet(HashSet())
    private val deliveryMutex = Mutex()

    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > MAX_SEEN
    }
    private var seenDirty = false

    @Volatile private var cursor: Long? = null
    @Volatile private var savedCursor: Long? = null

    /** Listens on our own relays for every contact's mailbox. Replaces any running session. */
    fun start(scope: CoroutineScope, directory: MailboxDirectory, sink: EnvelopeSink) {
        synchronized(jobLock) {
            job?.cancel()
            closeAllSockets()
            this.directory = directory
            this.sink = sink
            runCatching { directory.loadSeen() }.getOrNull()?.let { ids -> synchronized(seen) { ids.forEach { seen[it] = Unit } } }
            savedCursor = runCatching { directory.loadCursor() }.getOrNull()
            cursor = savedCursor
            rebuildSnapshot()
            job = scope.launch(Dispatchers.IO) { supervise() }
        }
    }

    /** Re-reads relays and mailboxes after a contact or setting changes. */
    fun refresh() {
        rebuildSnapshot()
        generation.value++
    }

    /**
     * The phone's network came back or changed. Connections made over the old one are dead even if
     * nothing has noticed yet, and a relay that failed while there was no network shouldn't wait
     * out its backoff now that there is one, so every relay is redialled at once.
     */
    fun networkChanged() {
        networkChanges.value++
        closeAllSockets()
    }

    fun stop() {
        synchronized(jobLock) {
            job?.cancel()
            job = null
            closeAllSockets()
        }
        saveState()
    }

    /** Stops and drops every key and id held in memory. Used by the wipe. */
    fun clear() {
        synchronized(jobLock) {
            job?.cancel()
            job = null
            closeAllSockets()
        }
        snapshot = EMPTY_SNAPSHOT
        directory = null
        sink = null
        cursor = null
        savedCursor = null
        synchronized(seen) {
            seen.clear()
            seenDirty = false
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    private fun closeAllSockets() {
        val sockets = synchronized(openSockets) { openSockets.toList().also { openSockets.clear() } }
        sockets.forEach { it.close() }
    }

    private fun rebuildSnapshot() {
        val dir = directory ?: return
        val epoch = MailboxCrypto.epochOf(clock() / 1000)
        // Fails while locked. The old snapshot stays in use, which is why it covers the next epoch
        // too.
        val (mailboxes, seed) = runCatching { dir.inboundMailboxes() to dir.decoySeed() }.getOrElse {
            DiagnosticLog.record("nostr", "mailboxes unavailable (locked?), keeping the current subscription")
            return
        }
        val relays = runCatching { dir.ownRelays() }.getOrDefault(snapshot.relays).distinct().take(MAX_OWN_RELAYS)
        val usable = mailboxes.filter { it.secret.size == MailboxCrypto.SECRET_BYTES }
        val byTag = HashMap<String, List<Pair<String, ByteArray>>>()
        for (e in epoch - EPOCHS_BACK..epoch + 1) {
            val real = HashMap<String, MutableList<Pair<String, ByteArray>>>()
            for (mailbox in usable) {
                real.getOrPut(MailboxCrypto.tag(mailbox.secret, e)) { ArrayList() }.add(mailbox.peer to MailboxCrypto.key(mailbox.secret, e))
            }
            byTag.putAll(real)
            if (real.isEmpty()) continue
            // Rounded up, so how many buckets we listen to says little about how many contacts we have.
            val padded = maxOf(MIN_BUCKETS, (real.size + BUCKET_STEP - 1) / BUCKET_STEP * BUCKET_STEP)
            MailboxCrypto.decoyTags(seed, e, padded - real.size, real.keys).forEach { byTag[it] = emptyList() }
        }
        seed.fill(0)
        snapshot = Snapshot(relays, byTag, epoch)
    }

    private suspend fun supervise() = coroutineScope {
        launch {
            while (isActive) {
                delay(EPOCH_CHECK_INTERVAL_MILLIS)
                if (MailboxCrypto.epochOf(clock() / 1000) != snapshot.epoch) refresh()
            }
        }
        while (isActive) {
            val relays = snapshot.relays
            val workers = relays.map { launch { relayLoop(it) } }
            // Tag changes are handled by resubscribing; only a new relay list needs new
            // connections.
            generation.first { snapshot.relays != relays }
            workers.forEach { it.cancel() }
            closeAllSockets()
            workers.forEach { it.join() }
        }
    }

    private suspend fun relayLoop(relay: RelayUrl) {
        var backoff = MIN_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            if (!snapshot.listening) {
                val gen = generation.value
                generation.first { it != gen }
                continue
            }
            val started = clock()
            val network = networkChanges.value
            val ws = try {
                withContext(Dispatchers.IO) { WebSocket.connect(relay, dialer, hostnameVerifier) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.record("nostr", "inbox relay unreachable (${e::class.java.simpleName})")
                null
            }
            if (ws != null) {
                openSockets.add(ws)
                try {
                    runSession(ws)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLog.record("nostr", "inbox relay connection ended (${e::class.java.simpleName})")
                } finally {
                    openSockets.remove(ws)
                    ws.close()
                }
                if (clock() - started > STABLE_SESSION_MILLIS) backoff = MIN_BACKOFF_MILLIS
            }
            val wait = backoff + rng.nextInt(backoff.toInt())
            val networkChanged = withTimeoutOrNull(wait) { networkChanges.first { it != network } } != null
            backoff = if (networkChanged) MIN_BACKOFF_MILLIS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    private class SessionState {
        @Volatile var generation = -1L
        @Volatile var caughtUp = false
        @Volatile var subscribedAt = 0L
        val backlog = ArrayList<Pending>()
    }

    private class Pending(val id: String, val createdAt: Long, val peer: String, val ciphertext: ByteArray)

    private suspend fun runSession(ws: WebSocket) = coroutineScope {
        val state = SessionState()
        subscribe(ws, state)
        val watcher = launch {
            while (isActive) {
                val gen = state.generation
                generation.first { it != gen }
                subscribe(ws, state)
            }
        }
        val keepalive = launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_INTERVAL_MILLIS)
                if (System.currentTimeMillis() - ws.lastReceivedAtMillis > DEAD_AFTER_MILLIS) {
                    DiagnosticLog.record("nostr", "inbox relay went silent, reconnecting")
                    ws.close()
                    break
                }
                runCatching { ws.sendPing() }
                // Once caught up, everything up to now has already been streamed to us.
                if (state.caughtUp) advanceCursor(clock() / 1000)
                saveState()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                while (isActive) handleRelayMessage(ws.receiveText() ?: break, state)
            }
        } finally {
            watcher.cancel()
            keepalive.cancel()
        }
    }

    private fun subscribe(ws: WebSocket, state: SessionState) {
        val tags = if (snapshot.listening) snapshot.byTag.keys.sorted() else emptyList()
        state.generation = generation.value
        synchronized(state.backlog) { state.backlog.clear() }
        state.caughtUp = false
        val now = clock() / 1000
        state.subscribedAt = now
        if (tags.isEmpty()) {
            ws.sendText("[\"CLOSE\",\"$SUBSCRIPTION_ID\"]")
            return
        }
        val since = maxOf(now - LOOKBACK_SECONDS, (cursor ?: 0L) - CURSOR_SLACK_SECONDS)
        val filters = tags.chunked(MAX_TAGS_PER_FILTER).joinToString(",") {
            "{\"kinds\":[$KIND],\"#$TAG_NAME\":${Json.stringArray(it)},\"since\":$since}"
        }
        ws.sendText("[\"REQ\",\"$SUBSCRIPTION_ID\",$filters]")
    }

    private suspend fun handleRelayMessage(text: String, state: SessionState) {
        val message = Json.parseOrNull(text) as? List<*> ?: return
        val forUs = message.getOrNull(1) == SUBSCRIPTION_ID
        when (message.getOrNull(0)) {
            "EVENT" -> if (forUs) {
                val pending = openEvent(message.getOrNull(2)) ?: return
                if (state.caughtUp) {
                    deliver(pending)
                } else {
                    synchronized(state.backlog) { if (state.backlog.size < MAX_BACKLOG) state.backlog.add(pending) }
                }
            }
            "EOSE" -> if (forUs) {
                val batch = synchronized(state.backlog) { state.backlog.toList().also { state.backlog.clear() } }
                // Relays send stored events newest first; replay them in the order they were sent.
                batch.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { deliver(it) }
                state.caughtUp = true
                advanceCursor(state.subscribedAt)
                saveState()
            }
            "CLOSED" -> if (forUs) {
                throw IOException("Relay closed the subscription: ${(message.getOrNull(2) as? String ?: "").take(80)}")
            }
            "NOTICE" -> DiagnosticLog.record("nostr", "relay notice: ${(message.getOrNull(1) as? String ?: "").take(80)}")
        }
    }

    private fun openEvent(raw: Any?): Pending? {
        val event = NostrEvent.fromJson(raw) ?: return null
        if (event.kind != KIND || event.content.length > MAX_CONTENT_CHARS) return null
        val tag = event.tagValue(TAG_NAME) ?: return null
        val mailboxes = snapshot.byTag[tag] ?: return null
        if (isSeen(event.id)) return null
        for ((peer, key) in mailboxes) {
            val ciphertext = MailboxCrypto.open(key, tag, event.content) ?: continue
            return Pending(event.id, event.createdAt, peer, ciphertext)
        }
        // Someone else's envelope in a bucket we listen to, which is most of what a bucket holds.
        // Not remembered as seen: there are too many, and trying a key costs next to nothing.
        return null
    }

    private suspend fun deliver(pending: Pending) = deliveryMutex.withLock {
        if (isSeen(pending.id)) return@withLock
        val target = sink ?: return@withLock
        val accepted = try {
            target.accept(pending.peer, pending.ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        // Marked seen even on failure: Signal won't decrypt the same ciphertext twice, so retrying
        // this event is pointless. The sender republishes until it gets a receipt.
        markSeen(pending.id)
        if (!accepted) DiagnosticLog.record("nostr", "envelope from a relay was not accepted, sender will republish")
    }

    private fun isSeen(id: String): Boolean = synchronized(seen) { seen.containsKey(id) }

    private fun markSeen(id: String) = synchronized(seen) {
        seen[id] = Unit
        seenDirty = true
    }

    private fun advanceCursor(unixSeconds: Long) {
        cursor = maxOf(cursor ?: 0L, unixSeconds)
    }

    // Seen ids and the cursor are written in batches rather than per event: after EOSE, once a
    // minute and on stop. Losing the last minute in a crash only means a few duplicates on the next
    // start, and Signal rejects those anyway.
    private fun saveState() {
        val dir = directory ?: return
        val ids = synchronized(seen) {
            if (!seenDirty) null else seen.keys.toList().takeLast(MAX_PERSISTED_SEEN).also { seenDirty = false }
        }
        if (ids != null && runCatching { dir.saveSeen(ids) }.isFailure) {
            synchronized(seen) { seenDirty = true }
        }
        val current = cursor ?: return
        if (current - (savedCursor ?: 0L) >= CURSOR_SAVE_STEP_SECONDS && runCatching { dir.saveCursor(current) }.isSuccess) {
            savedCursor = current
        }
    }

    companion object {
        /** Regular (stored) kind, not used by any NIP. */
        const val KIND = 4459

        /** Relays only index single-letter tags. */
        const val TAG_NAME = "z"

        /** Public relays mostly cap events near 64 KiB; 40 KiB is about 55 KiB once base64'd. */
        const val MAX_ENVELOPE_BYTES = 40 * 1024

        const val MAX_OWN_RELAYS = 6

        private val EMPTY_SNAPSHOT = Snapshot(emptyList(), emptyMap(), Long.MIN_VALUE)
        private const val SUBSCRIPTION_ID = "niix"
        private const val EVENT_LIFETIME_SECONDS = 14L * 24 * 60 * 60
        private const val MAX_PUBLISH_RELAYS = 6
        private const val PUBLISH_REPLY_TIMEOUT_MILLIS = 45_000
        private const val MAX_REPLIES_BEFORE_OK = 16
        private const val MAX_TAGS_PER_FILTER = 100
        private const val MAX_CONTENT_CHARS = 128 * 1024
        private const val MAX_BACKLOG = 5_000
        private const val MAX_SEEN = 2_048
        private const val MAX_PERSISTED_SEEN = 512

        /** We listen to at least this many buckets an epoch, and to a multiple of [BUCKET_STEP]. */
        private const val MIN_BUCKETS = 16
        private const val BUCKET_STEP = 16

        // Two past epochs plus the current one cover the whole event lifetime.
        private const val EPOCHS_BACK = 2L
        private const val LOOKBACK_SECONDS = EVENT_LIFETIME_SECONDS + 24 * 60 * 60

        // Covers senders whose clocks run behind. Anything fetched twice is dropped as seen.
        private const val CURSOR_SLACK_SECONDS = 6L * 60 * 60
        private const val CURSOR_SAVE_STEP_SECONDS = 10L * 60

        private const val PING_INTERVAL_MILLIS = 60_000L
        private const val DEAD_AFTER_MILLIS = 3 * 60_000L
        private const val EPOCH_CHECK_INTERVAL_MILLIS = 10 * 60_000L
        private const val MIN_BACKOFF_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 5 * 60_000L
        private const val STABLE_SESSION_MILLIS = 2 * 60_000L
    }
}
