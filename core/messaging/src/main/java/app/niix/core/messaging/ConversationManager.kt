package app.niix.core.messaging

import app.niix.core.crypto.AlreadyReceivedException
import app.niix.core.crypto.CryptoEngine
import app.niix.core.crypto.SessionMismatchException
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
import app.niix.core.model.Contact
import app.niix.core.model.Conversation
import app.niix.core.model.DiagnosticLog
import app.niix.core.model.GroupMembershipPolicy
import app.niix.core.model.ConversationType
import app.niix.core.model.clampDisappearSeconds
import app.niix.core.model.DeliveryState
import app.niix.core.model.Device
import app.niix.core.model.DeviceList
import app.niix.core.model.GroupMember
import app.niix.core.model.GroupRole
import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.model.OnionAddress
import app.niix.core.model.TrustState
import app.niix.core.nostr.NostrMailbox
import app.niix.core.nostr.NostrStore
import app.niix.core.storage.AttachmentFiles
import app.niix.core.storage.GroupSenderKeyState
import app.niix.core.storage.PendingGroupInvite
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.core.storage.WipeBeacon
import app.niix.core.transport.DuplexConnection
import app.niix.core.transport.TorTransport
import app.niix.core.transport.TransportState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class IncomingNotice(val conversationId: String, val title: String, val preview: String)

/** Whether a voice call with a contact is possible, and if not, why. */
enum class CallAvailability {
    /** Not someone to call: not a contact, blocked, an unanswered request, or no session yet. */
    UNAVAILABLE,

    /** A contact, but their safety number hasn't been verified, which calls need. */
    NEEDS_VERIFICATION,

    /** The user hasn't allowed calls with them. */
    NOT_ALLOWED,

    /** The user allowed calls; they haven't, or their answer hasn't arrived. */
    WAITING_FOR_THEM,

    /** Both allowed it. */
    READY,
}

class ConversationManager(
    private val storage: SecureStorage,
    private val crypto: CryptoEngine,
    private val transport: TorTransport,
    private val attachmentsDir: File,
    private val servicePort: Int,
    private val selfOnionProvider: () -> String?,
    private val sendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),

    /** Offline delivery through Nostr, used when a direct send fails. */
    private val nostr: NostrMailbox? = null,

    /** The phone's microphone and speaker, for calls. Without it calls fail as soon as they connect. */
    callAudio: CallAudio? = null,
) {

    /** This device's own address, which frames are sent from. */
    private fun selfDevice(): String = selfOnionProvider() ?: SELF

    /**
     * Voice calls, only ever between two people who have both allowed them. See CallManager for
     * how they work and CallPermissions for who may call whom.
     */
    val calls: CallManager = CallManager(
        host = object : CallHost {
            override fun selfAccount(): String = this@ConversationManager.selfAccount()
            override fun selfDevice(): String = this@ConversationManager.selfDevice()
            override fun canCall(account: String): Boolean = callAvailabilityNow(account) == CallAvailability.READY
            override fun canRing(account: String): Boolean =
                isCallablePeer(account) && isVerifiedForCalls(account) &&
                    storage.callPermissions.allowedByMe(account, crypto.remoteIdentityKeyBytes(account))
            override suspend fun sendToDevices(account: String, wire: WireMessage, deviceIds: Set<Int>?): Set<Int> =
                sendCallSignal(account, wire, deviceIds)
            override fun deviceIds(account: String): Set<Int> = recipientDevices(account).map { it.id }.toSet()
            override fun deviceOnion(account: String, deviceId: Int): String? =
                recipientDevices(account).firstOrNull { it.id == deviceId }?.onion?.value
            override suspend fun connect(onion: String): DuplexConnection =
                transport.connect(OnionAddress.parse(onion), servicePort)
            override fun wallClockMillis(): Long = System.currentTimeMillis()
        },
        audio = callAudio,
        scope = sendScope,
    )

    /** Connections to other devices, kept open between messages. See PeerConnections. */
    private val connections = PeerConnections(
        dial = { onion -> transport.connect(OnionAddress.parse(onion), servicePort) },
        ping = { frameBytes(FRAME_PING, selfDevice(), ByteArray(0)) },
        scope = sendScope,
    )

    /** Conversations on screen, and the devices held for each once they've been worked out. */
    private val warmConversations = HashMap<String, List<String>?>()

    /**
     * Called when a conversation comes on screen and goes off it. While it's on screen the
     * connections to its devices are opened in advance and kept open, so a message goes out the
     * moment it's sent instead of waiting for Tor to connect first. Only for people we talk to.
     *
     * The bookkeeping happens here, in call order, so leaving and coming straight back can't end
     * with the release overtaking the hold; only finding the devices happens in the background.
     */
    fun keepConversationWarm(conversationId: String, warm: Boolean) {
        synchronized(warmConversations) {
            if (!warm) {
                warmConversations.remove(conversationId)?.let(connections::release)
                return
            }
            if (warmConversations.containsKey(conversationId)) return
            warmConversations[conversationId] = null
        }
        sendScope.launch {
            val devices = runCatching { warmDevices(conversationId) }.getOrDefault(emptyList())
            synchronized(warmConversations) {
                // Left the conversation while the devices were being looked up.
                if (!warmConversations.containsKey(conversationId) || devices.isEmpty()) return@launch
                warmConversations[conversationId] = devices
                connections.hold(devices)
            }
        }
    }

    private fun warmDevices(conversationId: String): List<String> {
        val conversation = storage.conversations.get(conversationId) ?: return emptyList()
        if (conversation.pending) return emptyList()
        val accounts = when (conversation.type) {
            ConversationType.DIRECT -> listOf(conversation.id)
            ConversationType.GROUP -> storage.members.listForConversation(conversation.id)
                .map { it.memberOnion.value }.filter { it != selfAccount() }.take(MAX_WARM_GROUP_MEMBERS)
        }
        return accounts.filter { it == selfAccount() || isChosenPeer(it) }
            .flatMap { account -> recipientDevices(account).filter { crypto.hasSession(account, it.id) } }
            .map { it.onion.value }
    }

    /**
     * The account's address: how contacts and groups know us, whichever of its devices this is. On
     * the device the account was created on it's the device's own address.
     */
    private fun selfAccount(): String = storage.settings.getString(SettingsStore.KEY_ACCOUNT) ?: selfDevice()

    private val nostrStore: NostrStore by lazy { NostrStore(storage.settings) }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    /**
     * Called when a valid remote wipe arrives. The app layer does the actual wipe, since this class
     * can't delete the database it's reading from.
     */
    var onRemoteWipe: (() -> Unit)? = null

    private val _incoming = MutableSharedFlow<IncomingNotice>(extraBufferCapacity = 32)
    val incoming: SharedFlow<IncomingNotice> = _incoming.asSharedFlow()

    private val attachmentByteLimiter = SlidingWindowLimiter(maxWeight = 200L * 1024 * 1024, windowMillis = 60_000)

    /**
     * Per-sender limits on accepted messages.
     *
     * Connection limits cap what one connection costs, but a real contact could still send unique
     * message ids forever and fill the database. This is checked before decryption or any write, so
     * a flood is cheap to refuse. It's per sender so one noisy peer can't block everyone else, and
     * in memory only: it's there to blunt a flood, not to keep a permanent record.
     */
    private val senderMessageLimiters = java.util.concurrent.ConcurrentHashMap<String, SlidingWindowLimiter>()
    private val senderByteLimiters = java.util.concurrent.ConcurrentHashMap<String, SlidingWindowLimiter>()

    private fun senderWithinQuota(fromOnion: String, size: Int): Boolean {
        // Anyone can connect from a new address, so without this the maps would grow forever and
        // some script kiddie could eat all our memory. Only idle limiters are dropped, and an idle
        // one behaves the same as a fresh one.
        if (senderMessageLimiters.size > LIMITER_SWEEP_THRESHOLD) {
            senderMessageLimiters.entries.removeIf { it.value.isIdle() }
            senderByteLimiters.entries.removeIf { it.value.isIdle() }
        }
        val messages = senderMessageLimiters.computeIfAbsent(fromOnion) {
            SlidingWindowLimiter(maxWeight = MAX_MESSAGES_PER_SENDER_PER_MINUTE, windowMillis = 60_000)
        }
        val bytes = senderByteLimiters.computeIfAbsent(fromOnion) {
            SlidingWindowLimiter(maxWeight = MAX_BYTES_PER_SENDER_PER_MINUTE, windowMillis = 60_000)
        }
        // Count the message first, so one huge message can't get through just because the byte
        // window has room.
        if (!messages.allow()) return false
        return bytes.allow(size.toLong())
    }

    /** Drops what's held in memory about peers. Called on a wipe, so nothing carries over. */
    fun forgetRuntimeState() {
        senderMessageLimiters.clear()
        senderByteLimiters.clear()
        nostrAnnounceAttemptAt.clear()
        nostrPublishedAt.clear()
        verifiedFirstContacts.clear()
        coverTrafficMisses.clear()
        directFailedAt.clear()
        sessionRestartedAt.clear()
        sessionSetup.clear()
        blobsDelivered.clear()
        synchronized(warmConversations) { warmConversations.clear() }
        connections.closeAll()
        sessionSetup.clear()
        calls.endAll()
    }

    private fun emitIncoming(conversationId: String, sender: String, preview: String) {
        val title = storage.conversations.get(conversationId)?.title ?: sender.take(12)
        _incoming.tryEmit(IncomingNotice(conversationId, title, preview))
    }

    suspend fun listConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        storage.conversations.list()
    }

    suspend fun conversation(id: String): Conversation? = withContext(Dispatchers.IO) {
        storage.conversations.get(id)
    }

    suspend fun listContacts(): List<Contact> = withContext(Dispatchers.IO) {
        storage.contacts.list()
    }

    suspend fun pendingRequests(): List<Conversation> = withContext(Dispatchers.IO) {
        storage.conversations.list().filter { it.pending }
    }

    suspend fun acceptRequest(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (!conversation.pending) return@withContext false
        if (!storage.contacts.isKnown(conversationId)) {
            storage.contacts.upsert(
                Contact(
                    onionAddress = OnionAddress.parse(conversationId),
                    displayName = conversation.title,
                    fingerprint = crypto.remoteFingerprint(conversationId) ?: IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = now(),
                ),
            )
        }
        storage.conversations.setPending(conversationId, false)
        syncContact(conversationId)
        notifyChanged()
        // Accepting is what lets them see who we are and gives them a mailbox.
        sendScope.launch {
            runCatching { sendProfileTo(conversationId) }
            runCatching { maybeAnnounceNostrInbox(conversationId) }
        }
        true
    }

    suspend fun blockRequest(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (!conversation.pending) return@withContext false
        storage.blocklist.block(conversationId)
        syncContact(conversationId)
        storage.attachments.listForConversation(conversationId).forEach { runCatching { storage.deleteAttachmentFile(it.id) } }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        storage.conversations.delete(conversationId)
        notifyChanged()
        true
    }

    suspend fun messagesFor(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        storage.messages.listForConversation(conversationId)
    }

    suspend fun pendingGroupInvites(): List<PendingGroupInvite> = withContext(Dispatchers.IO) {
        storage.pendingGroupInvites.list()
    }

    suspend fun acceptGroupInvite(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val invite = storage.pendingGroupInvites.get(conversationId) ?: return@withContext false
        storage.conversations.upsert(
            Conversation(
                id = conversationId,
                type = ConversationType.GROUP,
                title = invite.title,
                disappearSeconds = 0,
                createdAtEpochMillis = now(),
                epoch = invite.epoch,
            ),
        )
        storage.members.replaceAll(conversationId, invite.members, invite.admins)
        // Record the state-chain baseline as soon as the group exists here, otherwise the first
        // membership change has nothing to be checked against.
        rememberGroupState(conversationId)
        storage.pendingGroupInvites.delete(conversationId)
        notifyChanged()
        true
    }

    suspend fun rejectGroupInvite(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val existed = storage.pendingGroupInvites.get(conversationId) != null
        storage.pendingGroupInvites.delete(conversationId)
        if (existed) notifyChanged()
        existed
    }

    suspend fun lastMessage(conversationId: String): Message? = withContext(Dispatchers.IO) {
        storage.messages.latest(conversationId)
    }

    /**
     * Asks [peerOnion] to erase their device.
     *
     * The recipient never acknowledges this on purpose, so all we can honestly say is that it was
     * sent.
     */
    suspend fun sendRemoteWipe(peerOnion: String) = withContext(Dispatchers.IO) {
        runCatching {
            ensureSession(peerOnion)
            sendWireDirectOrRelay(peerOnion, WireMessage.RemoteWipe(""))
        }
        Unit
    }

    /**
     * Tells a contact they may (or may no longer) erase this device. Sent straight away so their
     * list stays accurate.
     */
    suspend fun sendWipeNomination(peerOnion: String, nominated: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            ensureSession(peerOnion)
            sendWireDirectOrRelay(peerOnion, WireMessage.RemoteWipeNomination(nominated))
        }
        Unit
    }

    /** Contacts who have nominated this device to erase theirs. */
    suspend fun contactsThisDeviceCanWipe(): List<String> = withContext(Dispatchers.IO) {
        storage.remoteWipe.canWipe()
    }

    suspend fun markMessageRead(messageId: String) = withContext(Dispatchers.IO) {
        val message = storage.messages.get(messageId) ?: return@withContext
        if (message.direction != MessageDirection.INCOMING) return@withContext
        // Before the timer checks below: messages without a timer return early but still need to
        // stay unblurred.
        storage.messages.markRevealed(messageId)
        if (message.disappearSeconds == null || message.expiresAtEpochMillis != null) return@withContext
        storage.messages.startExpiry(messageId, message.disappearSeconds!!, now())
        sendReadReceipt(message)
        notifyChanged()
    }

    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        val unread = storage.messages.listForConversation(conversationId).filter {
            it.direction == MessageDirection.INCOMING && it.disappearSeconds != null && it.expiresAtEpochMillis == null
        }
        if (unread.isEmpty()) return@withContext
        val nowMs = now()
        for (message in unread) {
            storage.messages.startExpiry(message.id, message.disappearSeconds!!, nowMs)
            sendReadReceipt(message)
        }
        notifyChanged()
    }

    /**
     * Tells the sender a disappearing message was read, so their copy starts its timer too. Not
     * sent for an unanswered request: that would tell a stranger their message was seen.
     */
    private suspend fun sendReadReceipt(message: Message) {
        if (!isChosenPeer(message.senderOnion)) return
        runCatching {
            ensureSession(message.senderOnion)
            sendWire(message.senderOnion, WireMessage.Receipt(message.conversationId, message.id, READ_RECEIPT_STATE))
        }
    }

    suspend fun username(): String = withContext(Dispatchers.IO) {
        storage.settings.getString(SettingsStore.KEY_USERNAME).orEmpty().ifEmpty { "Me" }
    }

    suspend fun setUsername(name: String): Boolean = withContext(Dispatchers.IO) {
        val cleaned = name.trim().take(MAX_DISPLAY_NAME_CHARS)
        if (cleaned.isBlank()) return@withContext false
        storage.settings.setString(SettingsStore.KEY_USERNAME, cleaned)
        notifyChanged()
        runCatching { broadcastSelfProfile(loadProfile(SELF_PROFILE)) }
        true
    }

    private fun involves(conversation: Conversation, account: String): Boolean = when (conversation.type) {
        ConversationType.DIRECT -> conversation.id == account
        ConversationType.GROUP -> storage.members.isMember(conversation.id, account)
    }

    private fun shouldAttemptNow(msg: Message, nowMs: Long): Boolean = isRetryDue(msg.id, msg.createdAtEpochMillis, nowMs)

    /**
     * Retries thin out with age: every pass at first, then every few minutes, then hourly. [id]
     * spreads them over the passes, so they don't all come due at once.
     */
    private fun isRetryDue(id: String, sinceMillis: Long, nowMs: Long): Boolean {
        val ageMs = nowMs - sinceMillis
        val intervalMs = when {
            ageMs < 5L * 60_000 -> RETRY_BASE_INTERVAL_MILLIS
            ageMs < 60L * 60_000 -> 2L * 60_000
            ageMs < 24L * 60L * 60_000 -> 15L * 60_000
            else -> 60L * 60_000
        }
        if (intervalMs <= RETRY_BASE_INTERVAL_MILLIS) return true
        val slots = intervalMs / RETRY_BASE_INTERVAL_MILLIS
        val currentSlot = (nowMs / RETRY_BASE_INTERVAL_MILLIS) % slots
        val slot = Math.floorMod(id.hashCode().toLong(), slots)
        return currentSlot == slot
    }

    /**
     * Handles whatever arrived while the app was locked. Called once after unlocking; every entry
     * goes through the normal receive path, so the same checks apply as if it had arrived live.
     */
    suspend fun drainLockedInbox() = withContext(Dispatchers.IO) {
        if (!storage.appLock.isUnlocked()) return@withContext
        // Makes the queue's key now, while the phone is unlocked, rather than when the first
        // message arrives with the screen off.
        storage.lockedInbox.prepare()
        val queued = storage.lockedInbox.drain()
        if (queued.isEmpty()) return@withContext
        DiagnosticLog.record("receive", "processing ${queued.size} message(s) received while locked")
        // Arrival order matters: a group key sent just before a message has to be processed first.
        var failed = 0
        for (entry in queued) {
            val result = runCatching { intake(entry.senderOnion, entry.ciphertext, entry.viaRelay) }.getOrDefault(Intake.FAILED)
            // Not possible yet (a new sender still being checked, or over its quota): back in the
            // queue for the next pass, unless it has been waiting too long.
            val requeued = result == Intake.LATER &&
                System.currentTimeMillis() - entry.receivedAt < MAX_QUEUED_AGE_MILLIS &&
                storage.lockedInbox.enqueue(entry.senderOnion, entry.ciphertext, entry.viaRelay, entry.receivedAt)
            if (result == Intake.FAILED || (result == Intake.LATER && !requeued)) failed++
        }
        // These were already acknowledged, so the sender won't resend them. Rare, but it shouldn't
        // go unnoticed.
        if (failed > 0) {
            DiagnosticLog.record("receive", "$failed message(s) received while locked could not be opened at unlock and were lost")
        }
        notifyChanged()
    }

    /**
     * Retries what's waiting to go out. Normally on its schedule, which thins out with a message's
     * age. With [urgent] everything from the last hour is tried now, for when the network has just
     * come back (older messages are for someone who's been away, and keep to the schedule); with
     * [forAccount] only what's waiting for that contact, for when they've just shown they're online.
     */
    suspend fun retryPending(urgent: Boolean = false, forAccount: String? = null) =
        withContext(Dispatchers.IO) { retryMutex.withLock { retryPendingLocked(urgent, forAccount) } }

    private val contactSeenAt = ConcurrentHashMap<String, Long>()

    /**
     * Something just arrived from [account], so they're online now: whatever is waiting for them
     * goes at once instead of on the retry schedule, which by then may be minutes away.
     */
    private fun contactIsOnline(account: String) {
        val nowMs = now()
        val last = contactSeenAt[account]
        if (last != null && nowMs - last < CONTACT_FLUSH_INTERVAL_MILLIS) return
        contactSeenAt[account] = nowMs
        sendScope.launch {
            val waiting = runCatching { storage.messages.pendingOutgoing().isNotEmpty() }.getOrDefault(false)
            if (waiting) runCatching { retryPending(forAccount = account) }
        }
    }

    /**
     * One pass at a time. Pull-to-refresh and the background timer both call retryPending, and two
     * passes at once would send the same message twice and interleave the per-member group
     * tracking.
     */
    private val retryMutex = Mutex()

    private suspend fun retryPendingLocked(urgent: Boolean, forAccount: String?) {
        if (!storage.appLock.isUnlocked()) return
        if (!storage.lockedInbox.isEmpty()) runCatching { drainLockedInbox() }
        val pending = try {
            storage.messages.pendingOutgoing()
        } catch (_: Exception) {
            return
        }
        val nowMs = now()
        retryPassStartedAt = nowMs
        val due = ArrayList<Pair<Conversation, Message>>()
        for (msg in pending) {
            if (msg.deleted) {
                forgetDeliveryTracking(msg.id)
                continue
            }
            val conversation = storage.conversations.get(msg.conversationId) ?: continue
            if (forAccount != null) {
                if (!involves(conversation, forAccount)) continue
            } else if (!(urgent && nowMs - msg.createdAtEpochMillis < URGENT_WINDOW_MILLIS) && !shouldAttemptNow(msg, nowMs)) {
                continue
            }

            if (nowMs - msg.createdAtEpochMillis > MAX_PENDING_AGE_MILLIS) {
                // Log it: from the sender's side this is when a message they thought was in flight
                // stops being retried.
                DiagnosticLog.record("send", "gave up on a message after ${MAX_PENDING_AGE_MILLIS / 86_400_000} days undelivered")
                storage.messages.updateDeliveryState(msg.id, DeliveryState.FAILED)
                forgetDeliveryTracking(msg.id)
                notifyChanged()
                continue
            }
            due.add(conversation to msg)
        }
        storage.settings.getString(SettingsStore.KEY_WIPE_KEY_OWED_SINCE)?.toLongOrNull()?.let { since ->
            if (isRetryDue(WIPE_KEY_TRACKING, since, nowMs)) runCatching { deliverWipeKey() }
        }
        runCatching { retryOwnDeviceFiles(nowMs) }
        runCatching { retryCallPermissions(nowMs) }

        // A conversation's messages go in order, but different conversations go side by side. An
        // attempt to reach a phone that's offline can take a minute, and one away contact
        // shouldn't hold up everyone else's messages for that long.
        val permits = Semaphore(PARALLEL_RETRY_CONVERSATIONS)
        coroutineScope {
            due.groupBy { (conversation, _) -> conversation.id }.values.forEach { batch ->
                launch { permits.withPermit { batch.forEach { (conversation, msg) -> retryOne(conversation, msg) } } }
            }
        }
    }

    private suspend fun retryOne(conversation: Conversation, msg: Message) {
        if (conversation.type == ConversationType.DIRECT && msg.type == MessageType.TEXT) {
            val wire = WireMessage.Text(conversation.id, msg.id, selfAccount(), msg.body, conversation.disappearSeconds, msg.replyToId)
            val outcome = try {
                ensureSession(conversation.id)
                sendWireDirectOrRelay(conversation.id, wire, republishKey = msg.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DeliveryOutcome.FAILED
            }
            when (outcome) {
                DeliveryOutcome.DELIVERED -> {
                    storage.messages.updateDeliveryState(msg.id, DeliveryState.DELIVERED)
                    notifyChanged()
                }
                DeliveryOutcome.RELAYED -> {
                    storage.messages.updateDeliveryState(msg.id, DeliveryState.RELAYED)
                    notifyChanged()
                }
                DeliveryOutcome.FAILED -> Unit
            }
            return
        }

        val delivered = try {
            when (msg.type) {
                MessageType.TEXT -> {
                    val wire = WireMessage.Text(conversation.id, msg.id, selfAccount(), msg.body, conversation.disappearSeconds, msg.replyToId)
                    deliverToConversation(conversation, wire)
                }
                MessageType.ATTACHMENT -> retryAttachment(conversation, msg)
                MessageType.SYSTEM -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (delivered) {
            storage.messages.updateDeliveryState(msg.id, DeliveryState.DELIVERED)
            notifyChanged()
        }
    }

    /**
     * Starts a message that was given up on over again, as if just sent: it moves to the end of the
     * conversation and is retried from the beginning. False if it isn't a failed outgoing message.
     */
    suspend fun sendAgain(messageId: String): Boolean = withContext(Dispatchers.IO) {
        if (!storage.messages.sendAgain(messageId, now())) return@withContext false
        forgetDeliveryTracking(messageId)
        notifyChanged()
        sendScope.launch { runCatching { retryPending() } }
        true
    }

    /**
     * Files our other devices were offered but didn't get, retried on the same schedule as unsent
     * messages. Kept apart from the message's own delivery: a device of ours that's switched off
     * shouldn't leave a message to someone else showing as unsent.
     */
    private suspend fun retryOwnDeviceFiles(nowMs: Long) {
        val ownDevices = recipientDevices(selfAccount()).map { it.onion.value }.toSet()
        for (key in storage.settings.keysWithPrefix(OWN_FILE_TRACKING)) {
            val attachmentId = key.removePrefix(OWN_FILE_TRACKING)
            val lines = storage.settings.getString(key)?.split('\n').orEmpty()
            val since = lines.firstOrNull()?.toLongOrNull()
            val file = storage.attachmentFile(attachmentId)?.takeIf { it.isFile }
            // Given up after as long as a message would be, and dropped once the file is deleted.
            if (since == null || file == null || nowMs - since > MAX_PENDING_AGE_MILLIS) {
                storage.settings.remove(key)
                continue
            }
            if (!isRetryDue(key, since, nowMs)) continue
            val owed = lines.drop(1).filter { it in ownDevices }
                .filterNot { runCatching { sendAttachmentBlob(it, attachmentId, file) }.getOrDefault(false) }
            if (owed.isEmpty()) {
                storage.settings.remove(key)
            } else {
                storage.settings.setString(key, (listOf(since.toString()) + owed).joinToString("\n"))
            }
        }
    }

    /**
     * When the running retry pass started, and when a direct send to each device last failed. A
     * device that didn't answer earlier in this pass isn't dialled again for the rest of it: its
     * other messages go straight to its Nostr inbox, and the next pass tries direct again.
     */
    @Volatile
    private var retryPassStartedAt = 0L
    private val directFailedAt = ConcurrentHashMap<String, Long>()

    private fun failedThisPass(onion: String): Boolean =
        retryPassStartedAt > 0 && (directFailedAt[onion] ?: 0L) >= retryPassStartedAt

    /**
     * Whether a message was sent in the last few minutes and is still with nobody: not delivered
     * and not waiting on a relay. The app holds off locking for a short while when it is, since
     * nothing is retried while locked.
     */
    fun hasFreshUnsent(): Boolean =
        storage.appLock.isUnlocked() && storage.messages.hasUnsentSince(now() - FRESH_UNSENT_MILLIS)

    /**
     * Direct over Tor first, then the peer's Nostr inbox.
     *
     * [republishKey] identifies a retried message. Retries always try direct delivery again, but
     * only republish to Nostr every [NOSTR_REPUBLISH_INTERVAL_MILLIS]; a copy is already waiting on
     * the relays, and someone who's away for days doesn't need hundreds of duplicates.
     */
    private suspend fun sendWireDirectOrRelay(toAccount: String, wire: WireMessage, republishKey: String? = null): DeliveryOutcome =
        fanOut(toAccount, wire, republishKey, relay = true)

    /**
     * Sends [wire] to every device of [toAccount]: directly over Tor, then, if [relay], to the
     * device's Nostr inbox.
     *
     * With a [republishKey] the message is a tracked one that gets retried, and the devices that
     * acknowledged it are remembered under that key so a retry only goes to the rest. Sending a
     * device something it already has isn't harmless: a group message it decrypted once can't be
     * decrypted again, and it would never be acknowledged.
     *
     * DELIVERED means every device acknowledged it directly, RELAYED that each one still owed it at
     * least has a copy waiting on a relay.
     */
    private suspend fun fanOut(toAccount: String, wire: WireMessage, republishKey: String?, relay: Boolean): DeliveryOutcome {
        val devices = recipientDevices(toAccount)
        if (devices.isEmpty()) return DeliveryOutcome.DELIVERED
        val trackingKey = republishKey?.let { devicePendingKey(it, toAccount) }
        val owed = trackingKey?.let { storage.settings.getString(it) }?.split('\n')?.toSet()
        val targets = if (owed == null) devices else devices.filter { it.onion.value in owed }
        if (targets.isEmpty()) {
            trackingKey?.let { storage.settings.remove(it) }
            return DeliveryOutcome.DELIVERED
        }
        val plaintext = MessagePadding.pad(WireCodec.encode(wire))
        // All devices at once: one that's slow to reach shouldn't delay the others.
        val outcomes = coroutineScope {
            targets.map { device -> device to async { sendToDevice(toAccount, device, plaintext, republishKey, relay) } }
        }.associate { (device, outcome) -> device to outcome.await() }
        val stillOwed = outcomes.filterValues { it != DeliveryOutcome.DELIVERED }
        if (trackingKey != null) {
            if (stillOwed.isEmpty()) {
                storage.settings.remove(trackingKey)
            } else {
                storage.settings.setString(trackingKey, stillOwed.keys.joinToString("\n") { it.onion.value })
            }
        }
        return when {
            stillOwed.isEmpty() -> DeliveryOutcome.DELIVERED
            stillOwed.values.all { it == DeliveryOutcome.RELAYED } -> DeliveryOutcome.RELAYED
            else -> DeliveryOutcome.FAILED
        }
    }

    private suspend fun sendToDevice(
        account: String,
        device: Device,
        plaintext: ByteArray,
        republishKey: String?,
        relay: Boolean,
    ): DeliveryOutcome {
        // Every failure gets logged. A message that never arrives is hard to debug afterwards, and
        // without this "couldn't encrypt", "couldn't connect" and "no relay took it" all look the
        // same. Nothing about the content or recipient is logged.
        val ciphertext = try {
            ensureDeviceSession(account, device)
            crypto.encrypt(account, device.id, plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record("send", "encrypt failed (${e::class.java.simpleName}) -- no session?")
            return DeliveryOutcome.FAILED
        }
        val onion = device.onion.value
        val republish = republishKey?.let { "$it|$onion" }
        if (republishKey != null && failedThisPass(onion)) {
            if (relay && publishViaNostr(onion, ciphertext, republish)) return DeliveryOutcome.RELAYED
            return DeliveryOutcome.FAILED
        }
        return coroutineScope {
            val direct = async { sendDirect(onion, ciphertext) }
            // A phone that's online answers well within this. Past it, the same ciphertext goes to
            // the relay too, while the direct attempt carries on: reaching a phone that's offline
            // can take Tor a minute to give up on, and the message shouldn't wait for that. If both
            // arrive, the second copy is dropped as one already received.
            val quick = withTimeoutOrNull(RELAY_HEDGE_MILLIS) { direct.await() }
            if (quick == true) return@coroutineScope DeliveryOutcome.DELIVERED
            val relayed = relay && publishViaNostr(onion, ciphertext, republish)
            when {
                quick ?: direct.await() -> DeliveryOutcome.DELIVERED
                relayed -> DeliveryOutcome.RELAYED
                else -> {
                    if (relay) DiagnosticLog.record("send", "device unreachable and no Nostr relay took it -- will retry")
                    DeliveryOutcome.FAILED
                }
            }
        }
    }

    /** True if [onion] acknowledged [ciphertext] directly. Remembers failures for the retry pass. */
    private suspend fun sendDirect(onion: String, ciphertext: ByteArray): Boolean {
        val delivered = try {
            transportSend(onion, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An offline onion fails in the SOCKS handshake, which throws instead of returning
            // false. That has to be caught here or the Nostr fallback never runs.
            DiagnosticLog.record("send", "direct send failed (${e::class.java.simpleName})")
            false
        }
        if (delivered) directFailedAt.remove(onion) else directFailedAt[onion] = now()
        return delivered
    }

    private fun devicePendingKey(republishKey: String, account: String) = "devicepending:$republishKey|$account"

    /**
     * Pull-to-refresh: retries anything waiting to go out and nudges the Nostr subscription.
     *
     * Nothing incoming needs polling, since direct messages arrive as they're sent and relays push
     * the rest. Returns whether Tor is up, so the UI can tell "nothing new" apart from "couldn't
     * check".
     */
    suspend fun refreshNow(): Boolean = withContext(Dispatchers.IO) {
        runCatching { retryPending() }
        nostr?.let { mailbox -> if (mailbox.isRunning()) mailbox.refresh() }
        transport.state.value == TransportState.RUNNING
    }

    /**
     * Starts the Nostr inbox subscription, or just refreshes it if it's already running so the
     * frequent foreground service restarts don't redial every relay.
     *
     * Envelopes are the same Signal ciphertext a direct send carries and go through
     * [handleIncoming], so blocklist, allowlist, pinned identity, quota and locked-inbox handling
     * all apply unchanged.
     */
    fun startNostr(scope: CoroutineScope) {
        val mailbox = nostr ?: return
        if (mailbox.isRunning()) {
            mailbox.refresh()
            return
        }
        mailbox.start(scope, InboxDirectory(storage, nostrStore)) { peer, ciphertext ->
            handleIncoming(peer, ciphertext, viaRelay = true)
        }
    }

    fun isNostrAvailable(): Boolean = nostr != null

    suspend fun isNostrEnabled(): Boolean = withContext(Dispatchers.IO) {
        nostr != null && runCatching { nostrStore.isEnabled() }.getOrDefault(false)
    }

    suspend fun nostrRelays(): List<String> = withContext(Dispatchers.IO) {
        nostrStore.ownRelays().map { it.toString() }
    }

    /** Turning this off also tells contacts to stop using our mailbox. */
    suspend fun setNostrEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        nostrStore.setEnabled(enabled)
        nostr?.refresh()
        announceNostrInboxToAll()
    }

    /** Saves the valid relays, returns what was saved, and tells contacts about the change. */
    suspend fun setNostrRelays(raw: List<String>): List<String> = withContext(Dispatchers.IO) {
        val saved = nostrStore.setOwnRelays(raw)
        nostr?.refresh()
        announceNostrInboxToAll()
        saved.map { it.toString() }
    }

    fun defaultNostrRelays(): List<String> = NostrStore.DEFAULT_RELAYS

    private suspend fun announceNostrInboxToAll() {
        val accounts = (storage.contacts.list().map { it.onionAddress.value } +
            nostrStore.inboxPeers().map { ownerOf(it).first } + selfAccount()).distinct()
        for (account in accounts) {
            if (!hasAnySession(account)) continue
            runCatching { maybeAnnounceNostrInbox(account, force = true) }
        }
    }

    private val nostrAnnounceAttemptAt = ConcurrentHashMap<String, Long>()

    /**
     * Tells each device of [account] how to reach this one through Nostr, if it doesn't have the
     * current details yet. Every device of ours has its own inbox and every device of theirs its own
     * secret for it, so an envelope shows which device sent it. Only sent directly and only recorded
     * once acknowledged, so we know they really have it.
     */
    private suspend fun maybeAnnounceNostrInbox(account: String, force: Boolean = false) {
        val mailbox = nostr ?: return
        if (account != selfAccount() && !isChosenPeer(account)) return
        for (device in recipientDevices(account)) {
            if (!crypto.hasSession(account, device.id)) continue
            val key = device.onion.value
            val due = nostrStore.announcementDue(key) ?: continue
            val nowMs = now()
            if (!force) {
                val last = nostrAnnounceAttemptAt[key]
                if (last != null && nowMs - last < NOSTR_ANNOUNCE_RETRY_MILLIS) continue
            }
            nostrAnnounceAttemptAt[key] = nowMs
            val wire = WireMessage.NostrInbox(due.secret, due.relays.map { it.toString() })
            val plaintext = MessagePadding.pad(WireCodec.encode(wire))
            if (sendToDevice(account, device, plaintext, republishKey = null, relay = false) == DeliveryOutcome.DELIVERED) {
                nostrStore.recordAnnounced(key, due)
                nostrAnnounceAttemptAt.remove(key)
                mailbox.refresh()
            }
        }
    }

    /**
     * People the user chose to talk to: contacts, shared group members and accepted requests. Only
     * they get a mailbox, our profile and read receipts. Anyone can start a session by sending a
     * request, and none of that should go to a stranger.
     */
    private fun isChosenPeer(peerOnion: String): Boolean {
        if (storage.blocklist.isBlocked(peerOnion)) return false
        if (storage.conversations.get(peerOnion)?.pending == true) return false
        return storage.contacts.isKnown(peerOnion) || storage.members.isInAnyGroupWith(peerOnion)
    }

    /**
     * When each retry was last published, by republish key. Memory only; after a restart one extra
     * copy doesn't hurt.
     */
    private val nostrPublishedAt = ConcurrentHashMap<String, Long>()

    private suspend fun publishViaNostr(toOnion: String, ciphertext: ByteArray, republishKey: String?): Boolean {
        val mailbox = nostr ?: return false
        if (!nostrStore.isEnabled()) return false
        val outbox = nostrStore.outbox(toOnion) ?: return false
        if (ciphertext.size > NostrMailbox.MAX_ENVELOPE_BYTES) {
            DiagnosticLog.record("send", "too large for a Nostr relay")
            return false
        }
        val nowMs = now()
        if (republishKey != null) {
            val last = nostrPublishedAt[republishKey]
            if (last != null && nowMs - last < NOSTR_REPUBLISH_INTERVAL_MILLIS) return true
        }
        val accepted = try {
            mailbox.publish(outbox.relays, outbox.secret, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record("send", "Nostr publish threw ${e::class.java.simpleName}")
            0
        }
        if (accepted == 0) {
            DiagnosticLog.record("send", "peer offline and no Nostr relay accepted the message")
            return false
        }
        if (republishKey != null) {
            if (nostrPublishedAt.size > MAX_TRACKED_REPUBLISHES) nostrPublishedAt.clear()
            nostrPublishedAt[republishKey] = nowMs
        }
        DiagnosticLog.record("send", "stored on a Nostr relay (the others finish in the background)")
        return true
    }

    private suspend fun retryAttachment(conversation: Conversation, msg: Message): Boolean {
        val attachmentId = msg.attachmentId ?: return false
        val attachment = storage.attachments.get(attachmentId) ?: return false
        val wire = WireMessage.AttachmentOffer(
            conversation.id, msg.id, selfAccount(), attachmentId, attachment.mimeType,
            attachment.sizeBytes, attachment.encKey, attachment.digest, conversation.disappearSeconds,
        )
        if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
        val offered = deliverToConversation(conversation, wire)
        if (!offered) return false
        val encFile = storage.attachmentFile(attachmentId) ?: return false
        if (!encFile.isFile) return false
        return pushAttachmentBlob(conversation, attachmentId, encFile)
    }

    suspend fun addContactByCode(peerOnion: String, identityKey: ByteArray, title: String): Conversation =
        withContext(Dispatchers.IO) {
            // QR codes and pasted codes are untrusted. Validate everything before writing anything,
            // so a bad code can't leave half a contact behind.
            val onionAddress = OnionAddress.parse(peerOnion)
            require(crypto.isValidIdentityKeyBytes(identityKey)) { "Not a valid identity key" }
            storage.settings.setString(
                identityKeyStoreKey(peerOnion),
                Base64.getEncoder().encodeToString(identityKey),
            )
            val conversation = Conversation(
                id = peerOnion,
                type = ConversationType.DIRECT,
                title = title,
                disappearSeconds = 0,
                createdAtEpochMillis = now(),
            )
            storage.conversations.upsert(conversation)
            storage.contacts.upsert(
                Contact(
                    onionAddress = onionAddress,
                    displayName = title,
                    fingerprint = IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = now(),
                ),
            )
            syncContact(peerOnion)
            notifyChanged()
            conversation
        }

    suspend fun renameContact(conversationId: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext
        storage.conversations.get(conversationId)?.let {
            storage.conversations.upsert(it.copy(title = trimmed))
        }
        storage.contacts.get(conversationId)?.let {
            storage.contacts.upsert(it.copy(displayName = trimmed))
            syncContact(conversationId)
        }
        notifyChanged()
    }

    suspend fun block(onion: String) = withContext(Dispatchers.IO) {
        storage.blocklist.block(onion)
        // Blocking someone stops calls with them too, and unblocking doesn't bring that back.
        stopAllowingCalls(onion, announce = false)
        syncContact(onion, includeCalls = true)
    }

    /**
     * Lets [onion]'s messages through again, on every device of this account. What they sent
     * while blocked was dropped and doesn't come back.
     */
    suspend fun unblock(onion: String) = withContext(Dispatchers.IO) {
        storage.blocklist.unblock(onion)
        syncContact(onion, unblocked = true)
        // A blocked contact isn't given a mailbox; now they can have one again.
        nostr?.refresh()
        sendScope.launch { runCatching { maybeAnnounceNostrInbox(onion) } }
        notifyChanged()
    }

    suspend fun isBlocked(onion: String): Boolean = withContext(Dispatchers.IO) { storage.blocklist.isBlocked(onion) }

    /** Everyone blocked, with the name they're known by here, if any. */
    suspend fun blocked(): List<BlockedPeer> = withContext(Dispatchers.IO) {
        storage.blocklist.list().map { onion ->
            BlockedPeer(onion, storage.contacts.get(onion)?.displayName ?: storage.conversations.get(onion)?.title)
        }.sortedBy { it.name?.lowercase() ?: "\uFFFF${it.onion}" }
    }

    data class BlockedPeer(val onion: String, val name: String?)

    /**
     * Marks a contact as verified in person.
     *
     * This pins their identity key as well as setting the label. Without the pin, "verified" would
     * only mean the numbers matched once; with it, [identityMatchesPinnedKey] rejects messages from
     * a different key and libsignal refuses to replace it.
     *
     * Returns false when there's no identity to pin yet: a contact without a session can't really
     * be verified.
     */
    suspend fun markVerified(onion: String): Boolean = withContext(Dispatchers.IO) {
        val identityKey = crypto.remoteIdentityKeyBytes(onion)
        if (identityKey == null) {
            DiagnosticLog.record("verify", "cannot verify: no identity key established for this contact yet")
            return@withContext false
        }
        storage.settings.setString(
            identityKeyStoreKey(onion),
            Base64.getEncoder().encodeToString(identityKey),
        )
        storage.contacts.setTrustState(onion, TrustState.VERIFIED)
        syncContact(onion)
        true
    }

    /** The contact's identity key from the current session, or null if there isn't one. */
    fun identityKeyFor(onion: String): ByteArray? = crypto.remoteIdentityKeyBytes(onion)

    suspend fun safetyNumber(onion: String): String? = withContext(Dispatchers.IO) {
        crypto.safetyNumber(onion, selfAccount())
    }

    suspend fun trustState(onion: String): TrustState? = withContext(Dispatchers.IO) {
        storage.contacts.get(onion)?.trustState
    }

    suspend fun addGroupMemberToContacts(onion: String, displayName: String): Boolean = withContext(Dispatchers.IO) {
        if (onion == selfAccount()) return@withContext false
        if (storage.contacts.isKnown(onion)) return@withContext false
        storage.contacts.upsert(
            Contact(
                onionAddress = OnionAddress.parse(onion),
                displayName = displayName,
                fingerprint = crypto.remoteFingerprint(onion) ?: IdentityFingerprint(""),
                trustState = TrustState.UNVERIFIED,
                addedAtEpochMillis = now(),
            ),
        )
        syncContact(onion)
        notifyChanged()
        true
    }

    suspend fun createGroup(title: String, memberOnions: List<String>): Conversation =
        withContext(Dispatchers.IO) {
            val conversationId = UUID.randomUUID().toString()
            val conversation = Conversation(
                id = conversationId,
                type = ConversationType.GROUP,
                title = title,
                disappearSeconds = 0,
                createdAtEpochMillis = now(),
                epoch = 1,
            )
            storage.conversations.upsert(conversation)
            val self = selfAccount()
            val allMembers = (memberOnions + self).distinct()
            val admins = listOf(self)
            storage.members.replaceAll(conversationId, allMembers, admins)
            // The creator needs a state-hash baseline too, or its first membership change goes out
            // unchained.
            rememberGroupState(conversationId)
            val invite = WireMessage.GroupInvite(conversationId, title, allMembers, admins, epoch = 1)
            syncSent(conversationId, invite)
            memberOnions.forEach { runCatching { ensureSession(it); sendWire(it, invite) } }
            ensureGroupSenderKey(conversation)
            conversation
        }

    /**
     * Fills a fresh account with a few ordinary-looking chats, for the duress decoy. [names] and
     * [messages] come from the app's resources, so the decoy is in the phone's language: an
     * all-English history on a German phone would look staged.
     */
    suspend fun seedDecoyContent(names: List<String>, messages: List<String>) = withContext(Dispatchers.IO) {
        val rng = java.security.SecureRandom()
        val now = now()
        val self = selfAccount()
        val chosen = names.shuffled(kotlin.random.Random(rng.nextLong())).take(4 + rng.nextInt(3))
        for (name in chosen) {
            val onion = DecoyAddress.random(rng)
            val createdAt = now - java.util.concurrent.TimeUnit.DAYS.toMillis((20L + rng.nextInt(150)))
            val sent = List(3 + rng.nextInt(6)) { rng.nextBoolean() }
            // A real exchange with a stand-in phone held in memory, so this contact has an identity
            // key and a session that has carried these messages, like any contact that's been used.
            // A contact without them would give the decoy away, so one that fails is left out.
            if (runCatching { crypto.simulateConversation(onion, sent) }.isFailure) continue
            storage.contacts.upsert(
                Contact(
                    onionAddress = OnionAddress.parse(onion),
                    displayName = name,
                    fingerprint = crypto.remoteFingerprint(onion) ?: IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = createdAt,
                ),
            )
            storage.conversations.upsert(Conversation(onion, ConversationType.DIRECT, name, 0, createdAt))
            var t = createdAt
            for (outgoing in sent) {
                t += java.util.concurrent.TimeUnit.HOURS.toMillis((4L + rng.nextInt(90)))
                if (t > now) t = now - java.util.concurrent.TimeUnit.MINUTES.toMillis(rng.nextInt(500).toLong())
                storage.messages.insert(
                    Message(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = onion,
                        senderOnion = if (outgoing) self else onion,
                        direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        type = MessageType.TEXT,
                        body = messages[rng.nextInt(messages.size)],
                        attachmentId = null,
                        createdAtEpochMillis = t,
                        expiresAtEpochMillis = null,
                        deliveryState = if (outgoing) DeliveryState.DELIVERED else DeliveryState.RECEIVED,
                        deleted = false,
                        remoteDeletable = outgoing,
                    ),
                )
            }
        }
        notifyChanged()
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext
        storage.attachments.listForConversation(conversationId).forEach { attachment ->
            runCatching { storage.deleteAttachmentFile(attachment.id) }
        }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        if (conversation.type == ConversationType.GROUP) {
            storage.members.replaceAll(conversationId, emptyList(), emptyList())
            storage.groupSenderKeyState.delete(conversationId)
            storage.groupRemoteSenderKeys.deleteForConversation(conversationId)
        }
        // Settings keyed by conversation id go too. For a direct chat the id is the peer's onion,
        // so a contact added again later would otherwise inherit old settings, like being muted. A
        // stale group chain baseline would also make a rejoined group reject its first legitimate
        // update.
        storage.settings.remove(SettingsStore.mutedKey(conversationId))
        storage.settings.remove(groupStateHashKey(conversationId))
        storage.settings.remove(groupChainKey(conversationId))
        // Drop their mailbox too, unless we still share a group. They get a fresh one if you talk
        // again.
        if (conversation.type == ConversationType.DIRECT && !storage.members.isInAnyGroupWith(conversationId)) {
            devicesOf(conversationId).forEach { nostrStore.forget(it.onion.value) }
            nostr?.refresh()
        }
        // Calls start from nothing again too, and they're told, so their phone stops offering it.
        if (conversation.type == ConversationType.DIRECT && storage.callPermissions.isAllowedByMeRecorded(conversationId)) {
            stopAllowingCalls(conversationId, announce = true)
            syncContact(conversationId, includeCalls = true)
        }
        storage.conversations.delete(conversationId)
        notifyChanged()
    }

    // ---- Voice calls: who they're allowed with ----

    /** Whether a call with [onion] is possible now, and if not, why. */
    suspend fun callAvailability(onion: String): CallAvailability = withContext(Dispatchers.IO) {
        runCatching { callAvailabilityNow(onion) }.getOrDefault(CallAvailability.UNAVAILABLE)
    }

    /**
     * Allows or stops allowing voice calls with [onion], on every device of this account, and tells
     * them. Calls need both people to allow them, so allowing only makes calls possible once they
     * have too. Stopping ends any call with them at once. False if calls can't be allowed with them:
     * they aren't a contact, are blocked, aren't verified, or there's no session with them yet.
     */
    suspend fun setCallsAllowed(onion: String, allowed: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (allowed) {
            if (!isCallablePeer(onion) || !isVerifiedForCalls(onion)) return@withContext false
            val key = crypto.remoteIdentityKeyBytes(onion) ?: return@withContext false
            storage.callPermissions.allow(onion, key)
            DiagnosticLog.record("call", "allowed calls with a contact")
            oweCallPermission(onion)
        } else {
            stopAllowingCalls(onion, announce = true)
        }
        syncContact(onion, includeCalls = true)
        notifyChanged()
        true
    }

    private fun callAvailabilityNow(account: String): CallAvailability {
        if (!isCallablePeer(account)) return CallAvailability.UNAVAILABLE
        val key = crypto.remoteIdentityKeyBytes(account) ?: return CallAvailability.UNAVAILABLE
        if (!isVerifiedForCalls(account)) return CallAvailability.NEEDS_VERIFICATION
        val permissions = storage.callPermissions
        return when {
            !permissions.allowedByMe(account, key) -> CallAvailability.NOT_ALLOWED
            !permissions.allowedByThem(account) -> CallAvailability.WAITING_FOR_THEM
            else -> CallAvailability.READY
        }
    }

    /**
     * Someone calls can be had with at all: a contact the user added, not blocked, not an unanswered
     * request, and still on the key the user pinned if they verified them. Group members who aren't
     * contacts aren't, on purpose.
     */
    private fun isCallablePeer(account: String): Boolean {
        if (OnionAddress.parseOrNull(account) == null) return false
        if (account == selfAccount()) return false
        if (!storage.contacts.isKnown(account) || !isChosenPeer(account)) return false
        val conversation = storage.conversations.get(account)
        if (conversation != null && conversation.type != ConversationType.DIRECT) return false
        return identityMatchesPinnedKey(account)
    }

    /**
     * Calls are only with people whose safety number the user checked. A call carries far more than
     * a message, a live voice, so it's held to the stronger standard: their key is pinned, and one
     * that changes is refused outright rather than trusted on first use.
     */
    private fun isVerifiedForCalls(account: String): Boolean =
        storage.contacts.get(account)?.trustState == TrustState.VERIFIED &&
            storage.settings.getString(identityKeyStoreKey(account)) != null &&
            identityMatchesPinnedKey(account)

    private fun stopAllowingCalls(onion: String, announce: Boolean) {
        val wasAllowed = storage.callPermissions.isAllowedByMeRecorded(onion)
        storage.callPermissions.disallow(onion)
        calls.permissionWithdrawn(onion, byThem = false)
        if (announce) {
            oweCallPermission(onion)
        } else {
            storage.settings.remove(callPermissionOwedKey(onion))
        }
        if (wasAllowed) DiagnosticLog.record("call", "stopped allowing calls with a contact")
    }

    /** Marks [onion] as owed our current answer on calls, and sends it now; retried until it lands. */
    private fun oweCallPermission(onion: String) {
        storage.settings.setString(callPermissionOwedKey(onion), now().toString())
        sendScope.launch { runCatching { announceCallPermission(onion) } }
    }

    /** One announcement at a time, so two quick changes can't arrive in the wrong order. */
    private val callPermissionMutex = Mutex()

    private suspend fun announceCallPermission(onion: String) = callPermissionMutex.withLock {
        val owedKey = callPermissionOwedKey(onion)
        if (storage.settings.getString(owedKey) == null) return@withLock
        if (storage.blocklist.isBlocked(onion) || OnionAddress.parseOrNull(onion) == null) {
            storage.settings.remove(owedKey)
            return@withLock
        }
        // What's true at the moment of sending, not when it was asked for.
        val allowed = isCallablePeer(onion) && isVerifiedForCalls(onion) &&
            storage.callPermissions.allowedByMe(onion, crypto.remoteIdentityKeyBytes(onion))
        ensureSession(onion)
        val outcome = sendWireDirectOrRelay(onion, WireMessage.CallPermission(allowed))
        if (outcome != DeliveryOutcome.FAILED) storage.settings.remove(owedKey)
    }

    private suspend fun retryCallPermissions(nowMs: Long) {
        for (key in storage.settings.keysWithPrefix(CALL_PERMISSION_OWED)) {
            val onion = key.removePrefix(CALL_PERMISSION_OWED)
            val since = storage.settings.getString(key)?.toLongOrNull()
            if (since == null || nowMs - since > MAX_PENDING_AGE_MILLIS || OnionAddress.parseOrNull(onion) == null) {
                storage.settings.remove(key)
                continue
            }
            if (!isRetryDue(key, since, nowMs)) continue
            runCatching { announceCallPermission(onion) }
        }
    }

    private fun callPermissionOwedKey(onion: String) = "$CALL_PERMISSION_OWED$onion"

    /**
     * A call message, direct to [account]'s devices ([deviceIds], or all of them), never through a
     * relay: a call that can't reach someone now is over, and a relay would only keep a record that
     * it happened. Returns the devices that acknowledged it.
     */
    private suspend fun sendCallSignal(account: String, wire: WireMessage, deviceIds: Set<Int>?): Set<Int> {
        val targets = recipientDevices(account).filter { deviceIds == null || it.id in deviceIds }
        if (targets.isEmpty()) return emptySet()
        val plaintext = MessagePadding.pad(WireCodec.encode(wire))
        return coroutineScope {
            targets.map { device ->
                device to async {
                    try {
                        sendToDevice(account, device, plaintext, republishKey = null, relay = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        DeliveryOutcome.FAILED
                    }
                }
            }.filter { (_, outcome) -> outcome.await() == DeliveryOutcome.DELIVERED }.map { (device, _) -> device.id }.toSet()
        }
    }

    suspend fun ensureConversationForContact(onion: String): Conversation = withContext(Dispatchers.IO) {
        storage.conversations.get(onion)?.let { return@withContext it }
        val title = storage.contacts.get(onion)?.displayName?.takeIf { it.isNotBlank() } ?: onion.take(8)
        val conversation = Conversation(
            id = onion,
            type = ConversationType.DIRECT,
            title = title,
            disappearSeconds = 0,
            createdAtEpochMillis = now(),
        )
        storage.conversations.upsert(conversation)
        notifyChanged()
        conversation
    }

    suspend fun displayName(onion: String): String = withContext(Dispatchers.IO) {
        storage.contacts.get(onion)?.displayName?.takeIf { it.isNotBlank() } ?: onion.take(8)
    }

    suspend fun groupMembers(conversationId: String): List<GroupMember> =
        withContext(Dispatchers.IO) { storage.members.listForConversation(conversationId) }

    suspend fun amIGroupAdmin(conversationId: String): Boolean =
        withContext(Dispatchers.IO) { storage.members.isAdmin(conversationId, selfAccount()) }

    suspend fun addGroupMembers(conversationId: String, newOnions: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfAccount())) return@withContext false
            newOnions.forEach {
                storage.members.add(GroupMember(conversationId, OnionAddress.parse(it), GroupRole.MEMBER))
            }
            syncGroup(conversationId)
            true
        }

    suspend fun removeGroupMember(conversationId: String, onion: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfAccount())) return@withContext false
            if (onion == selfAccount()) return@withContext false
            storage.members.remove(conversationId, OnionAddress.parse(onion))
            syncGroup(conversationId)
            revokeSenderKeysFor(conversationId, listOf(onion))
            true
        }

    suspend fun promoteGroupMember(conversationId: String, onion: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfAccount())) return@withContext false
            storage.members.setRole(conversationId, onion, GroupRole.ADMIN)
            syncGroup(conversationId)
            true
        }

    suspend fun leaveGroup(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (conversation.type != ConversationType.GROUP) return@withContext false
        val self = selfAccount()
        val members = storage.members.listForConversation(conversationId)
        val remaining = members.filter { it.memberOnion.value != self }
        // A GroupLeave rather than an invite that leaves us out. Members correctly reject invites
        // that drop their own sender (that's how someone would try to eject a member), so leaving
        // that way never reached anyone.
        val leave = WireMessage.GroupLeave(conversationId, epoch = conversation.epoch + 1)
        remaining.forEach { runCatching { ensureSession(it.memberOnion.value); sendWire(it.memberOnion.value, leave) } }
        syncSent(conversationId, leave)
        removeGroupLocally(conversationId)
        notifyChanged()
        true
    }

    private fun removeGroupLocally(conversationId: String) {
        storage.attachments.listForConversation(conversationId).forEach { runCatching { storage.deleteAttachmentFile(it.id) } }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        storage.members.replaceAll(conversationId, emptyList(), emptyList())
        storage.conversations.delete(conversationId)
        storage.groupSenderKeyState.delete(conversationId)
        storage.groupRemoteSenderKeys.deleteForConversation(conversationId)
    }

    private suspend fun syncGroup(conversationId: String) {
        val conversation = storage.conversations.get(conversationId) ?: return
        val members = storage.members.listForConversation(conversationId)
        val allOnions = members.map { it.memberOnion.value }
        val admins = members.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value }
        val nextEpoch = conversation.epoch + 1
        // Taken before the epoch moves, so it names the state this change builds on.
        val prevHash = currentGroupStateHash(conversationId)
        storage.conversations.setEpoch(conversationId, nextEpoch)
        val invite = WireMessage.GroupInvite(
            conversationId, conversation.title, allOnions, admins,
            epoch = nextEpoch, prevStateHash = prevHash,
        )
        syncSent(conversationId, invite)
        allOnions.filter { it != selfAccount() }.forEach { runCatching { ensureSession(it); sendWire(it, invite) } }
        rememberGroupState(conversationId)
        val refreshed = storage.conversations.get(conversationId) ?: return
        ensureGroupSenderKey(refreshed)
        notifyChanged()
    }

    suspend fun sendText(
        conversationId: String,
        body: String,
        replyToId: String? = null,
    ): Message = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        // Only accept a reply target from this conversation, otherwise the quote could point at an
        // unrelated message on the other side.
        val validReplyTo = replyToId?.takeIf { storage.messages.get(it)?.conversationId == conversationId }
        val message = newOutgoingMessage(conversation, MessageType.TEXT, body, attachmentId = null, replyToId = validReplyTo)
        storage.messages.insert(message)
        notifyChanged()
        sendScope.launch { deliverTextNow(conversation, message) }
        message
    }

    private suspend fun deliverTextNow(conversation: Conversation, message: Message) = withContext(Dispatchers.IO) {
        val wire = WireMessage.Text(
            conversation.id, message.id, selfAccount(), message.body,
            conversation.disappearSeconds, message.replyToId,
        )
        syncSent(conversation.id, wire)
        if (conversation.type == ConversationType.DIRECT) {
            // Fall back to a relay straight away, so a message to someone offline is stored within
            // seconds.
            val outcome = try {
                ensureSession(conversation.id)
                sendWireDirectOrRelay(conversation.id, wire, republishKey = message.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DeliveryOutcome.FAILED
            }
            when (outcome) {
                DeliveryOutcome.DELIVERED -> finalizeDelivery(message.id, true)
                DeliveryOutcome.RELAYED -> storage.messages.updateDeliveryState(message.id, DeliveryState.RELAYED)
                DeliveryOutcome.FAILED -> finalizeDelivery(message.id, false)
            }
            notifyChanged()
            return@withContext
        }
        val delivered = try {
            deliverToConversation(conversation, wire)
        } catch (_: Exception) {
            false
        }
        finalizeDelivery(message.id, delivered)
        notifyChanged()
    }

    suspend fun sendAttachment(conversationId: String, source: File, mimeType: String): Message =
        withContext(Dispatchers.IO) {
            val conversation = requireConversation(conversationId)
            val attachmentId = UUID.randomUUID().toString()
            val key = storage.attachmentCipher.newKey()
            attachmentsDir.mkdirs()
            val encryptedFile = File(attachmentsDir, "$attachmentId.enc")
            // The attachment id is the associated data, so a blob only decrypts as the attachment
            // it was made for and swapped files are caught.
            storage.attachmentCipher.encryptFile(key, source, encryptedFile, attachmentContext(attachmentId))
            val digest = sha256(encryptedFile)
            val attachment = Attachment(
                id = attachmentId,
                conversationId = conversationId,
                filePath = encryptedFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = encryptedFile.length(),
                encKey = key,
                digest = digest,
                state = AttachmentState.COMPLETE,
                createdAtEpochMillis = now(),
            )
            storage.attachments.insert(attachment)
            val message = newOutgoingMessage(conversation, MessageType.ATTACHMENT, "", attachmentId)
            storage.messages.insert(message)
            notifyChanged()
            sendScope.launch {
                deliverAttachmentNow(conversation, message, attachmentId, mimeType, encryptedFile.length(), key, digest, encryptedFile)
            }
            message
        }

    private suspend fun deliverAttachmentNow(
        conversation: Conversation,
        message: Message,
        attachmentId: String,
        mimeType: String,
        sizeBytes: Long,
        key: ByteArray,
        digest: ByteArray,
        encryptedFile: File,
    ) = withContext(Dispatchers.IO) {
        val wire = WireMessage.AttachmentOffer(
            conversation.id, message.id, selfAccount(), attachmentId, mimeType,
            sizeBytes, key, digest, conversation.disappearSeconds,
        )
        // Our other devices get the offer, then the file like any recipient.
        syncSent(conversation.id, wire)
        val delivered = try {
            if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
            deliverToConversation(conversation, wire)
        } catch (_: Exception) {
            false
        }
        // Our own devices always have the offer; the recipients only once it was delivered. It
        // counts as delivered once they have the file too; until then it's retried.
        val fileDelivered = runCatching { pushAttachmentBlob(conversation, attachmentId, encryptedFile, toRecipients = delivered) }.getOrDefault(false)
        finalizeDelivery(message.id, delivered && fileDelivered)
        notifyChanged()
    }

    /**
     * Sends the file to every device that was offered it: the recipients' (when [toRecipients]) and
     * this account's other devices. True once every recipient device has it.
     */
    private suspend fun pushAttachmentBlob(
        conversation: Conversation,
        attachmentId: String,
        encryptedFile: File,
        toRecipients: Boolean = true,
    ): Boolean {
        val recipients = when {
            !toRecipients -> emptyList()
            conversation.type == ConversationType.DIRECT -> listOf(conversation.id)
            else -> storage.members.listForConversation(conversation.id).map { it.memberOnion.value }.filter { it != selfAccount() }
        }
        val ownMissing = recipientDevices(selfAccount()).map { it.onion.value }
            .filterNot { runCatching { sendAttachmentBlob(it, attachmentId, encryptedFile) }.getOrDefault(false) }
        val ownKey = "$OWN_FILE_TRACKING$attachmentId"
        if (ownMissing.isNotEmpty() && storage.settings.getString(ownKey) == null) {
            storage.settings.setString(ownKey, (listOf(now().toString()) + ownMissing).joinToString("\n"))
        }
        // Every device of every recipient has to have it, or a group member who was offline would
        // never get the file. Those that have it are remembered, so a retry only goes to the rest.
        val delivered = blobsDelivered.getOrPut(attachmentId) { ConcurrentHashMap.newKeySet() }
        val owed = recipients.flatMap { recipientDevices(it) }.filter { it.onion.value !in delivered }
        owed.forEach { device ->
            if (runCatching { sendAttachmentBlob(device.onion.value, attachmentId, encryptedFile) }.getOrDefault(false)) {
                delivered.add(device.onion.value)
            }
        }
        val done = toRecipients && owed.all { it.onion.value in delivered } &&
            (recipients.isNotEmpty() || conversation.type == ConversationType.GROUP)
        if (done) blobsDelivered.remove(attachmentId)
        return done
    }

    /**
     * Devices that have each attachment still being delivered. Memory only: after a restart, one
     * that already has it says so as soon as it's asked, without the file being sent again.
     */
    private val blobsDelivered = ConcurrentHashMap<String, MutableSet<String>>()

    private suspend fun sendAttachmentBlob(toOnion: String, attachmentId: String, encFile: File): Boolean =
        transport.connect(OnionAddress.parse(toOnion), servicePort).use { connection ->
            val out = DataOutputStream(connection.output)
            out.writeByte(FRAME_ATTACHMENT)
            val sender = selfDevice().toByteArray(Charsets.UTF_8)
            out.writeInt(sender.size); out.write(sender)
            val idBytes = attachmentId.toByteArray(Charsets.UTF_8)
            out.writeInt(idBytes.size); out.write(idBytes)
            // Proves the blob comes from whoever sent the offer. Raw frames are handled before any
            // Signal decryption, so without this anyone who learned a pending attachment id could
            // push junk and ruin the real transfer. Only sender and recipient know the
            // per-attachment key it's derived from.
            val encKey = encKeyForAttachment(attachmentId)
            if (encKey == null) {
                DiagnosticLog.record("send", "attachment row missing at send time -- cannot authenticate transfer")
                return@use false
            }
            val token = attachmentTransferToken(encKey, attachmentId)
            out.writeInt(token.size); out.write(token)
            val size = encFile.length()
            out.writeLong(size)
            out.flush()
            // The recipient answers before any of the file is sent: either it has it all already,
            // or it says how much it has, and only the rest goes.
            val input = DataInputStream(connection.input)
            when (input.readByte().toInt()) {
                FRAME_ACK -> true
                FRAME_ATTACHMENT_FROM -> {
                    val from = input.readLong()
                    if (from !in 0..size) return@use false
                    java.io.FileInputStream(encFile).use { file ->
                        file.channel.position(from)
                        file.copyTo(out)
                    }
                    out.flush()
                    runCatching { input.readByte().toInt() == FRAME_ACK }.getOrDefault(false)
                }
                else -> false
            }
        }

    fun attachment(attachmentId: String): Attachment? = storage.attachments.get(attachmentId)

    suspend fun attachmentBytes(attachmentId: String): ByteArray? = withContext(Dispatchers.IO) {
        val att = storage.attachments.get(attachmentId) ?: return@withContext null
        val encFile = storage.attachmentFile(attachmentId) ?: return@withContext null
        if (!encFile.isFile) return@withContext null
        decryptAttachmentToBytes(att.encKey, attachmentId, encFile)
    }

    suspend fun decryptAttachmentTo(attachmentId: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val att = storage.attachments.get(attachmentId) ?: return@withContext false
        val encFile = storage.attachmentFile(attachmentId) ?: return@withContext false
        if (!encFile.isFile) return@withContext false
        decryptAttachmentToFile(att.encKey, attachmentId, encFile, dest)
    }

    private fun profilesDir(): File = File(attachmentsDir.parentFile ?: attachmentsDir, "niix-profiles")

    private fun profileKey(): ByteArray {
        val existing = storage.settings.getString(SettingsStore.KEY_PROFILE_KEY)
        if (existing != null) return Base64.getDecoder().decode(existing)
        val key = storage.attachmentCipher.newKey()
        storage.settings.setString(SettingsStore.KEY_PROFILE_KEY, Base64.getEncoder().encodeToString(key))
        return key
    }

    /**
     * Records the display name a peer says they use.
     *
     * A name you set yourself is never overwritten by one the peer sends. Names are trimmed and
     * capped because they're attacker-controlled text that ends up in lists and notifications. They
     * don't have to be unique: conversations are keyed on the onion address and identity key, and a
     * name affects neither.
     */
    private fun applyPeerDisplayName(peerOnion: String, claimed: String?) {
        val name = claimed?.trim()?.take(MAX_DISPLAY_NAME_CHARS)?.takeIf { it.isNotBlank() } ?: return
        val contact = storage.contacts.get(peerOnion) ?: return
        // A locally chosen name wins.
        val existing = contact.displayName.trim()
        if (existing.isNotBlank() && existing != peerOnion.take(8)) return
        storage.contacts.upsert(contact.copy(displayName = name))
        storage.conversations.get(peerOnion)?.let { conversation ->
            if (conversation.type == ConversationType.DIRECT) {
                storage.conversations.upsert(conversation.copy(title = name))
            }
        }
    }

    /**
     * Ties a raw attachment transfer to the key from its offer. Both ends can compute it; someone
     * who only saw the attachment id can't.
     */
    private fun attachmentTransferToken(encKey: ByteArray, attachmentId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encKey + attachmentId.toByteArray(Charsets.UTF_8))

    /**
     * The per-attachment key, or null if the row is gone. Deliberately not an empty array: that
     * would produce a token the recipient can never match, and the transfer would fail on their end
     * with no clue why.
     */
    private fun encKeyForAttachment(attachmentId: String): ByteArray? =
        storage.attachments.get(attachmentId)?.encKey

    /**
     * What an attachment's ciphertext is bound to: its id, which is unique and already validated.
     */
    private fun attachmentContext(attachmentId: String): ByteArray =
        attachmentId.toByteArray(Charsets.UTF_8)

    /**
     * Decrypts an attachment, with its id required as associated data.
     *
     * There's no fallback to unbound decryption. If there were, an attacker who could strip the
     * binding would get the unbound path for free. Attachments made before the binding existed
     * won't open any more; that was a deliberate call while the app only had testers.
     */
    private fun decryptAttachmentToBytes(key: ByteArray, attachmentId: String, encFile: File): ByteArray? =
        runCatching {
            val out = ByteArrayOutputStream()
            encFile.inputStream().use { input ->
                storage.attachmentCipher.decrypt(key, input, out, attachmentContext(attachmentId))
            }
            out.toByteArray()
        }.getOrElse {
            retireUndecryptable(attachmentId, encFile)
            null
        }

    /** Same as above, but to a file. */
    private fun decryptAttachmentToFile(
        key: ByteArray,
        attachmentId: String,
        encFile: File,
        dest: File,
    ): Boolean = runCatching {
        dest.outputStream().use { out ->
            encFile.inputStream().use { input ->
                storage.attachmentCipher.decrypt(key, input, out, attachmentContext(attachmentId))
            }
        }
        true
    }.getOrElse {
        // A partial write may be left behind; it isn't the file the caller asked for.
        runCatching { dest.delete() }
        retireUndecryptable(attachmentId, encFile)
        false
    }

    private fun retireUndecryptable(attachmentId: String, encFile: File) {
        runCatching {
            DiagnosticLog.record("attachment", "removed an attachment that could not be decrypted")
            encFile.delete()
            storage.attachments.updateState(attachmentId, AttachmentState.FAILED)
            notifyChanged()
        }
    }

    private fun storeProfile(key: String, bytes: ByteArray?) {
        val dir = profilesDir()
        dir.mkdirs()
        // This key becomes a file name, so refuse path separators here too, even though
        // handleIncoming already validates addresses.
        val file = File(dir, "${sanitizeProfileKey(key)}.enc")
        if (bytes == null) { file.delete(); return }
        file.outputStream().use { out ->
            storage.attachmentCipher.encrypt(profileKey(), ByteArrayInputStream(bytes), out)
        }
    }

    private fun sanitizeProfileKey(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun loadProfile(key: String): ByteArray? {
        val file = File(profilesDir(), "${sanitizeProfileKey(key)}.enc")
        if (!file.isFile) return null
        return runCatching {
            val out = ByteArrayOutputStream()
            file.inputStream().use { input -> storage.attachmentCipher.decrypt(profileKey(), input, out) }
            out.toByteArray()
        }.getOrNull()
    }

    suspend fun profileBytes(key: String): ByteArray? = withContext(Dispatchers.IO) { loadProfile(key) }

    suspend fun setSelfProfile(bytes: ByteArray?) = withContext(Dispatchers.IO) {
        storeProfile(SELF_PROFILE, bytes)
        notifyChanged()
        broadcastSelfProfile(bytes)
    }

    /**
     * Sends our picture and display name to every direct contact.
     *
     * The name goes out even without a picture, otherwise contacts only see the start of an onion
     * address. Names aren't unique and nothing trusts them; identity comes from the onion address
     * and key.
     */
    private suspend fun broadcastSelfProfile(bytes: ByteArray?) {
        val name = runCatching { username() }.getOrNull()?.takeIf { it.isNotBlank() }
        sendToOwnDevices(WireMessage.ProfileUpdate(selfAccount(), bytes, null, name))
        for (conversation in storage.conversations.list().filter { it.type == ConversationType.DIRECT && isChosenPeer(it.id) }) {
            runCatching {
                ensureSession(conversation.id)
                sendWire(conversation.id, WireMessage.ProfileUpdate(selfAccount(), bytes, null, name))
            }
        }
    }

    suspend fun setGroupProfile(conversationId: String, bytes: ByteArray?): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (conversation.type != ConversationType.GROUP) return@withContext false
        if (!storage.members.isAdmin(conversationId, selfAccount())) return@withContext false
        storeProfile(conversationId, bytes)
        notifyChanged()
        val update = WireMessage.ProfileUpdate(selfAccount(), bytes, conversationId)
        syncSent(conversationId, update)
        val recipients = storage.members.listForConversation(conversationId)
            .map { it.memberOnion.value }
            .filter { it != selfAccount() }
        recipients.forEach { runCatching { ensureSession(it); sendWire(it, update) } }
        true
    }

    /** Copies [count] bytes, writing as it goes; throws if the input ends sooner. */
    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) throw java.io.EOFException("Connection ended $remaining bytes early")
            out.write(buffer, 0, read)
            remaining -= read
        }
        out.flush()
    }

    /** Attachments being received right now, so two transfers of one can't write the file at once. */
    private val receivingAttachments: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun setDisappearTimer(conversationId: String, seconds: Long) = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        storage.conversations.setDisappearSeconds(conversationId, seconds)
        val update = WireMessage.TimerUpdate(conversationId, seconds)
        syncSent(conversationId, update)
        deliverToConversation(conversation, update)
        Unit
    }

    suspend fun deleteForMe(messageId: String) = withContext(Dispatchers.IO) {
        val attachmentId = storage.messages.deleteLocally(messageId)
        attachmentId?.let { cleanupAttachment(it) }
        notifyChanged()
    }

    suspend fun deleteForEveryone(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        val existing = storage.messages.get(messageId)
        // Only our own outgoing messages can be recalled. Incoming messages also have
        // remoteDeletable set, so the flag alone would let us ask everyone to delete someone else's
        // message.
        if (existing == null) return@withContext
        if (existing.direction != MessageDirection.OUTGOING || existing.senderOnion != selfAccount()) {
            DiagnosticLog.record("send", "refused delete-for-everyone: not our own outgoing message")
            return@withContext
        }
        if (!existing.remoteDeletable) return@withContext
        storage.messages.markDeletedForEveryone(messageId)
        existing.attachmentId?.let { cleanupAttachment(it) }
        val delete = WireMessage.DeleteForEveryone(conversationId, listOf(messageId))
        syncSent(conversationId, delete)
        deliverToConversation(conversation, delete)
        Unit
    }

    /**
     * Handles one incoming envelope. True means it's dealt with (stored, queued or deliberately
     * dropped) and can be acknowledged; false means the sender should try again later.
     */
    suspend fun handleIncoming(fromOnion: String, ciphertext: ByteArray, viaRelay: Boolean = false): Boolean =
        when (intake(fromOnion, ciphertext, viaRelay)) {
            Intake.ACCEPTED, Intake.DROPPED -> true
            Intake.LATER, Intake.FAILED -> false
        }

    private suspend fun intake(fromOnion: String, ciphertext: ByteArray, viaRelay: Boolean): Intake = withContext(Dispatchers.IO) {
        // Check this first, before queueing too. fromOnion comes from the sender and libsignal
        // accepts any string as a name, but it ends up in conversation ids, database keys and file
        // paths. Something like "../../databases/niix.db" must never get that far.
        if (OnionAddress.parseOrNull(fromOnion) == null) {
            DiagnosticLog.record("receive", "dropped: sender address is not a valid onion address")
            return@withContext Intake.DROPPED
        }

        // Checked before decryption or any write, so an authenticated flood is cheap. Not
        // acknowledging makes the sender treat it as undelivered and back off, which is what we
        // want; acknowledging would silently drop messages a real peer thinks arrived.
        if (!senderWithinQuota(fromOnion, ciphertext.size)) {
            DiagnosticLog.record("receive", "sender exceeded its message quota -- refused")
            return@withContext Intake.LATER
        }

        // While locked the database (and with it the Signal session store) is closed, so the
        // message can't be processed yet. Queue the ciphertext instead: the sender sees a
        // successful delivery and we handle it on unlock. Nothing is decrypted or written to the
        // database while locked.
        if (!storage.appLock.isUnlocked()) {
            if (!storage.lockedInbox.enqueue(fromOnion, ciphertext, viaRelay)) {
                DiagnosticLog.record("receive", "locked inbox full -- message refused, sender will retry")
                return@withContext Intake.LATER
            }
            // Show the same generic notification as when unlocked (no sender, no preview), so a
            // locked phone gives away nothing more. Emitted directly because emitIncoming reads the
            // conversation title from the database, which is closed right now.
            _incoming.tryEmit(IncomingNotice(fromOnion, fromOnion.take(12), ""))
            return@withContext Intake.ACCEPTED
        }

        // From here on the sender is an account: the frame came from one of its devices.
        val fromDevice = fromOnion
        val (account, deviceId) = ownerOf(fromDevice)
        intakeFrom(account, deviceId, fromDevice, ciphertext, viaRelay)
    }

    private fun intakeFrom(fromOnion: String, deviceId: Int, fromDevice: String, ciphertext: ByteArray, viaRelay: Boolean): Intake {
        // An account that has a device list speaks only through the devices on it. Without this, a
        // removed first device would still pass: its address is the account's own.
        val devices = storage.devices.list(fromOnion)
        if (devices != null && devices.device(deviceId)?.onion?.value != fromDevice) {
            DiagnosticLog.record("receive", "dropped: sent from a device that is no longer on its account")
            return Intake.DROPPED
        }
        if (storage.blocklist.isBlocked(fromOnion)) {
            DiagnosticLog.record("receive", "dropped: sender is blocked")
            return Intake.DROPPED
        }
        if (storage.settings.getBool(SettingsStore.KEY_ALLOWLIST_ONLY, false) && !isKnownPeer(fromOnion)) {
            // Worth logging because neither side sees anything: the sender gets a normal-looking
            // result and the recipient gets nothing. A first message from someone not yet added is
            // exactly what this drops.
            DiagnosticLog.record("receive", "dropped: allowlist-only is on and sender is not a known contact")
            return Intake.DROPPED
        }
        if (!viaRelay) firstContactCheck(fromOnion, fromDevice, ciphertext)?.let { return it }
        return try {
            val plaintext = crypto.decrypt(fromOnion, deviceId, ciphertext)
            verifiedFirstContacts.remove(fromDevice)
            // A scanned code pins the expected key, so enforce the pin here on incoming messages,
            // not just when we start the conversation.
            if (!identityMatchesPinnedKey(fromOnion)) {
                DiagnosticLog.record("receive", "dropped: sender's identity key does not match the pinned key for that address")
                return Intake.DROPPED
            }
            dispatch(fromOnion, deviceId, WireCodec.decode(MessagePadding.unpad(plaintext)), viaRelay)
            notifyChanged()
            // A copy from a relay may have waited there for days; only a direct one says they're
            // there right now.
            if (!viaRelay) contactIsOnline(fromOnion)
            // In the background, since this may dial the sender while they're still waiting for our
            // acknowledgement on this connection.
            if (nostr != null) sendScope.launch { runCatching { maybeAnnounceNostrInbox(fromOnion) } }
            Intake.ACCEPTED
        } catch (_: AlreadyReceivedException) {
            // A second copy, say one direct and one through a relay. Acknowledged, so the sender
            // stops.
            Intake.DROPPED
        } catch (_: SessionMismatchException) {
            // Not acknowledged: once the new session reaches the sender, its next retry gets through.
            DiagnosticLog.record("receive", "could not decrypt: the session no longer matches the sender's, starting a new one")
            startNewSession(fromOnion, deviceId)
            Intake.FAILED
        } catch (e: Exception) {
            // Logged so a dropped message can be told apart from one that was never sent.
            DiagnosticLog.record("receive", "decrypt/dispatch failed (${e::class.java.simpleName})")
            Intake.FAILED
        }
    }

    /**
     * A recipient device confirmed a copy that reached it through a relay. Only that device is done:
     * the message counts as delivered once no device of a direct chat is still owed it. Before,
     * the first such receipt marked the whole message delivered, which stopped the retries to a
     * contact's other devices. Group messages stay as they are until every member's copy has been
     * acknowledged directly, which the retries already track per member.
     */
    private fun relayedCopyReceived(message: Message, fromAccount: String, deviceId: Int) {
        val conversation = storage.conversations.get(message.conversationId) ?: return
        if (conversation.type != ConversationType.DIRECT || message.deliveryState == DeliveryState.DELIVERED) return
        val trackingKey = devicePendingKey(message.id, fromAccount)
        val owed = storage.settings.getString(trackingKey)?.split('\n')?.filter { it.isNotBlank() }
        if (owed != null) {
            val device = recipientDevices(fromAccount).firstOrNull { it.id == deviceId }?.onion?.value
            val remaining = owed.filter { it != device }
            if (remaining.isNotEmpty()) {
                storage.settings.setString(trackingKey, remaining.joinToString("\n"))
                return
            }
            storage.settings.remove(trackingKey)
        }
        storage.messages.updateDeliveryState(message.id, DeliveryState.DELIVERED)
        notifyChanged()
    }

    private val sessionRestartedAt = ConcurrentHashMap<String, Long>()

    /**
     * Replaces the session with a device whose messages no longer decrypt. Without this, its
     * messages would fail on every retry until they were given up on a month later.
     *
     * Ours is thrown away and a new one built from the device's current key bundle, checked against
     * the identity key we know as for any session. Then a message goes to it on the new session, so
     * the first thing it gets from us carries what it needs to switch over too, and its retries get
     * through. Only for people we talk to, since this dials them and hands them our keys; and at
     * most every few minutes per device, however many messages fail meanwhile.
     */
    private fun startNewSession(account: String, deviceId: Int) {
        if (account != selfAccount() && !isChosenPeer(account)) return
        val device = recipientDevices(account).firstOrNull { it.id == deviceId } ?: return
        val nowMs = now()
        val last = sessionRestartedAt[device.onion.value]
        if (last != null && nowMs - last < SESSION_RESTART_INTERVAL_MILLIS) return
        sessionRestartedAt[device.onion.value] = nowMs
        sendScope.launch {
            runCatching {
                crypto.resetSession(account, deviceId)
                ensureDeviceSession(account, device)
                val filler = ByteArray(COVER_TRAFFIC_MIN_FILLER_BYTES).also(SecureRandom()::nextBytes)
                sendToDevice(account, device, MessagePadding.pad(WireCodec.encode(WireMessage.Dummy(filler))), republishKey = null, relay = true)
            }.onFailure { DiagnosticLog.record("receive", "could not start a new session yet (${it::class.java.simpleName})") }
        }
    }

    /**
     * Identity keys that addresses have served from their own onion service, waiting for the first
     * message from them to be checked against. Memory only.
     */
    private val verifiedFirstContacts = ConcurrentHashMap<String, ByteArray>()
    private val firstContactLookups: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val firstContactLimiter = SlidingWindowLimiter(maxWeight = 20, windowMillis = 60_000)

    /**
     * Ties a new sender's address to their identity key before libsignal stores it.
     *
     * The sender address in a direct frame is only a claim, and libsignal trusts any new name on
     * first use. Without a check, whoever reached us first could take an address we haven't talked
     * to yet: a group member we never messaged, say, or a stranger posing as someone the user knows
     * by address. Only the real owner of an onion address can answer on it, so we ask the address
     * for its key bundle over Tor and compare identities.
     *
     * The lookup runs in the background and the message is refused for now, so the sender isn't
     * kept waiting on a Tor round trip. Their retry, normally within half a minute, finds the
     * answer. Null means no check is needed: the identity is already known or pinned (the store
     * enforces pins), or this isn't a session-starting message and decryption will decide. Nostr
     * envelopes skip this, because they're addressed with a secret we only gave that peer.
     */
    private fun firstContactCheck(fromOnion: String, fromDevice: String, ciphertext: ByteArray): Intake? {
        if (crypto.hasRemoteIdentity(fromOnion)) return null
        if (storage.settings.getString(identityKeyStoreKey(fromOnion)) != null) return null
        val claimed = crypto.claimedIdentity(ciphertext) ?: return null
        // One of our own devices, from our list. Only our key will do, and the session handshake
        // proves the sender holds it.
        if (fromOnion == selfAccount()) {
            if (claimed.contentEquals(crypto.localIdentityKey())) return null
            DiagnosticLog.record("receive", "dropped: claims to be a device of this account with another key")
            return Intake.DROPPED
        }
        // A device of an account we already know, whose device list hasn't reached us yet: same
        // key, unfamiliar address. Taking it as a new contact would split that person in two, so it
        // waits for the list. A device of our own that isn't on our list was removed.
        if (claimed.contentEquals(crypto.localIdentityKey())) {
            DiagnosticLog.record("receive", "dropped: a device of this account that isn't on its device list")
            return Intake.DROPPED
        }
        if (storage.contacts.list().any { crypto.remoteIdentityKeyBytes(it.onionAddress.value)?.contentEquals(claimed) == true }) {
            DiagnosticLog.record("receive", "new device of a known contact, waiting for their device list")
            return Intake.LATER
        }
        verifiedFirstContacts[fromDevice]?.let { genuine ->
            if (genuine.contentEquals(claimed)) return null
            DiagnosticLog.record("receive", "dropped: first message claims an address whose own key is different")
            return Intake.DROPPED
        }
        // Rate-limited, since each lookup is a Tor connection and the address is the sender's
        // choice.
        if (firstContactLimiter.allow() && firstContactLookups.add(fromDevice)) {
            sendScope.launch {
                try {
                    val identity = crypto.bundleIdentityKey(fetchBundle(fromDevice))
                    if (verifiedFirstContacts.size > MAX_PENDING_FIRST_CONTACTS) verifiedFirstContacts.clear()
                    verifiedFirstContacts[fromDevice] = identity
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    DiagnosticLog.record("receive", "could not reach a new sender's address to confirm who they are")
                } finally {
                    firstContactLookups.remove(fromDevice)
                }
            }
        }
        return Intake.LATER
    }

    private fun isKnownPeer(onion: String): Boolean =
        storage.contacts.isKnown(onion) ||
            storage.conversations.get(onion) != null ||
            crypto.hasRemoteIdentity(onion)

    private fun dispatch(fromOnion: String, deviceId: Int, wire: WireMessage, viaRelay: Boolean = false) {
        if (fromOnion == selfAccount()) {
            dispatchFromOwnDevice(deviceId, wire)
            return
        }
        when (wire) {
            is WireMessage.Text -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                // Real group messages arrive inside GroupCiphertext, which is membership-checked
                // before unwrapping. A bare Text naming a group id would skip that, letting anyone
                // with a session (even a member who was just removed) post into the group. Direct
                // chats aren't affected because resolveIncomingConversationId pins them to the
                // sender's onion.
                if (!isAuthorizedForGroupIfGroup(convId, fromOnion)) {
                    // Log it, or a dropped message here would vanish without a trace.
                    DiagnosticLog.record("receive", "dropped Text: sender not a current member of that group")
                    return
                }
                val isNew = storage.messages.get(wire.messageId) == null
                ensureDirectConversationIfMissing(convId, fromOnion)
                storage.messages.insert(
                    incomingMessage(convId, wire.messageId, fromOnion, MessageType.TEXT, wire.body, null, wire.expiresSeconds)
                        .copy(replyToId = wire.replyToId),
                )
                if (isNew) emitIncoming(convId, fromOnion, wire.body)
                // A relayed copy is acknowledged with a receipt; a direct one already was.
                if (isNew && viaRelay) {
                    sendScope.launch {
                        runCatching {
                            ensureSession(fromOnion)
                            sendWireDirectOrRelay(fromOnion, WireMessage.Receipt(convId, wire.messageId, DeliveryState.DELIVERED.name))
                        }
                    }
                }
            }
            is WireMessage.AttachmentOffer -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                // Same as for Text: an offer naming a group has to come from a current member.
                if (!isAuthorizedForGroupIfGroup(convId, fromOnion)) return
                if (!isAcceptableOffer(wire)) return
                val resolvedFile = AttachmentFiles.resolve(attachmentsDir, wire.attachmentId) ?: return
                ensureDirectConversationIfMissing(convId, fromOnion)
                storage.attachments.insert(
                    Attachment(
                        id = wire.attachmentId,
                        conversationId = convId,
                        filePath = resolvedFile.absolutePath,
                        mimeType = wire.mimeType,
                        sizeBytes = wire.sizeBytes,
                        encKey = wire.encKey,
                        digest = wire.digest,
                        state = AttachmentState.PENDING,
                        createdAtEpochMillis = now(),
                    ),
                )
                val isNew = storage.messages.get(wire.messageId) == null
                storage.messages.insert(incomingMessage(convId, wire.messageId, fromOnion, MessageType.ATTACHMENT, "", wire.attachmentId, wire.expiresSeconds))
                if (isNew) emitIncoming(convId, fromOnion, "[Attachment]")
            }
            is WireMessage.DeleteForEveryone -> {
                // The message has to exist, belong to the conversation the request names, have been
                // written by the sender, and the sender still has to be in that conversation.
                // Checking ownership alone would still let a request name one conversation while
                // targeting a message in another, or let a removed member delete what they wrote.
                val claimedConversation = resolveIncomingConversationId(wire.conversationId, fromOnion)
                wire.targetMessageIds.forEach { messageId ->
                    val message = storage.messages.get(messageId)
                    val authorized = message != null &&
                        message.conversationId == claimedConversation &&
                        message.senderOnion == fromOnion &&
                        isAuthorizedForGroupIfGroup(claimedConversation, fromOnion)
                    if (authorized) {
                        storage.messages.markDeletedForEveryone(messageId)
                    } else if (message != null) {
                        DiagnosticLog.record("receive", "refused delete-for-everyone: request not authorized for that message")
                    }
                }
            }
            is WireMessage.TimerUpdate -> {
                // Unknown conversations are a no-op update anyway.
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                if (isAuthorizedForConversation(convId, fromOnion)) {
                    storage.conversations.setDisappearSeconds(convId, wire.seconds)
                }
            }
            is WireMessage.Receipt -> {
                // Receipts only ever describe messages this device sent. Otherwise any group member
                // could send a fake READ for someone else's message and make it disappear on a
                // third phone before it was opened, or mark it however they liked. Incoming
                // messages start their timer in markMessageRead.
                val message = storage.messages.get(wire.messageId) ?: return
                if (message.direction != MessageDirection.OUTGOING) return
                if (!isAuthorizedForConversation(message.conversationId, fromOnion)) return
                when (wire.state) {
                    READ_RECEIPT_STATE -> {
                        val duration = message.disappearSeconds
                        if (duration != null && message.expiresAtEpochMillis == null) {
                            storage.messages.startExpiry(wire.messageId, duration, now())
                            notifyChanged()
                        }
                    }
                    // Sent for a copy that came through a relay. A receipt can't un-deliver a
                    // message, so nothing else is accepted.
                    DeliveryState.DELIVERED.name -> relayedCopyReceived(message, fromOnion, deviceId)
                }
            }
            is WireMessage.GroupInvite -> {
                val existing = storage.conversations.get(wire.conversationId)
                if (existing == null) {
                    // Validate the invite's member and admin lists before offering it to the user.
                    // Accepting them as-is would let the sender hand themselves authority they were
                    // never given. See GroupMembershipPolicy.
                    if (!GroupMembershipPolicy.isAcceptableInvite(
                            inviter = fromOnion,
                            invitee = selfAccount(),
                            members = wire.members,
                            admins = wire.admins,
                        )
                    ) {
                        DiagnosticLog.record("receive", "dropped group invite: inconsistent member/admin lists")
                        return
                    }
                    val existingPending = storage.pendingGroupInvites.get(wire.conversationId)
                    if (existingPending == null || wire.epoch >= existingPending.epoch) {
                        storage.pendingGroupInvites.upsert(
                            PendingGroupInvite(
                                conversationId = wire.conversationId,
                                inviterOnion = fromOnion,
                                title = wire.title,
                                members = wire.members,
                                admins = wire.admins,
                                receivedAtEpochMillis = now(),
                                epoch = wire.epoch,
                            ),
                        )
                        notifyChanged()
                    }
                } else if (existing.type == ConversationType.GROUP) {
                    val previousMembers = storage.members.listForConversation(wire.conversationId)
                        .map { it.memberOnion.value }
                    // GroupMembershipPolicy makes the decisions here (admin check, epoch, who was
                    // removed) so they're covered by GroupMembershipPolicyTest.
                    val decision = GroupMembershipPolicy.evaluate(
                        senderIsAdmin = storage.members.isAdmin(wire.conversationId, fromOnion),
                        currentEpoch = existing.epoch,
                        incomingEpoch = wire.epoch,
                        currentMembers = previousMembers,
                        incomingMembers = wire.members,
                        incomingAdmins = wire.admins,
                        sender = fromOnion,
                        currentStateHash = currentGroupStateHash(wire.conversationId),
                        claimedPrevStateHash = wire.prevStateHash,
                        chainEstablished = isChainEstablished(wire.conversationId),
                    )
                    if (decision is GroupMembershipPolicy.Decision.Reject) {
                        DiagnosticLog.record("receive", "rejected group update: ${decision.reason}")
                    }
                    if (decision is GroupMembershipPolicy.Decision.Accept) {
                        storage.conversations.upsert(existing.copy(title = wire.title, epoch = wire.epoch))
                        storage.members.replaceAll(wire.conversationId, wire.members, wire.admins)
                        revokeSenderKeysFor(wire.conversationId, decision.removedMembers)
                        // Every member is now on a build that chains state, so the unchained path
                        // is closed for this group for good.
                        if (wire.prevStateHash != null) markChainEstablished(wire.conversationId)
                        rememberGroupState(wire.conversationId)
                        notifyChanged()
                    }
                }
            }
            is WireMessage.ProfileUpdate -> {
                val groupId = wire.conversationId
                if (groupId == null) {
                    storeProfile(fromOnion, wire.image)
                    applyPeerDisplayName(fromOnion, wire.displayName)
                    notifyChanged()
                } else if (storage.members.isAdmin(groupId, fromOnion)) {
                    storeProfile(groupId, wire.image)
                    notifyChanged()
                }
            }
            is WireMessage.RemoteWipe -> {
                // Handled in dispatch so it's never stored, shown or notified.
                //
                // Nothing is sent back either way. A reply would confirm the device exists and is
                // running the app, and a refusal would tell the sender they aren't nominated.
                if (storage.remoteWipe.shouldWipe(fromOnion, crypto.remoteIdentityKeyBytes(fromOnion))) {
                    DiagnosticLog.record("wipe", "remote wipe accepted from a nominated contact")
                    onRemoteWipe?.invoke()
                } else {
                    // Nobody gets a response, but an attempt is still worth logging.
                    DiagnosticLog.record("wipe", "remote wipe REFUSED (sender not nominated, or their key changed)")
                }
            }
            is WireMessage.RemoteWipeNomination -> {
                // Only accepted from a contact whose identity key is pinned. This list is who you
                // can wipe, and taking an unverified contact's word for it would let anyone who
                // reached you once put themselves on it. Withdrawing is always accepted; removing a
                // permission shouldn't be harder than granting it.
                val trusted = wire.nominated == false ||
                    (storage.contacts.isKnown(fromOnion) && identityMatchesPinnedKey(fromOnion))
                if (trusted) {
                    storage.remoteWipe.setCanWipe(fromOnion, wire.nominated)
                    DiagnosticLog.record(
                        "wipe",
                        if (wire.nominated) "a contact nominated this device to erase theirs"
                        else "a contact withdrew that nomination",
                    )
                }
            }
            is WireMessage.GroupLeave -> {
                val conversation = storage.conversations.get(wire.conversationId)
                // Only a current member can leave, and only themselves: fromOnion is authenticated
                // by the session and no membership list is read, so this can't affect anyone else.
                if (conversation != null && conversation.type == ConversationType.GROUP &&
                    storage.members.isMember(wire.conversationId, fromOnion) &&
                    wire.epoch > conversation.epoch
                ) {
                    val remaining = storage.members.listForConversation(wire.conversationId)
                        .filter { it.memberOnion.value != fromOnion }
                    // If the last admin left, promote the lowest-sorted remaining member. Every
                    // device works out the same answer from its own state, so nobody has to trust
                    // the leaver.
                    val remainingAdmins = remaining.filter { it.role == GroupRole.ADMIN }
                        .map { it.memberOnion.value }
                    val admins = remainingAdmins.ifEmpty {
                        listOfNotNull(remaining.map { it.memberOnion.value }.minOrNull())
                    }
                    storage.conversations.upsert(conversation.copy(epoch = wire.epoch))
                    storage.members.replaceAll(
                        wire.conversationId,
                        remaining.map { it.memberOnion.value },
                        admins,
                    )
                    revokeSenderKeysFor(wire.conversationId, listOf(fromOnion))
                    rememberGroupState(wire.conversationId)
                    notifyChanged()
                } else {
                    DiagnosticLog.record("receive", "dropped group leave: not a current member, or stale epoch")
                }
            }
            is WireMessage.SenderKeyDistribution -> {
                if (isAuthorizedForConversation(wire.conversationId, fromOnion)) {
                    val distributionId = runCatching {
                        crypto.processGroupDistribution(fromOnion, deviceId, wire.distributionBytes)
                    }.getOrNull()
                    if (distributionId != null) {
                        storage.groupRemoteSenderKeys.record(wire.conversationId, fromOnion, distributionId)
                    }
                }
            }
            is WireMessage.GroupCiphertext -> {
                // Both failures below throw instead of returning. A return would let handleIncoming
                // acknowledge the message while we threw it away; throwing keeps it pending on the
                // sender's side, and their retry sends the key first.
                if (!isAuthorizedForConversation(wire.conversationId, fromOnion)) {
                    // Usually a membership update that hasn't reached this phone yet.
                    DiagnosticLog.record("receive", "group message from a sender not yet known as a member -- not acknowledged, sender will retry")
                    throw IllegalStateException("group sender not authorised")
                }
                val innerBytes = runCatching {
                    MessagePadding.unpad(crypto.groupDecrypt(fromOnion, deviceId, wire.ciphertext))
                }.getOrNull()
                val inner = innerBytes?.let { runCatching { WireCodec.decode(it) }.getOrNull() }
                if (inner == null) {
                    // Almost always the sender's group key hasn't arrived yet.
                    DiagnosticLog.record("receive", "group message could not be decrypted -- not acknowledged, sender will retry with its key")
                    throw IllegalStateException("group message undecryptable")
                }
                // Only content travels in the group key; anything else inside it is refused.
                if (inner !is WireMessage.Text && inner !is WireMessage.AttachmentOffer) return
                dispatch(fromOnion, deviceId, inner)
            }
            // Old builds still send the removed relay messages, and cover traffic is meant to be
            // thrown away. Accepted so the senders don't retry.
            is WireMessage.Retired, is WireMessage.Dummy -> Unit
            is WireMessage.NostrInbox -> recordNostrOutbox(fromOnion, deviceId, wire)
            is WireMessage.Devices -> {
                if (storage.devices.replaceIfNewer(fromOnion, wire.list)) {
                    DiagnosticLog.record("receive", "a contact's device list changed (${wire.list.devices.size} device(s))")
                    // New devices need our mailbox details and, for groups, our key; both follow
                    // the next time something is sent to them.
                    sendScope.launch { runCatching { maybeAnnounceNostrInbox(fromOnion) } }
                    // And whether we allow calls, or they couldn't call us from them.
                    if (storage.callPermissions.isAllowedByMeRecorded(fromOnion)) oweCallPermission(fromOnion)
                }
            }
            is WireMessage.CallPermission -> {
                // Their side of the agreement. On its own it allows nothing: calls also need the
                // user to have allowed them here, which only the user can do. Withdrawing ends any
                // call with them at once.
                storage.callPermissions.setAllowedByThem(fromOnion, wire.allowed)
                if (!wire.allowed) calls.permissionWithdrawn(fromOnion, byThem = true)
                DiagnosticLog.record("call", if (wire.allowed) "a contact allowed calls" else "a contact stopped allowing calls")
            }
            // Calls are live or not at all. One that came through a relay waited there, so it's
            // stale by definition; they're never sent that way.
            is WireMessage.CallOffer -> if (!viaRelay) calls.onOffer(fromOnion, deviceId, wire)
            is WireMessage.CallAnswer -> if (!viaRelay) calls.onAnswer(fromOnion, deviceId, wire)
            is WireMessage.CallEnd -> if (!viaRelay) calls.onEnd(fromOnion, deviceId, wire)
            // Only ever valid between devices of one account.
            is WireMessage.SyncSent, is WireMessage.ContactUpdate, is WireMessage.WipeKey ->
                DiagnosticLog.record("receive", "dropped a device-sync message from another account")
        }
    }

    /**
     * Messages from another device of this account. Those hold the account's identity key, so they
     * speak for the account: what it sent, which contacts it has, which devices it's on.
     */
    private fun dispatchFromOwnDevice(deviceId: Int, wire: WireMessage) {
        when (wire) {
            is WireMessage.SyncSent -> applySentElsewhere(wire.conversationId, wire.inner)
            is WireMessage.ContactUpdate -> applyContactUpdate(wire)
            is WireMessage.Devices -> applyOwnDeviceList(wire.list)
            is WireMessage.NostrInbox -> recordNostrOutbox(selfAccount(), deviceId, wire)
            is WireMessage.ProfileUpdate -> if (wire.conversationId == null) {
                storeProfile(SELF_PROFILE, wire.image)
                wire.displayName?.trim()?.take(MAX_DISPLAY_NAME_CHARS)?.takeIf { it.isNotBlank() }?.let {
                    storage.settings.setString(SettingsStore.KEY_USERNAME, it)
                }
            }
            is WireMessage.WipeKey -> acceptWipeKey(wire)
            is WireMessage.Dummy, is WireMessage.Retired -> Unit
            else -> DiagnosticLog.record("receive", "ignored a message from another device of this account")
        }
    }

    /** Something this account sent from another device, recorded here as if sent from this one. */
    private fun applySentElsewhere(conversationId: String, inner: WireMessage) {
        val self = selfAccount()
        val conversation = storage.conversations.get(conversationId)
        when (inner) {
            is WireMessage.Text -> {
                ensureConversationFromOwnDevice(conversationId) ?: return
                storage.messages.insert(
                    sentElsewhere(conversationId, inner.messageId, MessageType.TEXT, inner.body, null, inner.expiresSeconds)
                        .copy(replyToId = inner.replyToId),
                )
            }
            is WireMessage.AttachmentOffer -> {
                ensureConversationFromOwnDevice(conversationId) ?: return
                if (!isAcceptableOffer(inner)) return
                val file = AttachmentFiles.resolve(attachmentsDir, inner.attachmentId) ?: return
                storage.attachments.insert(
                    Attachment(
                        id = inner.attachmentId,
                        conversationId = conversationId,
                        filePath = file.absolutePath,
                        mimeType = inner.mimeType,
                        sizeBytes = inner.sizeBytes,
                        encKey = inner.encKey,
                        digest = inner.digest,
                        state = AttachmentState.PENDING,
                        createdAtEpochMillis = now(),
                    ),
                )
                storage.messages.insert(
                    sentElsewhere(conversationId, inner.messageId, MessageType.ATTACHMENT, "", inner.attachmentId, inner.expiresSeconds),
                )
            }
            is WireMessage.DeleteForEveryone -> inner.targetMessageIds.forEach { id ->
                val message = storage.messages.get(id) ?: return@forEach
                if (message.conversationId == conversationId && message.senderOnion == self) {
                    storage.messages.markDeletedForEveryone(id)
                    message.attachmentId?.let { cleanupAttachment(it) }
                }
            }
            is WireMessage.TimerUpdate -> if (conversation != null) {
                storage.conversations.setDisappearSeconds(conversationId, inner.seconds)
            }
            is WireMessage.GroupInvite -> {
                if (conversation != null && (conversation.type != ConversationType.GROUP || inner.epoch <= conversation.epoch)) return
                val removed = storage.members.listForConversation(conversationId).map { it.memberOnion.value } - inner.members.toSet()
                storage.conversations.upsert(
                    conversation?.copy(title = inner.title, epoch = inner.epoch)
                        ?: Conversation(conversationId, ConversationType.GROUP, inner.title, 0, now(), epoch = inner.epoch),
                )
                storage.members.replaceAll(conversationId, inner.members, inner.admins)
                storage.pendingGroupInvites.delete(conversationId)
                revokeSenderKeysFor(conversationId, removed)
                if (inner.prevStateHash != null) markChainEstablished(conversationId)
                rememberGroupState(conversationId)
            }
            is WireMessage.GroupLeave -> if (conversation?.type == ConversationType.GROUP) removeGroupLocally(conversationId)
            is WireMessage.ProfileUpdate -> if (conversation?.type == ConversationType.GROUP) {
                storeProfile(conversationId, inner.image)
            }
            else -> DiagnosticLog.record("receive", "ignored a sync message of an unexpected kind")
        }
    }

    /** The conversation a message sent from another device belongs in, created if it's a new direct chat. */
    private fun ensureConversationFromOwnDevice(conversationId: String): Conversation? {
        storage.conversations.get(conversationId)?.let { existing ->
            if (existing.pending) storage.conversations.setPending(conversationId, false)
            return existing
        }
        // Only a direct chat can be new here; a group arrives through its invite first.
        if (OnionAddress.parseOrNull(conversationId) == null) return null
        val title = storage.contacts.get(conversationId)?.displayName?.takeIf { it.isNotBlank() } ?: conversationId.take(8)
        return Conversation(conversationId, ConversationType.DIRECT, title, 0, now()).also { storage.conversations.upsert(it) }
    }

    private fun sentElsewhere(
        conversationId: String,
        messageId: String,
        type: MessageType,
        body: String,
        attachmentId: String?,
        expiresSeconds: Long,
    ) = Message(
        id = messageId,
        conversationId = conversationId,
        senderOnion = selfAccount(),
        direction = MessageDirection.OUTGOING,
        type = type,
        body = body,
        attachmentId = attachmentId,
        createdAtEpochMillis = now(),
        expiresAtEpochMillis = null,
        // SENT, not PENDING: the device that sent it delivers it, and this one mustn't retry it.
        deliveryState = DeliveryState.SENT,
        deleted = false,
        remoteDeletable = true,
        disappearSeconds = clampDisappearSeconds(expiresSeconds).takeIf { it > 0 },
    )

    private fun applyContactUpdate(update: WireMessage.ContactUpdate) {
        val onion = update.onion
        if (onion == selfAccount()) return
        if (update.blocked) {
            storage.blocklist.block(onion)
            stopAllowingCalls(onion, announce = false)
            return
        }
        if (update.unblocked) {
            storage.blocklist.unblock(onion)
            // Without a name they aren't a contact on the device that unblocked them, only someone
            // who had been blocked, and that doesn't make them a contact here either.
            if (update.displayName.isBlank() && storage.contacts.get(onion) == null) return
        }
        update.pinnedIdentityKey?.let { key ->
            if (!crypto.isValidIdentityKeyBytes(key)) return
            storage.settings.setString(identityKeyStoreKey(onion), Base64.getEncoder().encodeToString(key))
        }
        val name = update.displayName.trim().take(MAX_DISPLAY_NAME_CHARS).ifBlank { onion.take(8) }
        val existing = storage.contacts.get(onion)
        storage.contacts.upsert(
            existing?.copy(displayName = name) ?: Contact(
                onionAddress = OnionAddress.parse(onion),
                displayName = name,
                fingerprint = crypto.remoteFingerprint(onion) ?: IdentityFingerprint(""),
                trustState = TrustState.UNVERIFIED,
                addedAtEpochMillis = now(),
            ),
        )
        if (update.verified) storage.contacts.setTrustState(onion, TrustState.VERIFIED)
        val conversation = ensureConversationFromOwnDevice(onion)
        if (conversation != null && conversation.title != name) storage.conversations.upsert(conversation.copy(title = name, pending = false))
        // Calls allowed or stopped on another device. That device tells the contact; this one
        // only records it, bound to the key it knows for them.
        when (update.callsAllowed) {
            true -> {
                val key = crypto.remoteIdentityKeyBytes(onion)
                    ?: update.pinnedIdentityKey?.takeIf { crypto.isValidIdentityKeyBytes(it) }
                // The other device only allows calls with a verified contact, and verification
                // travels in this same update, so this holds unless something is off.
                if (key != null && update.verified) {
                    storage.callPermissions.allow(onion, key)
                } else {
                    DiagnosticLog.record("call", "calls were allowed on another device, but this one has no verified key for that contact yet")
                }
            }
            false -> {
                storage.callPermissions.disallow(onion)
                calls.permissionWithdrawn(onion, byThem = false)
            }
            null -> Unit
        }
    }

    /** Our own list, changed on another device. If it no longer includes this device, we were removed. */
    private fun applyOwnDeviceList(list: DeviceList) {
        if (!storage.devices.replaceIfNewer(selfAccount(), list)) return
        refreshWipeBeacon()
        if (list.device(crypto.localDeviceId())?.onion?.value != selfDevice()) {
            DiagnosticLog.record("wipe", "this device was removed from its account")
            onRemoteWipe?.invoke()
        }
    }

    /** Tells this account's other devices about something sent from this one. */
    private fun syncSent(conversationId: String, inner: WireMessage) {
        sendToOwnDevices(WireMessage.SyncSent(conversationId, inner))
    }

    /**
     * Tells this account's other devices how this one now has [onion]. Whether calls are allowed
     * is only included when [includeCalls], i.e. when that's what changed, so a rename from a
     * device that hasn't heard about a change yet can't undo it.
     */
    private fun syncContact(onion: String, unblocked: Boolean = false, includeCalls: Boolean = false) {
        val contact = storage.contacts.get(onion)
        val pinned = storage.settings.getString(identityKeyStoreKey(onion))?.let { Base64.getDecoder().decode(it) }
        sendToOwnDevices(
            WireMessage.ContactUpdate(
                onion = onion,
                displayName = contact?.displayName.orEmpty(),
                pinnedIdentityKey = pinned,
                verified = contact?.trustState == TrustState.VERIFIED,
                blocked = storage.blocklist.isBlocked(onion),
                unblocked = unblocked,
                callsAllowed = if (includeCalls) {
                    storage.callPermissions.allowedByMe(onion, crypto.remoteIdentityKeyBytes(onion) ?: pinned)
                } else {
                    null
                },
            ),
        )
    }

    /**
     * Best effort, in the background: a device that's offline gets it from its Nostr inbox, and one
     * that misses it altogether is no worse off than a phone that was switched off.
     */
    private fun sendToOwnDevices(wire: WireMessage) {
        if (recipientDevices(selfAccount()).isEmpty()) return
        sendScope.launch { runCatching { fanOut(selfAccount(), wire, republishKey = null, relay = true) } }
    }

    private fun isAcceptableOffer(wire: WireMessage.AttachmentOffer): Boolean {
        if (!AttachmentFiles.isValidId(wire.attachmentId)) return false
        if (wire.sizeBytes < 0 || wire.sizeBytes > MAX_ATTACHMENT_BYTES) return false
        // The type decides how the file is shown and what it's saved as, so it has to look like a
        // MIME type and nothing else.
        if (!MIME_TYPE.matches(wire.mimeType)) return false
        // An id already in use belongs to the attachment that has it. A second offer with the same
        // id, here or in another conversation, would point a new message at someone else's file,
        // and deleting that message would delete the file.
        if (storage.attachments.get(wire.attachmentId) != null) return false
        // Exact lengths. Both are fixed-size, and the generic block limit would let a sender store
        // a 64 KB "digest" that only fails much later and looks like corruption.
        return wire.digest?.size == ATTACHMENT_DIGEST_BYTES && wire.encKey.size == ATTACHMENT_KEY_BYTES
    }

    // ---- This account's devices ----

    /** The devices this account is on, this one included. */
    suspend fun ownDevices(): List<Device> = withContext(Dispatchers.IO) { devicesOf(selfAccount()) }

    fun thisDeviceId(): Int = crypto.localDeviceId()

    /**
     * Shows a QR code's worth of invite on a device that should join an existing account, and waits
     * for one of that account's devices to deliver it. Only for a device with nothing of its own
     * yet: linking replaces its identity. Null until Tor has published this device's address.
     */
    suspend fun startLinkInvite(): LinkInvite? = withContext(Dispatchers.IO) {
        if (!canJoin()) return@withContext null
        val onion = selfOnionProvider()?.let(OnionAddress::parseOrNull) ?: return@withContext null
        LinkInvite.create(onion).also { pendingLink = it }
    }

    /**
     * Whether this device can still join another account: it has nothing of its own to lose, no
     * contacts and no other devices, and isn't already part of an account.
     */
    suspend fun canJoinAccount(): Boolean = withContext(Dispatchers.IO) { canJoin() }

    private fun canJoin(): Boolean =
        storage.contacts.list().isEmpty() &&
            storage.settings.getString(SettingsStore.KEY_ACCOUNT) == null &&
            storage.devices.list(selfAccount()) == null

    fun cancelLinkInvite() {
        pendingLink = null
    }

    @Volatile
    private var pendingLink: LinkInvite? = null

    private val _linked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once this device has joined an account. */
    val linked: SharedFlow<Unit> = _linked.asSharedFlow()

    enum class LinkResult { LINKED, NOT_A_LINK_CODE, TOO_MANY_DEVICES, UNREACHABLE }

    /**
     * Adds the device showing [code] to this account: hands it the identity key, contacts and
     * groups over Tor, then tells contacts and our other devices about it. History stays here.
     */
    suspend fun linkDevice(code: String): LinkResult = withContext(Dispatchers.IO) {
        val invite = LinkInvite.parseOrNull(code.trim()) ?: return@withContext LinkResult.NOT_A_LINK_CODE
        val self = selfAccount()
        val current = storage.devices.list(self) ?: DeviceList.single(OnionAddress.parse(self))
        if (current.devices.size >= DeviceList.MAX_DEVICES) return@withContext LinkResult.TOO_MANY_DEVICES
        if (current.devices.any { it.onion == invite.onion }) return@withContext LinkResult.LINKED
        val newDevice = Device(current.nextFreeId(), invite.onion)
        val next = DeviceList(current.epoch + 1, current.devices + newDevice)
        val sealed = linkBundle(self, newDevice.id, next).seal(invite)
        val accepted = try {
            transport.connect(invite.onion, servicePort).use { connection ->
                writeFrame(connection.output, FRAME_LINK, selfDevice(), sealed)
                DataInputStream(connection.input).readByte().toInt() == FRAME_ACK
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!accepted) return@withContext LinkResult.UNREACHABLE
        DiagnosticLog.record("devices", "linked a new device")
        publishOwnDeviceList(next)
        // Our name and picture, which the bundle leaves out to stay small.
        val picture = loadProfile(SELF_PROFILE)
        if (picture != null) sendToOwnDevices(WireMessage.ProfileUpdate(self, picture, null, username()))
        LinkResult.LINKED
    }

    private fun linkBundle(self: String, deviceId: Int, devices: DeviceList): LinkBundle {
        val blocked = storage.blocklist.list()
        val contacts = storage.contacts.list().map { contact ->
            val onion = contact.onionAddress.value
            val pin = storage.settings.getString(identityKeyStoreKey(onion))
            LinkBundle.Contact(
                onion = onion,
                name = contact.displayName,
                verified = contact.trustState == TrustState.VERIFIED,
                identityKey = pin?.let { Base64.getDecoder().decode(it) } ?: crypto.remoteIdentityKeyBytes(onion),
                pinned = pin != null,
                devices = storage.devices.list(onion),
            )
        }
        val groups = storage.conversations.list().filter { it.type == ConversationType.GROUP }.map { group ->
            val members = storage.members.listForConversation(group.id)
            LinkBundle.Group(
                id = group.id,
                title = group.title,
                epoch = group.epoch,
                members = members.map { it.memberOnion.value },
                admins = members.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value },
                disappearSeconds = group.disappearSeconds,
                chained = isChainEstablished(group.id),
            )
        }
        return LinkBundle(
            account = self,
            identityKeyPair = crypto.exportIdentityKeyPair(),
            deviceId = deviceId,
            devices = devices,
            wipeKey = currentWipeKey(),
            username = storage.settings.getString(SettingsStore.KEY_USERNAME).orEmpty(),
            contacts = contacts,
            groups = groups,
            blocked = blocked,
        )
    }

    /**
     * The new device's side of [linkDevice]: becomes a device of the account in the bundle. All or
     * nothing, so a failure leaves the device as it was and the code can be scanned again.
     */
    private fun joinAccount(bundle: LinkBundle) {
        storage.database.inTransaction {
            crypto.adoptIdentity(bundle.identityKeyPair, bundle.deviceId)
            storage.settings.setString(SettingsStore.KEY_ACCOUNT, bundle.account)
            if (bundle.username.isNotBlank()) storage.settings.setString(SettingsStore.KEY_USERNAME, bundle.username)
            storage.devices.replaceIfNewer(bundle.account, bundle.devices)
            for (contact in bundle.contacts) {
                contact.identityKey?.takeIf(crypto::isValidIdentityKeyBytes)?.let { key ->
                    crypto.trustIdentity(contact.onion, key)
                    if (contact.pinned) {
                        storage.settings.setString(identityKeyStoreKey(contact.onion), Base64.getEncoder().encodeToString(key))
                    }
                }
                storage.contacts.upsert(
                    Contact(
                        onionAddress = OnionAddress.parse(contact.onion),
                        displayName = contact.name,
                        fingerprint = IdentityFingerprint(""),
                        trustState = if (contact.verified) TrustState.VERIFIED else TrustState.UNVERIFIED,
                        addedAtEpochMillis = now(),
                    ),
                )
                storage.conversations.upsert(Conversation(contact.onion, ConversationType.DIRECT, contact.name, 0, now()))
                contact.devices?.let { storage.devices.replaceIfNewer(contact.onion, it) }
            }
            for (group in bundle.groups) {
                storage.conversations.upsert(
                    Conversation(group.id, ConversationType.GROUP, group.title, group.disappearSeconds, now(), epoch = group.epoch),
                )
                storage.members.replaceAll(group.id, group.members, group.admins)
                if (group.chained) markChainEstablished(group.id)
                rememberGroupState(group.id)
            }
            bundle.blocked.forEach(storage.blocklist::block)
            crypto.ensureKeysInitialized()
        }
        crypto.localOnionForFingerprint = bundle.account
        storage.settings.remove(SettingsStore.KEY_LINK_PENDING)
        setWipeKey(bundle.wipeKey, bundle.devices.epoch)
        DiagnosticLog.record("devices", "joined an account as device ${bundle.deviceId}")
    }

    /**
     * Takes a device off this account and tells it to erase itself. Contacts stop sending to it once
     * the new list reaches them. It keeps the identity key it had, which is why it's told to wipe.
     */
    suspend fun removeDevice(deviceId: Int): Boolean = withContext(Dispatchers.IO) {
        if (deviceId == crypto.localDeviceId()) return@withContext false
        val self = selfAccount()
        val current = storage.devices.list(self) ?: return@withContext false
        val target = current.device(deviceId) ?: return@withContext false
        runCatching { sendWipe(target.onion.value, currentWipeKey()) }
        // It knows the old key, so the rest of us move to a new one it can't use against us.
        val list = DeviceList(current.epoch + 1, current.devices - target)
        publishOwnDeviceList(list)
        setWipeKey(WipeBeacon.newKey(), list.epoch)
        storage.settings.removeWithPrefix("devicepending:$WIPE_KEY_TRACKING")
        storage.settings.setString(SettingsStore.KEY_WIPE_KEY_OWED_SINCE, now().toString())
        sendScope.launch { runCatching { deliverWipeKey() } }
        DiagnosticLog.record("devices", "removed a device from this account")
        true
    }

    /**
     * Sends the current wipe key to each of our devices that hasn't acknowledged it yet. A device
     * that missed it could no longer be wiped by the others, nor wipe them, so this goes on with
     * the retries of unsent messages until every device has it.
     */
    private suspend fun deliverWipeKey() {
        if (storage.settings.getString(SettingsStore.KEY_WIPE_KEY_OWED_SINCE) == null) return
        val epoch = wipeKeyEpoch()
        val wire = WireMessage.WipeKey(currentWipeKey(), epoch)
        if (fanOut(selfAccount(), wire, republishKey = "$WIPE_KEY_TRACKING$epoch", relay = true) == DeliveryOutcome.DELIVERED) {
            storage.settings.remove(SettingsStore.KEY_WIPE_KEY_OWED_SINCE)
            DiagnosticLog.record("wipe", "every device has the new wipe key")
        }
    }

    /**
     * A wipe key from another of our devices. The newer one wins, so a copy that was held up on a
     * relay can't put an old key back. Two devices removing others at the same moment both make
     * a key for the same epoch; the larger is kept, so every device settles on the same one.
     */
    private fun acceptWipeKey(wire: WireMessage.WipeKey) {
        val epoch = wipeKeyEpoch()
        val newer = wire.epoch > epoch || (wire.epoch == epoch && compareKeys(wire.key, currentWipeKey()) > 0)
        if (!newer) {
            DiagnosticLog.record("wipe", "ignored a wipe key older than the one this device has")
            return
        }
        setWipeKey(wire.key, wire.epoch)
    }

    private fun compareKeys(a: ByteArray, b: ByteArray): Int = java.util.Arrays.compareUnsigned(a, b)

    private fun wipeKeyEpoch(): Long = storage.settings.getString(SettingsStore.KEY_WIPE_KEY_EPOCH)?.toLongOrNull() ?: 0L

    private fun setWipeKey(key: ByteArray, epoch: Long) {
        storage.settings.setString(SettingsStore.KEY_WIPE_KEY_EPOCH, epoch.toString())
        refreshWipeBeacon(key)
    }

    /** The account's wipe key. Kept in the database, so it's in backups; the beacon mirrors it. */
    private fun currentWipeKey(): ByteArray =
        storage.settings.getString(SettingsStore.KEY_WIPE_KEY)?.let { Base64.getDecoder().decode(it) }
            ?: WipeBeacon.newKey().also { storage.settings.setString(SettingsStore.KEY_WIPE_KEY, Base64.getEncoder().encodeToString(it)) }

    /**
     * Writes the beacon from the database: the wipe key, [key] if it's changing, and our other
     * devices' addresses. Run at every start too, so every install has one.
     */
    fun refreshWipeBeacon(key: ByteArray = currentWipeKey()) {
        storage.settings.setString(SettingsStore.KEY_WIPE_KEY, Base64.getEncoder().encodeToString(key))
        runCatching { storage.wipeBeacon.update(key, recipientDevices(selfAccount()).map { it.onion.value }) }
            .onFailure { DiagnosticLog.record("wipe", "could not update the wipe beacon (${it.javaClass.simpleName})") }
    }

    private suspend fun sendWipe(toOnion: String, key: ByteArray): Boolean =
        transport.connect(OnionAddress.parse(toOnion), servicePort).use { connection ->
            writeFrame(connection.output, FRAME_WIPE, selfDevice(), key)
            runCatching { DataInputStream(connection.input).readByte().toInt() == FRAME_ACK }.getOrDefault(false)
        }

    /** Stores our new device list and sends it to everyone who sends us anything. */
    private suspend fun publishOwnDeviceList(list: DeviceList) {
        val self = selfAccount()
        storage.devices.replaceIfNewer(self, list)
        refreshWipeBeacon()
        val wire = WireMessage.Devices(list)
        val recipients = (
            storage.contacts.list().map { it.onionAddress.value } +
                storage.conversations.list().filter { it.type == ConversationType.GROUP }
                    .flatMap { storage.members.listForConversation(it.id) }.map { it.memberOnion.value } +
                self
            ).distinct().filter { !storage.blocklist.isBlocked(it) }
        recipients.forEach { account ->
            sendScope.launch { runCatching { fanOut(account, wire, republishKey = null, relay = true) } }
        }
        notifyChanged()
    }

    /** Where to send the account's wipe key, read before the local wipe deletes the beacon. */
    class PreparedWipe internal constructor(internal val key: ByteArray, internal val devices: List<String>)

    /**
     * Reads what's needed to wipe this account's other devices. Needs no database, so it works
     * under the duress passcode, but has to happen before the local wipe. Null if there's nothing
     * to send or the beacon can't be opened.
     */
    fun prepareWipeOfOtherDevices(): PreparedWipe? {
        val (key, devices) = storage.wipeBeacon.read() ?: return null
        return if (devices.isEmpty()) null else PreparedWipe(key, devices)
    }

    /**
     * Sends the wipe key to each device, retrying while Tor is up until each has taken it or
     * [deadlineMillis] (elapsed time) passes. Returns the devices it didn't reach, if any.
     */
    suspend fun deliverWipe(wipe: PreparedWipe, deadlineMillis: Long): PreparedWipe? {
        val remaining = wipe.devices.toMutableList()
        while (remaining.isNotEmpty()) {
            if (transport.state.value == TransportState.RUNNING) {
                val reached = remaining.filter { runCatching { sendWipe(it, wipe.key) }.getOrDefault(false) }
                remaining.removeAll(reached)
            }
            if (remaining.isEmpty() || elapsedMillis() + WIPE_RETRY_MILLIS > deadlineMillis) break
            kotlinx.coroutines.delay(WIPE_RETRY_MILLIS)
        }
        return if (remaining.isEmpty()) null else PreparedWipe(wipe.key, remaining)
    }

    private fun elapsedMillis() = System.nanoTime() / 1_000_000

    /** Each device has its own inbox, so an announcement is filed under the device that sent it. */
    private fun recordNostrOutbox(account: String, deviceId: Int, wire: WireMessage.NostrInbox) {
        if (nostr == null) return
        if (!nostrStore.recordOutbox(deviceOnion(account, deviceId), wire.secret, wire.relays)) {
            DiagnosticLog.record("receive", "ignored a Nostr inbox announcement with no usable relays")
        }
    }

    private suspend fun deliverToConversation(conversation: Conversation, wire: WireMessage): Boolean {
        return when (conversation.type) {
            ConversationType.DIRECT -> sendWire(conversation.id, wire)
            ConversationType.GROUP -> deliverToGroup(conversation, wire)
        }
    }

    /**
     * Sends a group message to every member and only reports success once all of them have it.
     *
     * Each member is tracked separately: the ones still owed the message are stored under its id,
     * retries only go to them, and it stays pending until the list is empty. Someone who leaves
     * drops off the list; someone who joins later isn't added, so they don't get older messages.
     * Like direct messages, sends fall back to Nostr, and a relayed copy still counts as owed until
     * the member acknowledges directly.
     *
     * Members get our group key before the message unless we know they already have it, and always
     * on a retry. Without the key they can't decrypt anything from us.
     */
    private suspend fun deliverToGroup(conversation: Conversation, wire: WireMessage): Boolean {
        val members = storage.members.listForConversation(conversation.id)
            .map { it.memberOnion.value }
            .filter { it != selfAccount() }
        if (members.isEmpty()) return false

        val trackingKey = groupTrackingId(wire)?.let { pendingRecipientsKey(it) }
        val stored = trackingKey?.let { storage.settings.getString(it) }
        val firstAttempt = stored == null
        val remaining = if (stored == null) members else stored.split('\n').filter { it.isNotBlank() && it in members }
        if (remaining.isEmpty()) {
            trackingKey?.let { storage.settings.remove(it) }
            return true
        }

        // Only content is wrapped in the group key; control messages stay pairwise.
        val keyState = if (wire is WireMessage.Text || wire is WireMessage.AttachmentOffer) {
            ensureGroupSenderKey(conversation)
        } else {
            null
        }
        // Taken before encrypting so it describes the key at or before this message. A key from a
        // later position can't decrypt an earlier message.
        val keyWire = keyState?.let { state ->
            runCatching {
                WireMessage.SenderKeyDistribution(
                    conversation.id,
                    selfAccount(),
                    crypto.groupDistributionFor(state.distributionId),
                )
            }.getOrNull()
        }
        val outgoing = if (keyState != null) groupWrapIfApplicable(conversation, wire) ?: wire else wire
        val holdersKey = keyState?.let { keyHoldersKey(conversation.id, it.distributionId) }
        val holders = holdersKey?.let { readOnionSet(it) } ?: emptySet()

        val stillOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val newHolders: MutableSet<String> = ConcurrentHashMap.newKeySet()
        // Members side by side, a few at a time: one at a time, a group of ten took ten times as
        // long as a single chat, and a member who was offline held up everyone after them.
        val permits = Semaphore(PARALLEL_GROUP_MEMBERS)
        coroutineScope {
            for (onion in remaining) {
                launch {
                    permits.withPermit {
                        runCatching { ensureSession(onion) }
                        if (keyWire != null && (!firstAttempt || onion !in holders)) {
                            val keyOutcome = try {
                                sendWireDirectOrRelay(onion, keyWire, republishKey = groupTrackingId(wire)?.let { "$it|key|$onion" })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                DeliveryOutcome.FAILED
                            }
                            // No key, no message. A locked member's phone would accept the message
                            // now and then fail to open it, so they stay owed and get both next time.
                            if (keyOutcome == DeliveryOutcome.FAILED) {
                                stillOwed += onion
                                return@withPermit
                            }
                        }
                        val outcome = try {
                            sendWireDirectOrRelay(onion, outgoing, republishKey = groupTrackingId(wire)?.let { "$it|$onion" })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            DeliveryOutcome.FAILED
                        }
                        if (outcome == DeliveryOutcome.DELIVERED) {
                            // A direct acknowledgement means they decrypted it, so they have the key
                            // and don't need it again on first attempts.
                            if (keyState != null) newHolders += onion
                        } else {
                            stillOwed += onion
                        }
                    }
                }
            }
        }

        if (holdersKey != null && newHolders.isNotEmpty()) {
            writeOnionSet(holdersKey, holders + newHolders)
        }
        if (trackingKey != null) {
            if (stillOwed.isEmpty()) {
                storage.settings.remove(trackingKey)
            } else {
                storage.settings.setString(trackingKey, stillOwed.joinToString("\n"))
            }
        }
        if (stillOwed.isNotEmpty()) {
            DiagnosticLog.record("send", "group message still owed to ${stillOwed.size} of ${members.size} member(s); will retry")
        }
        return stillOwed.isEmpty()
    }

    /** Messages with an id can be tracked per member; control messages can't. */
    private fun groupTrackingId(wire: WireMessage): String? = when (wire) {
        is WireMessage.Text -> wire.messageId
        is WireMessage.AttachmentOffer -> wire.messageId
        else -> null
    }

    private fun pendingRecipientsKey(messageId: String) = "grouppending:$messageId"

    /** Which members and devices still owe a message: none, once it's delivered or given up on. */
    private fun forgetDeliveryTracking(messageId: String) {
        storage.settings.remove(pendingRecipientsKey(messageId))
        storage.settings.removeWithPrefix("devicepending:$messageId")
    }

    private fun keyHoldersKey(conversationId: String, distributionId: String) =
        "groupkeyholders:$conversationId:$distributionId"

    private fun readOnionSet(key: String): Set<String> =
        storage.settings.getString(key)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun writeOnionSet(key: String, onions: Set<String>) {
        storage.settings.setString(key, onions.joinToString("\n"))
    }

    private suspend fun groupWrapIfApplicable(conversation: Conversation, wire: WireMessage): WireMessage? {
        if (wire !is WireMessage.Text && wire !is WireMessage.AttachmentOffer) return null
        val state = ensureGroupSenderKey(conversation) ?: return null
        val distributionId = runCatching { UUID.fromString(state.distributionId) }.getOrNull() ?: return null
        return try {
            val innerBytes = MessagePadding.pad(WireCodec.encode(wire))
            val ciphertext = crypto.groupEncrypt(distributionId, innerBytes)
            WireMessage.GroupCiphertext(conversation.id, selfAccount(), ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureGroupSenderKey(conversation: Conversation): GroupSenderKeyState? {
        val existing = storage.groupSenderKeyState.get(conversation.id)
        if (existing != null && existing.epoch == conversation.epoch) return existing
        val distribution = try {
            crypto.createGroupDistribution()
        } catch (_: Exception) {
            return null
        }
        val state = GroupSenderKeyState(conversation.id, distribution.distributionId, conversation.epoch)
        storage.groupSenderKeyState.set(state)
        val recipients = storage.members.listForConversation(conversation.id)
            .map { it.memberOnion.value }
            .filter { it != selfAccount() }
        val distributionWire = WireMessage.SenderKeyDistribution(conversation.id, selfAccount(), distribution.messageBytes)
        recipients.forEach { runCatching { ensureSession(it); sendWire(it, distributionWire) } }
        return state
    }

    private fun groupStateHashKey(conversationId: String) = "groupstate:$conversationId"

    private fun currentGroupStateHash(conversationId: String): String? =
        storage.settings.getString(groupStateHashKey(conversationId))

    /**
     * Set once a group accepts a chained transition and never cleared. After that, unchained
     * transitions are refused. See GroupMembershipPolicy.
     */
    private fun groupChainKey(conversationId: String) = "groupchain:$conversationId"

    private fun isChainEstablished(conversationId: String): Boolean =
        storage.settings.getString(groupChainKey(conversationId)) == "1"

    private fun markChainEstablished(conversationId: String) {
        storage.settings.setString(groupChainKey(conversationId), "1")
    }

    /** Records the state now in force, after a transition has been applied. */
    private fun rememberGroupState(conversationId: String) {
        val conversation = storage.conversations.get(conversationId) ?: run {
            // Without a baseline the next transition can't be checked and the chain falls back to
            // "higher epoch wins", which is exactly the bypass it's meant to stop. This should
            // never happen, but if it does it shouldn't go unnoticed.
            DiagnosticLog.record("group", "could not record state baseline: conversation missing")
            return
        }
        val members = storage.members.listForConversation(conversationId)
        val hash = GroupMembershipPolicy.stateHash(
            conversationId = conversationId,
            epoch = conversation.epoch,
            members = members.map { it.memberOnion.value },
            admins = members.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value },
        )
        storage.settings.setString(groupStateHashKey(conversationId), hash)
    }

    private fun revokeSenderKeysFor(conversationId: String, removedOnions: List<String>) {
        removedOnions.forEach { onion ->
            // First revoke by tracked distribution id, which is precise and leaves their keys for
            // other groups alone.
            storage.groupRemoteSenderKeys.listDistributionIds(conversationId, onion).forEach { distributionId ->
                runCatching { crypto.revokeGroupSenderKey(onion, distributionId) }
            }
            storage.groupRemoteSenderKeys.deleteForConversationAndSender(conversationId, onion)

            // Then, if we no longer share any group with them, drop every sender key we hold from
            // them. The tracking table might be missing an entry, and a key that survives removal
            // would keep decrypting their messages. People removed from one group keep working in
            // the others.
            if (!storage.members.isInAnyGroupWith(onion)) {
                runCatching { crypto.revokeAllGroupSenderKeys(onion) }
            }
        }
    }

    /** Direct only, to every device of [toAccount]. True when all of them acknowledged it. */
    private suspend fun sendWire(toAccount: String, wire: WireMessage): Boolean =
        try {
            fanOut(toAccount, wire, republishKey = null, relay = false) == DeliveryOutcome.DELIVERED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun transportSend(toOnion: String, ciphertext: ByteArray, fromDevice: String = selfDevice()): Boolean =
        connections.exchange(toOnion, frameBytes(FRAME_MESSAGE, fromDevice, ciphertext)) == FRAME_ACK

    /**
     * Handles one incoming connection. [liftDeadline] is called once a connection has shown it's
     * entitled to stay open longer than the deadline every connection starts with (see
     * MessageReceiver): it's carrying an attachment with a valid token, or messages that decrypt.
     */
    suspend fun handleConnection(connection: DuplexConnection, liftDeadline: () -> Unit = {}) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(connection.input)
            val type = input.readByte().toInt()

            // Frame types 6-13 belonged to the removed peer-to-peer relay. Old builds may still try
            // them; closing the connection makes them fall back.
            if (type !in setOf(FRAME_MESSAGE, FRAME_PING, FRAME_BUNDLE_REQUEST, FRAME_ATTACHMENT, FRAME_LINK, FRAME_WIPE, CallManager.FRAME_CALL)) {
                return@withContext
            }

            val senderLen = input.readInt()
            if (senderLen !in 1..MAX_ONION_BYTES) return@withContext
            val senderBytes = ByteArray(senderLen)
            input.readFully(senderBytes)
            val sender = String(senderBytes, Charsets.UTF_8)

            when (type) {
                FRAME_ATTACHMENT -> {
                    val idLen = input.readInt()
                    if (idLen !in 1..MAX_ONION_BYTES) return@withContext
                    val idBytes = ByteArray(idLen)
                    input.readFully(idBytes)
                    val attachmentId = String(idBytes, Charsets.UTF_8)
                    val tokenLen = input.readInt()
                    if (tokenLen !in 1..64) return@withContext
                    val token = ByteArray(tokenLen)
                    input.readFully(token)
                    val blobLen = input.readLong()
                    if (blobLen < 0 || blobLen > MAX_ATTACHMENT_BYTES) return@withContext

                    attachmentsDir.mkdirs()
                    val canonicalDest = AttachmentFiles.resolve(attachmentsDir, attachmentId) ?: return@withContext

                    val pending = storage.attachments.get(attachmentId) ?: return@withContext
                    // Checked before writing a single byte, so an unauthenticated blob costs
                    // nothing and can't disturb a real transfer.
                    val expectedToken = attachmentTransferToken(pending.encKey, attachmentId)
                    if (!MessageDigest.isEqual(token, expectedToken)) {
                        DiagnosticLog.record("receive", "dropped attachment blob: transfer token did not match")
                        return@withContext
                    }
                    // Already here, from an earlier attempt whose acknowledgement got lost, or from
                    // another of the sender's devices. Saying so stops the sender resending it.
                    if (pending.state == AttachmentState.COMPLETE) {
                        writeFrame(connection.output, FRAME_ACK, selfDevice(), ByteArray(0))
                        return@withContext
                    }
                    if (pending.state != AttachmentState.PENDING) return@withContext
                    liftDeadline()

                    val expectedDigest = pending.digest
                    if (expectedDigest == null || blobLen != pending.sizeBytes) {
                        storage.attachments.updateState(attachmentId, AttachmentState.FAILED)
                        notifyChanged()
                        return@withContext
                    }
                    if (!receivingAttachments.add(attachmentId)) return@withContext
                    try {
                        // A transfer that broke off left what had arrived in the file. Only the
                        // rest is asked for, so a large file over a shaky connection gets there.
                        val have = canonicalDest.length().takeIf { canonicalDest.isFile && it <= blobLen } ?: 0L
                        if (!attachmentByteLimiter.allow(weight = blobLen - have)) return@withContext
                        DataOutputStream(connection.output).run {
                            writeByte(FRAME_ATTACHMENT_FROM)
                            writeLong(have)
                            flush()
                        }
                        java.io.FileOutputStream(canonicalDest, have > 0).use { copyExactly(input, it, blobLen - have) }

                        if (!sha256(canonicalDest).contentEquals(expectedDigest)) {
                            canonicalDest.delete()
                            // Sent whole and still wrong: the sender's copy is bad. A resumed one
                            // may just have started from a damaged partial file, so it gets
                            // another try from the beginning.
                            if (have == 0L) {
                                storage.attachments.updateState(attachmentId, AttachmentState.FAILED)
                                notifyChanged()
                            }
                            return@withContext
                        }

                        storage.attachments.updateState(attachmentId, AttachmentState.COMPLETE)
                        writeFrame(connection.output, FRAME_ACK, selfDevice(), ByteArray(0))
                        notifyChanged()
                    } finally {
                        receivingAttachments.remove(attachmentId)
                    }
                }
                FRAME_WIPE -> {
                    // Checked against the beacon, not the database, so a locked phone acts on it.
                    // Nothing is said to a sender with the wrong key.
                    val keyLen = input.readInt()
                    if (keyLen != WipeBeacon.KEY_BYTES) return@withContext
                    val key = ByteArray(keyLen).also(input::readFully)
                    if (!storage.wipeBeacon.matches(key)) return@withContext
                    DiagnosticLog.record("wipe", "another device of this account asked for a wipe")
                    writeFrame(connection.output, FRAME_ACK, selfDevice(), ByteArray(0))
                    onRemoteWipe?.invoke()
                }
                FRAME_LINK -> {
                    val invite = pendingLink ?: return@withContext
                    val payloadLen = input.readInt()
                    if (payloadLen !in 1..MAX_PAYLOAD_BYTES) return@withContext
                    val payload = ByteArray(payloadLen)
                    input.readFully(payload)
                    // Anyone can reach this address; only the device that scanned the code can
                    // produce something that opens. Anything else is ignored and the invite stays.
                    val bundle = runCatching { LinkBundle.open(invite, payload) }.getOrNull() ?: return@withContext
                    joinAccount(bundle)
                    pendingLink = null
                    writeFrame(connection.output, FRAME_ACK, selfDevice(), ByteArray(0))
                    notifyChanged()
                    _linked.tryEmit(Unit)
                }
                FRAME_MESSAGE, FRAME_PING -> serveStream(connection, input, type, sender, liftDeadline)
                // A call's audio. Only taken from the device of a call picked up here, with that
                // call's proof; held open, with the deadline lifted, until the call ends.
                CallManager.FRAME_CALL -> calls.serveMedia(sender, input, connection, liftDeadline)
                FRAME_BUNDLE_REQUEST -> {
                    val payloadLen = input.readInt()
                    if (payloadLen !in 0..MAX_PAYLOAD_BYTES) return@withContext
                    if (payloadLen > 0) input.readFully(ByteArray(payloadLen))
                    writeFrame(connection.output, FRAME_BUNDLE_RESPONSE, selfDevice(), crypto.exportLocalBundle())
                }
                else -> Unit
            }
        } catch (_: Exception) {
            // A broken or hostile frame. Closing the connection is the whole response.
        } finally {
            connection.close()
        }
    }

    /**
     * Messages and pings from one sender, one after another on one connection, until the sender
     * closes it or sends anything else (see PeerConnections). Each is acknowledged as before, and a
     * message that isn't taken ends the connection, which is how the sender learns it should try
     * again later. Once a message has decrypted, the sender is a real contact and the connection
     * may stay open past the deadline.
     */
    private suspend fun serveStream(
        connection: DuplexConnection,
        input: DataInputStream,
        firstType: Int,
        sender: String,
        liftDeadline: () -> Unit,
    ) {
        var type = firstType
        while (true) {
            val payloadLen = input.readInt()
            if (payloadLen !in 0..MAX_PAYLOAD_BYTES) return
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) input.readFully(payload)
            if (type == FRAME_MESSAGE) {
                if (payload.isEmpty()) return
                val result = intake(sender, payload, viaRelay = false)
                if (result != Intake.ACCEPTED && result != Intake.DROPPED) return
                if (result == Intake.ACCEPTED && storage.appLock.isUnlocked()) liftDeadline()
            } else if (payloadLen != 0) {
                return
            }
            writeFrame(connection.output, FRAME_ACK, selfDevice(), ByteArray(0))

            val next = input.read()
            if (next != FRAME_MESSAGE && next != FRAME_PING) return
            val nextSenderLen = input.readInt()
            if (nextSenderLen !in 1..MAX_ONION_BYTES) return
            val nextSender = String(ByteArray(nextSenderLen).also(input::readFully), Charsets.UTF_8)
            // One connection, one sender.
            if (nextSender != sender) return
            type = next
        }
    }

    private suspend fun fetchBundle(peerOnion: String): ByteArray = withContext(Dispatchers.IO) {
        transport.connect(OnionAddress.parse(peerOnion), servicePort).use { connection ->
            writeFrame(connection.output, FRAME_BUNDLE_REQUEST, selfDevice(), ByteArray(0))
            val input = DataInputStream(connection.input)
            val type = input.readByte().toInt()
            require(type == FRAME_BUNDLE_RESPONSE) { "Unexpected frame type $type" }
            val senderLen = input.readInt()
            require(senderLen in 1..MAX_ONION_BYTES) { "Bad sender length" }
            input.readFully(ByteArray(senderLen))
            val payloadLen = input.readInt()
            require(payloadLen in 1..MAX_PAYLOAD_BYTES) { "Bad bundle length" }
            val payload = ByteArray(payloadLen)
            input.readFully(payload)
            payload
        }
    }

    /** The devices [account] is on: its list, or just its own address if it never sent one. */
    private fun devicesOf(account: String): List<Device> =
        (storage.devices.list(account) ?: DeviceList.single(OnionAddress.parse(account))).devices

    /** Where a message for [account] goes. For our own account that's every device but this one. */
    private fun recipientDevices(account: String): List<Device> =
        if (account == selfAccount()) {
            storage.devices.list(account)?.devices.orEmpty().filter { it.id != crypto.localDeviceId() }
        } else {
            devicesOf(account)
        }

    /** The account and device a frame's sender address belongs to. */
    private fun ownerOf(deviceOnion: String): Pair<String, Int> =
        storage.devices.owner(deviceOnion) ?: (deviceOnion to FIRST_DEVICE)

    private fun deviceOnion(account: String, deviceId: Int): String =
        devicesOf(account).firstOrNull { it.id == deviceId }?.onion?.value ?: account

    private fun hasAnySession(account: String): Boolean =
        recipientDevices(account).any { crypto.hasSession(account, it.id) }

    /**
     * Sessions with every device of [account]. A device that can't be reached now gets one the next
     * time something is sent to it; this only fails if no device has a session at all.
     */
    private suspend fun ensureSession(account: String) {
        val devices = recipientDevices(account)
        var failure: Exception? = null
        for (device in devices) {
            try {
                ensureDeviceSession(account, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (devices.none { crypto.hasSession(account, it.id) }) failure?.let { throw it }
    }

    /**
     * One session setup per device at a time. Retries for different conversations run side by side,
     * and a contact who is also in a group would otherwise get two sessions started at once, the
     * second replacing the first.
     */
    private val sessionSetup = ConcurrentHashMap<String, Mutex>()

    private suspend fun ensureDeviceSession(account: String, device: Device) {
        if (crypto.hasSession(account, device.id)) return
        val firstSession = sessionSetup.computeIfAbsent(device.onion.value) { Mutex() }.withLock {
            if (crypto.hasSession(account, device.id)) return
            val first = !hasAnySession(account)
            val bundle = fetchBundle(device.onion.value)
            // Every device of an account shares its identity key. A device of our own has ours;
            // any other has the one pinned or already seen for the account, if there is one.
            val actual = crypto.bundleIdentityKey(bundle)
            val expected = when {
                account == selfAccount() -> crypto.localIdentityKey()
                else -> storage.settings.getString(identityKeyStoreKey(account))?.let { Base64.getDecoder().decode(it) }
                    ?: crypto.remoteIdentityKeyBytes(account)
            }
            if (expected != null) require(actual.contentEquals(expected)) { "Identity mismatch for a device of $account" }
            require(crypto.bundleDeviceId(bundle) == device.id) { "Bundle is for a different device" }
            crypto.establishOutboundSession(account, device.id, bundle)
            first
        }
        // Outside the lock: these send to the same device, which comes back through here.
        if (!firstSession || account == selfAccount() || !isChosenPeer(account)) return
        runCatching { sendProfileTo(account) }
        runCatching { maybeAnnounceNostrInbox(account) }
    }

    /** Our picture and name. Sent even without a picture, so contacts at least get the name. */
    private suspend fun sendProfileTo(peerOnion: String) {
        val picture = loadProfile(SELF_PROFILE)
        val name = runCatching { username() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (picture != null || name != null) {
            sendWire(peerOnion, WireMessage.ProfileUpdate(selfAccount(), picture, null, name))
        }
    }

    /**
     * Makes sure every contact has our current Nostr inbox details. Runs periodically because an
     * announcement only counts once it's acknowledged directly, and a contact who was offline when
     * the relays changed needs telling later.
     */
    suspend fun refreshNostrAnnouncements() = withContext(Dispatchers.IO) {
        if (nostr == null) return@withContext
        if (!storage.appLock.isUnlocked()) return@withContext
        val accounts = try {
            storage.contacts.list().map { it.onionAddress.value } + selfAccount()
        } catch (_: Exception) {
            return@withContext
        }
        for (account in accounts) {
            if (!hasAnySession(account)) continue
            runCatching { maybeAnnounceNostrInbox(account) }
        }
    }

    @Volatile private var lastAnnounceAt = 0L

    /**
     * Sends a tiny encrypted message to contacts after reconnecting, if that's turned on. Anything
     * arriving directly from this device tells their phones it's online, so they send whatever was
     * waiting for it straight away instead of on their next retry. Direct only, never through a
     * relay, looks like cover traffic, and goes at most every ten minutes.
     *
     * Off by default: it tells those contacts when this device comes online.
     */
    suspend fun announceOnline() = withContext(Dispatchers.IO) {
        if (!storage.appLock.isUnlocked()) return@withContext
        if (!runCatching { storage.settings.getBool(SettingsStore.KEY_ANNOUNCE_ONLINE, false) }.getOrDefault(false)) {
            return@withContext
        }
        val t = now()
        if (t - lastAnnounceAt < ANNOUNCE_MIN_INTERVAL_MILLIS) return@withContext
        lastAnnounceAt = t
        val contacts = runCatching { storage.contacts.list() }.getOrDefault(emptyList())
            .map { it.onionAddress.value }
            .filter { isChosenPeer(it) }
            .take(MAX_ANNOUNCE_CONTACTS)
        val permits = Semaphore(PARALLEL_GROUP_MEMBERS)
        coroutineScope {
            contacts.forEach { account ->
                launch {
                    permits.withPermit {
                        val filler = ByteArray(COVER_TRAFFIC_MIN_FILLER_BYTES + secureRandomInt(COVER_TRAFFIC_FILLER_JITTER_BYTES))
                        SecureRandom().nextBytes(filler)
                        sendWire(account, WireMessage.Dummy(filler))
                    }
                }
            }
        }
    }

    private val coverTrafficMisses = ConcurrentHashMap<String, Int>()

    suspend fun sendCoverTraffic() = withContext(Dispatchers.IO) {
        if (!storage.appLock.isUnlocked()) return@withContext
        val candidates = try {
            storage.contacts.list()
                .map { it.onionAddress.value }
                .flatMap { account -> devicesOf(account).filter { crypto.hasSession(account, it.id) }.map { account to it } }
                .filter { (coverTrafficMisses[it.second.onion.value] ?: 0) < MAX_CONSECUTIVE_COVER_MISSES }
        } catch (_: Exception) {
            return@withContext
        }
        val (account, device) = candidates.randomOrNull() ?: return@withContext
        val onion = device.onion.value

        val filler = ByteArray(COVER_TRAFFIC_MIN_FILLER_BYTES + secureRandomInt(COVER_TRAFFIC_FILLER_JITTER_BYTES))
        SecureRandom().nextBytes(filler)
        val ciphertext = try {
            crypto.encrypt(account, device.id, MessagePadding.pad(WireCodec.encode(WireMessage.Dummy(filler))))
        } catch (_: Exception) {
            return@withContext
        }
        val delivered = try { transportSend(onion, ciphertext) } catch (_: Exception) { false }
        if (delivered) {
            coverTrafficMisses.remove(onion)
        } else {
            coverTrafficMisses.merge(onion, 1, Int::plus)
        }
    }

    private fun secureRandomInt(bound: Int): Int = if (bound <= 0) 0 else SecureRandom().nextInt(bound)

    private fun writeFrame(output: OutputStream, type: Int, senderOnion: String, payload: ByteArray) {
        output.write(frameBytes(type, senderOnion, payload))
        output.flush()
    }

    private fun frameBytes(type: Int, senderOnion: String, payload: ByteArray): ByteArray {
        val sender = senderOnion.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream(1 + 4 + sender.size + 4 + payload.size).also { bytes ->
            DataOutputStream(bytes).run {
                writeByte(type)
                writeInt(sender.size)
                write(sender)
                writeInt(payload.size)
                write(payload)
            }
        }.toByteArray()
    }

    /**
     * Whether [peerOnion]'s current identity key matches the one pinned when their code was
     * scanned. True when nothing was pinned, which is normal trust-on-first-use; the check only
     * applies where the user established the right key.
     */
    private fun identityMatchesPinnedKey(peerOnion: String): Boolean {
        // No pin: trust on first use, as before.
        val pinned = storage.settings.getString(identityKeyStoreKey(peerOnion)) ?: return true

        // From here on, fail closed. A pin means the user scanned this contact's code, so a corrupt
        // pin or a missing identity is something we can't vouch for.
        val expected = runCatching { Base64.getDecoder().decode(pinned) }.getOrNull()
        if (expected == null) {
            DiagnosticLog.record("receive", "pinned identity for this contact is unreadable -- refusing")
            return false
        }
        val actual = crypto.remoteIdentityKeyBytes(peerOnion)
        if (actual == null) {
            DiagnosticLog.record("receive", "no stored identity to compare against a pin -- refusing")
            return false
        }
        return actual.contentEquals(expected)
    }

    private fun identityKeyStoreKey(onion: String): String = "identity:$onion"

    private fun finalizeDelivery(messageId: String, delivered: Boolean) {
        storage.messages.updateDeliveryState(
            messageId,
            if (delivered) DeliveryState.DELIVERED else DeliveryState.PENDING,
        )
    }

    private fun newOutgoingMessage(
        conversation: Conversation,
        type: MessageType,
        body: String,
        attachmentId: String?,
        replyToId: String? = null,
    ): Message {
        val createdAt = now()
        return Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            senderOnion = selfAccount(),
            direction = MessageDirection.OUTGOING,
            type = type,
            body = body,
            attachmentId = attachmentId,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = null,
            deliveryState = DeliveryState.PENDING,
            deleted = false,
            remoteDeletable = true,
            disappearSeconds = conversation.disappearSeconds.takeIf { it > 0 },
            replyToId = replyToId,
        )
    }

    private fun incomingMessage(conversationId: String, messageId: String, sender: String, type: MessageType, body: String, attachmentId: String?, expiresSeconds: Long): Message {
        val createdAt = now()
        return Message(
            id = messageId,
            conversationId = conversationId,
            senderOnion = sender,
            direction = MessageDirection.INCOMING,
            type = type,
            body = body,
            attachmentId = attachmentId,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = null,
            deliveryState = DeliveryState.RECEIVED,
            deleted = false,
            remoteDeletable = true,
            disappearSeconds = clampDisappearSeconds(expiresSeconds).takeIf { it > 0 },
        )
    }

    private fun resolveIncomingConversationId(wireConversationId: String, fromOnion: String): String {
        val existing = storage.conversations.get(wireConversationId)
        return if (existing != null && existing.type == ConversationType.GROUP) wireConversationId else fromOnion
    }

    /**
     * Membership check for content that may name a group but can also be the first message of a new
     * direct chat.
     *
     * Deliberately looser than [isAuthorizedForConversation], which rejects unknown conversations.
     * A first message from a new contact names a conversation we've never seen, and rejecting it
     * would mean nobody could ever reach us. So: if the named conversation exists and is a group,
     * the sender must be a member. Everything else goes through the normal direct-chat handling,
     * where resolveIncomingConversationId has already pinned the id to the sender's onion.
     */
    private fun isAuthorizedForGroupIfGroup(conversationId: String, fromOnion: String): Boolean {
        val conversation = storage.conversations.get(conversationId) ?: return true
        if (conversation.type != ConversationType.GROUP) return true
        return storage.members.isMember(conversationId, fromOnion)
    }

    private fun isAuthorizedForConversation(conversationId: String, fromOnion: String): Boolean {
        val conversation = storage.conversations.get(conversationId)
        val isGroup = conversation?.type == ConversationType.GROUP
        return GroupMembershipPolicy.isAuthorizedActor(
            conversationExists = conversation != null,
            isGroup = isGroup,
            conversationId = conversationId,
            actor = fromOnion,
            isCurrentMember = isGroup && storage.members.isMember(conversationId, fromOnion),
        )
    }

    private fun ensureDirectConversationIfMissing(conversationId: String, fromOnion: String) {
        if (conversationId == fromOnion && storage.conversations.get(conversationId) == null) {
            val isStranger = !storage.contacts.isKnown(fromOnion)
            storage.conversations.upsert(
                Conversation(fromOnion, ConversationType.DIRECT, fromOnion, 0, now(), pending = isStranger),
            )

            // Add them as a contact as well as opening the conversation, so the side that didn't
            // scan the code can still get a Nostr mailbox once the request is accepted. UNVERIFIED,
            // because nothing here proves who they are.
            if (isStranger) {
                storage.contacts.upsert(
                    Contact(
                        onionAddress = OnionAddress.parse(fromOnion),
                        displayName = fromOnion.take(8),
                        fingerprint = IdentityFingerprint(""),
                        trustState = TrustState.UNVERIFIED,
                        addedAtEpochMillis = now(),
                    ),
                )
            }
        }
    }

    private fun requireConversation(conversationId: String): Conversation =
        storage.conversations.get(conversationId)
            ?: throw IllegalArgumentException("No conversation $conversationId")

    private fun cleanupAttachment(attachmentId: String) {
        storage.deleteAttachmentFile(attachmentId)
        storage.attachments.delete(attachmentId)
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /**
     * Local time for records this device creates. Monotonic, so winding the clock back can't pull
     * expiry deadlines into the past or sort new messages before old ones. Timestamps from other
     * devices never go through this.
     */
    private fun now(): Long = storage.clock.now()

    companion object {
        /** Above this many tracked senders, idle limiters are cleared out. */
        private const val LIMITER_SWEEP_THRESHOLD = 256
        private const val SELF = "self"
        private const val FIRST_DEVICE = 1
        private const val SELF_PROFILE = "self"
        private const val READ_RECEIPT_STATE = "READ"

        /**
         * How often the retry loop runs. Must match ConnectivityService.RETRY_INTERVAL_MILLIS,
         * because shouldAttemptNow derives its backoff slots from it.
         */
        private const val RETRY_BASE_INTERVAL_MILLIS = 20_000L

        /** Our devices still owed an attachment's file, by attachment id; see retryOwnDeviceFiles. */
        private const val OWN_FILE_TRACKING = "ownfile:"

        /** Tracks which of our devices have the current wipe key; see deliverWipeKey. */
        private const val WIPE_KEY_TRACKING = "wipekey:"

        /** Contacts still owed our answer on calls, by address; see announceCallPermission. */
        private const val CALL_PERMISSION_OWED = "callpermowed:"

        /** A large group isn't kept warm beyond this many members; the rest connect on send. */
        private const val MAX_WARM_GROUP_MEMBERS = 8

        /** Group members sent to at the same time. */
        private const val PARALLEL_GROUP_MEMBERS = 6

        /** How long to wait for a direct acknowledgement before sending a copy to the relay too. */
        private const val RELAY_HEDGE_MILLIS = 5_000L

        /** How far back an urgent retry pass reaches. */
        private const val URGENT_WINDOW_MILLIS = 60L * 60 * 1000

        /** At most one immediate flush per contact this often, however much they send. */
        private const val CONTACT_FLUSH_INTERVAL_MILLIS = 15_000L
        private const val ANNOUNCE_MIN_INTERVAL_MILLIS = 10L * 60 * 1000
        private const val MAX_ANNOUNCE_CONTACTS = 25

        /** See startNewSession. */
        private const val SESSION_RESTART_INTERVAL_MILLIS = 10L * 60 * 1000

        /** Conversations retried at the same time. Each one is a Tor circuit or two. */
        private const val PARALLEL_RETRY_CONVERSATIONS = 4

        /** See hasFreshUnsent. */
        private const val FRESH_UNSENT_MILLIS = 10L * 60 * 1000

        /**
         * How long an undelivered message is retried before it's marked FAILED. Being away for a
         * week is normal, and retrying is cheap, but something that can never be delivered
         * shouldn't show as pending forever.
         */
        private const val MAX_PENDING_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** See sendWireDirectOrRelay. Comfortably inside a Nostr event's 14-day lifetime. */
        private const val NOSTR_REPUBLISH_INTERVAL_MILLIS = 12L * 60 * 60 * 1000

        /**
         * A peer that didn't take an announcement (an older build, say) isn't asked again before
         * this.
         */
        private const val NOSTR_ANNOUNCE_RETRY_MILLIS = 60L * 60 * 1000
        private const val MAX_TRACKED_REPUBLISHES = 4_096
        private const val FRAME_MESSAGE = 1
        private const val FRAME_BUNDLE_REQUEST = 2
        private const val FRAME_BUNDLE_RESPONSE = 3
        private const val FRAME_ACK = 4
        /**
         * 5 was the attachment frame that sent the whole file at once. The one that replaced it
         * waits for the recipient to say how much it already has ([FRAME_ATTACHMENT_FROM]).
         */
        private const val FRAME_ATTACHMENT = 16
        private const val FRAME_ATTACHMENT_FROM = 17

        /** 6 to 13 were the removed relay's; old builds may still send them. */
        private const val FRAME_LINK = 14
        private const val FRAME_WIPE = 15

        /** Keeps an open connection alive; answered with an ACK. See PeerConnections. */
        private const val FRAME_PING = 18
        private const val WIPE_RETRY_MILLIS = 30_000L

        /** SHA-256. */
        private const val ATTACHMENT_DIGEST_BYTES = 32

        /** Key size of the streaming AEAD that AttachmentCipher generates. */
        private const val ATTACHMENT_KEY_BYTES = 32

        private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
        private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9.+-]{0,40}/[a-z0-9][a-z0-9.+-]{0,80}$", RegexOption.IGNORE_CASE)
        private const val MAX_ONION_BYTES = 256

        /** How long a message received while locked is kept waiting for a check that can't run yet. */
        private const val MAX_QUEUED_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** New senders whose key has been looked up but whose message hasn't come back yet. */
        private const val MAX_PENDING_FIRST_CONTACTS = 256

        /**
         * Display names come from peers and appear in lists and notifications, so they're capped.
         */
        private const val MAX_DISPLAY_NAME_CHARS = 40

        /** Well above any real typing rate, but still limits what a hostile peer can pile up. */
        private const val MAX_MESSAGES_PER_SENDER_PER_MINUTE = 120L
        private const val MAX_BYTES_PER_SENDER_PER_MINUTE = 16L * 1024 * 1024
        private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

        private const val COVER_TRAFFIC_MIN_FILLER_BYTES = 16
        private const val COVER_TRAFFIC_FILLER_JITTER_BYTES = 184
        private const val MAX_CONSECUTIVE_COVER_MISSES = 50
    }
}

private enum class DeliveryOutcome { DELIVERED, RELAYED, FAILED }

/** What happened to an incoming envelope. ACCEPTED and DROPPED are acknowledged; the rest aren't. */
private enum class Intake { ACCEPTED, DROPPED, LATER, FAILED }
