package app.niix.core.messaging

import app.niix.core.crypto.CryptoEngine
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
import app.niix.core.model.Contact
import app.niix.core.model.Conversation
import app.niix.core.model.ConversationType
import app.niix.core.model.DeliveryState
import app.niix.core.model.GroupMember
import app.niix.core.model.GroupRole
import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.model.OnionAddress
import app.niix.core.model.TrustState
import app.niix.core.storage.PendingGroupInvite
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.DuplexConnection
import app.niix.core.transport.TorTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

data class IncomingNotice(val conversationId: String, val title: String, val preview: String)

class ConversationManager(
    private val storage: SecureStorage,
    private val crypto: CryptoEngine,
    private val transport: TorTransport,
    private val attachmentsDir: File,
    private val servicePort: Int,
    private val selfOnionProvider: () -> String?,
) {

    private fun selfOnion(): String = selfOnionProvider() ?: SELF

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    private val _incoming = MutableSharedFlow<IncomingNotice>(extraBufferCapacity = 32)
    val incoming: SharedFlow<IncomingNotice> = _incoming.asSharedFlow()

    // Caps total attachment bytes accepted from unauthenticated (pre-session) connections per
    // rolling minute -- see the FRAME_ATTACHMENT branch of handleConnection(). 200MB/minute
    // comfortably covers legitimate use (a couple of near-max-size attachments) while bounding
    // how much disk a flood of forged-but-otherwise-valid attachment frames can burn through.
    private val attachmentByteLimiter = SlidingWindowLimiter(maxWeight = 200L * 1024 * 1024, windowMillis = 60_000)

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

    /** Every saved contact, including ones whose conversation was deleted -- deleting a
     * conversation never removes the contact itself (see [deleteConversation]). */
    suspend fun listContacts(): List<Contact> = withContext(Dispatchers.IO) {
        storage.contacts.list()
    }

    /** Conversations started by a stranger messaging you first (e.g. after scanning your QR
     * code) that you haven't accepted or blocked yet. */
    suspend fun pendingRequests(): List<Conversation> = withContext(Dispatchers.IO) {
        storage.conversations.list().filter { it.pending }
    }

    /** Accepts a message request: saves the sender as a contact and turns their conversation
     * into a normal one. */
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
        notifyChanged()
        true
    }

    /** Blocks a message request's sender and removes the pending conversation and its messages
     * from this device -- the same as [deleteConversation] plus adding them to the blocklist. */
    suspend fun blockRequest(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (!conversation.pending) return@withContext false
        storage.blocklist.block(conversationId)
        storage.attachments.listForConversation(conversationId).forEach { runCatching { File(it.filePath).delete() } }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        storage.conversations.delete(conversationId)
        notifyChanged()
        true
    }

    suspend fun messagesFor(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        storage.messages.listForConversation(conversationId)
    }

    /** Group invites from a conversationId we've never seen before, held pending explicit
     * accept/block -- see the GroupInvite branch of [dispatch]. */
    suspend fun pendingGroupInvites(): List<PendingGroupInvite> = withContext(Dispatchers.IO) {
        storage.pendingGroupInvites.list()
    }

    /** Accepts a pending group invite: only now does the group and its membership list get
     * written locally. Returns false if there's no such pending invite. */
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
        storage.pendingGroupInvites.delete(conversationId)
        notifyChanged()
        true
    }

    /** Rejects a pending group invite: discards it without ever having written any membership
     * data (see the GroupInvite branch of [dispatch] -- none was written to begin with). */
    suspend fun rejectGroupInvite(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val existed = storage.pendingGroupInvites.get(conversationId) != null
        storage.pendingGroupInvites.delete(conversationId)
        if (existed) notifyChanged()
        existed
    }

    suspend fun lastMessage(conversationId: String): Message? = withContext(Dispatchers.IO) {
        storage.messages.listForConversation(conversationId).lastOrNull()
    }

    /**
     * Call when a conversation is actually opened/viewed. For any incoming disappearing message
     * that hasn't started its countdown yet, this starts it now (on this device) and sends a
     * read receipt back to whoever sent it, so their copy starts counting down too -- see the
     * [newOutgoingMessage] / [incomingMessage] doc comments for why the countdown doesn't start
     * any earlier than this.
     */
    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        val unread = storage.messages.listForConversation(conversationId).filter {
            it.direction == MessageDirection.INCOMING && it.disappearSeconds != null && it.expiresAtEpochMillis == null
        }
        if (unread.isEmpty()) return@withContext
        val nowMs = now()
        for (message in unread) {
            storage.messages.startExpiry(message.id, message.disappearSeconds!!, nowMs)
            runCatching {
                ensureSession(message.senderOnion)
                sendWire(message.senderOnion, WireMessage.Receipt(message.conversationId, message.id, READ_RECEIPT_STATE))
            }
        }
        notifyChanged()
    }

    suspend fun username(): String = withContext(Dispatchers.IO) {
        storage.settings.getString(SettingsStore.KEY_USERNAME).orEmpty().ifEmpty { "Me" }
    }

    /**
     * Re-attempts delivery of every undelivered outgoing message. Called on a timer while the
     * service runs, so a message sent to an offline/locked peer is not lost — it stays PENDING
     * and is delivered (and acknowledged) as soon as the peer is reachable and unlocked.
     */
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        if (!storage.appLock.isUnlocked()) return@withContext
        val pending = try {
            storage.messages.pendingOutgoing()
        } catch (_: Exception) {
            return@withContext
        }
        val nowMs = now()
        for (msg in pending) {
            if (msg.deleted) continue
            val conversation = storage.conversations.get(msg.conversationId) ?: continue

            // Give up after a while rather than retrying (and staying silently PENDING)
            // forever -- surfaces as a distinct FAILED state the person can actually see.
            if (nowMs - msg.createdAtEpochMillis > MAX_PENDING_AGE_MILLIS) {
                storage.messages.updateDeliveryState(msg.id, DeliveryState.FAILED)
                notifyChanged()
                continue
            }

            val delivered = try {
                when (msg.type) {
                    MessageType.TEXT -> {
                        val wire = WireMessage.Text(conversation.id, msg.id, selfOnion(), msg.body, conversation.disappearSeconds)
                        if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
                        deliverToConversation(conversation, wire)
                    }
                    MessageType.ATTACHMENT -> retryAttachment(conversation, msg)
                    MessageType.SYSTEM -> false
                }
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                storage.messages.updateDeliveryState(msg.id, DeliveryState.DELIVERED)
                notifyChanged()
            }
        }
    }

    /**
     * Retries a PENDING attachment message: re-announces it via AttachmentOffer, then re-pushes
     * the raw encrypted blob via [pushAttachmentBlob] -- for a group, to every member who hasn't
     * gotten it, same as a first send now does.
     */
    private suspend fun retryAttachment(conversation: Conversation, msg: Message): Boolean {
        val attachmentId = msg.attachmentId ?: return false
        val attachment = storage.attachments.get(attachmentId) ?: return false
        val wire = WireMessage.AttachmentOffer(
            conversation.id, msg.id, selfOnion(), attachmentId, attachment.mimeType,
            attachment.sizeBytes, attachment.encKey, attachment.digest, conversation.disappearSeconds,
        )
        if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
        val offered = deliverToConversation(conversation, wire)
        if (!offered) return false
        val encFile = File(attachment.filePath)
        if (!encFile.isFile) return false
        return when (conversation.type) {
            ConversationType.DIRECT -> runCatching { sendAttachmentBlob(conversation.id, attachmentId, encFile) }.getOrDefault(false)
            ConversationType.GROUP -> {
                val recipients = storage.members.listForConversation(conversation.id)
                    .map { it.memberOnion.value }
                    .filter { it != selfOnion() }
                var anySucceeded = false
                recipients.forEach { onion ->
                    runCatching { if (sendAttachmentBlob(onion, attachmentId, encFile)) anySucceeded = true }
                }
                anySucceeded
            }
        }
    }

    suspend fun addDirectContact(peerOnion: String, peerBundle: ByteArray, title: String): Conversation =
        withContext(Dispatchers.IO) {
            crypto.establishOutboundSession(peerOnion, peerBundle)
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
                    onionAddress = OnionAddress.parse(peerOnion),
                    displayName = title,
                    fingerprint = crypto.remoteFingerprint(peerOnion) ?: IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = now(),
                ),
            )
            notifyChanged()
            conversation
        }

    /**
     * Adds a contact from a short share code (onion address + identity key). No key bundle is
     * exchanged here; the peer's prekeys are fetched over Tor on the first message, and the
     * identity key captured now is used then to verify we reached the right person.
     */
    suspend fun addContactByCode(peerOnion: String, identityKey: ByteArray, title: String): Conversation =
        withContext(Dispatchers.IO) {
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
                    onionAddress = OnionAddress.parse(peerOnion),
                    displayName = title,
                    fingerprint = IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = now(),
                ),
            )
            notifyChanged()
            conversation
        }

    /** Conversation id -> most recent matching message body, for showing the matched line in search results. */
    suspend fun searchMessageMatches(query: String): Map<String, String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyMap() else runCatching { storage.messages.searchMatchingBodies(query) }.getOrDefault(emptyMap())
    }

    suspend fun renameContact(conversationId: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext
        storage.conversations.get(conversationId)?.let {
            storage.conversations.upsert(it.copy(title = trimmed))
        }
        storage.contacts.get(conversationId)?.let {
            storage.contacts.upsert(it.copy(displayName = trimmed))
        }
        notifyChanged()
    }

    suspend fun block(onion: String) = withContext(Dispatchers.IO) { storage.blocklist.block(onion) }

    suspend fun unblock(onion: String) = withContext(Dispatchers.IO) { storage.blocklist.unblock(onion) }

    suspend fun setAllowlistOnly(enabled: Boolean) = withContext(Dispatchers.IO) {
        storage.settings.setBool(SettingsStore.KEY_ALLOWLIST_ONLY, enabled)
    }

    suspend fun isAllowlistOnly(): Boolean = withContext(Dispatchers.IO) {
        storage.settings.getBool(SettingsStore.KEY_ALLOWLIST_ONLY, false)
    }

    suspend fun markVerified(onion: String) = withContext(Dispatchers.IO) {
        storage.contacts.setTrustState(onion, TrustState.VERIFIED)
    }

    suspend fun getContact(onion: String): Contact? = withContext(Dispatchers.IO) {
        storage.contacts.get(onion)
    }

    suspend fun safetyNumber(onion: String): String? = withContext(Dispatchers.IO) {
        crypto.safetyNumber(onion, selfOnion())
    }

    /** Null if [onion] isn't a saved contact yet (e.g. a still-pending message request -- which
     * is, by definition, always unverified: TOFU just happened and no safety-number check has
     * occurred). Used to surface an "unverified identity" indicator in the UI -- see Item 6:
     * TOFU itself isn't a bug, but the app wasn't telling the person when they were relying on
     * it versus an explicitly confirmed identity. */
    suspend fun trustState(onion: String): TrustState? = withContext(Dispatchers.IO) {
        storage.contacts.get(onion)?.trustState
    }

    /**
     * Saves a group member as a direct contact, so they show up in the left-edge contacts
     * drawer and a private chat can be started with them outside the group. Returns false if
     * they're already a contact, or if [onion] is your own address.
     */
    suspend fun addGroupMemberToContacts(onion: String, displayName: String): Boolean = withContext(Dispatchers.IO) {
        if (onion == selfOnion()) return@withContext false
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
            val self = selfOnion()
            val allMembers = (memberOnions + self).distinct()
            val admins = listOf(self)
            storage.members.replaceAll(conversationId, allMembers, admins)
            val invite = WireMessage.GroupInvite(conversationId, title, allMembers, admins, epoch = 1)
            memberOnions.forEach { runCatching { ensureSession(it); sendWire(it, invite) } }
            conversation
        }

    /**
     * Populates the (freshly wiped and re-created) database with plausible-looking, entirely
     * fake conversations and backdated messages. Used only after a duress wipe, so the app opens
     * into something that looks like an ordinary, lived-in messenger rather than an empty one.
     * None of this content is reachable or real — the onion addresses are random strings that
     * happen to match the v3 format, not actual contacts.
     */
    suspend fun seedDecoyContent() = withContext(Dispatchers.IO) {
        val rng = java.security.SecureRandom()
        val now = now()
        val names = listOf("Alex", "Sam", "Jordan", "Mum", "Dad", "Chris", "Taylor", "Jamie", "Robin", "Morgan")
        val templates = listOf(
            "Hey, are we still on for Saturday?",
            "Thanks for the recipe, it turned out great!",
            "Can you send me that photo from the trip?",
            "Running a bit late, be there in 10.",
            "Happy birthday! Hope you have a great day.",
            "Did you finish watching that show yet?",
            "Let's grab coffee sometime this week.",
            "Thanks again for helping me move.",
            "Sounds good, see you then.",
            "No worries, talk soon.",
            "Can you pick up milk on your way home?",
            "That's hilarious.",
            "Sure, I'll check and let you know.",
            "Sorry for the late reply, been busy.",
            "Sounds like a plan.",
            "Call me when you get a chance?",
        )
        val self = selfOnion()
        val chosen = names.shuffled(kotlin.random.Random(rng.nextLong())).take(4 + rng.nextInt(3))
        for (name in chosen) {
            val onion = randomOnion(rng)
            val createdAt = now - java.util.concurrent.TimeUnit.DAYS.toMillis((20L + rng.nextInt(150)))
            storage.contacts.upsert(
                Contact(
                    onionAddress = OnionAddress.parse(onion),
                    displayName = name,
                    fingerprint = IdentityFingerprint(""),
                    trustState = TrustState.UNVERIFIED,
                    addedAtEpochMillis = createdAt,
                ),
            )
            storage.conversations.upsert(Conversation(onion, ConversationType.DIRECT, name, 0, createdAt))
            var t = createdAt
            val count = 3 + rng.nextInt(6)
            repeat(count) {
                t += java.util.concurrent.TimeUnit.HOURS.toMillis((4L + rng.nextInt(90)))
                if (t > now) t = now - java.util.concurrent.TimeUnit.MINUTES.toMillis(rng.nextInt(500).toLong())
                val outgoing = rng.nextBoolean()
                storage.messages.insert(
                    Message(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = onion,
                        senderOnion = if (outgoing) self else onion,
                        direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        type = MessageType.TEXT,
                        body = templates[rng.nextInt(templates.size)],
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

    private fun randomOnion(rng: java.security.SecureRandom): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder(62)
        repeat(56) { sb.append(alphabet[rng.nextInt(alphabet.length)]) }
        sb.append(OnionAddress.SUFFIX)
        return sb.toString()
    }

    /**
     * Deletes a conversation and its local messages/attachments only. The underlying contact
     * (and, for a direct chat, the already-established encrypted session) is left completely
     * untouched -- they stay in your contacts and you can message them again without
     * re-adding them or re-exchanging keys.
     */
    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext
        storage.attachments.listForConversation(conversationId).forEach { attachment ->
            runCatching { File(attachment.filePath).delete() }
        }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        if (conversation.type == ConversationType.GROUP) {
            storage.members.replaceAll(conversationId, emptyList(), emptyList())
        }
        storage.conversations.delete(conversationId)
        notifyChanged()
    }

    /**
     * Returns the existing conversation with [onion], or creates a fresh (empty) one if it was
     * previously removed via [deleteConversation]. The contact record and its established
     * session survive that deletion, so this never re-adds the contact or re-exchanges keys --
     * it just gives the person a conversation to open again.
     */
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
        withContext(Dispatchers.IO) { storage.members.isAdmin(conversationId, selfOnion()) }

    suspend fun addGroupMembers(conversationId: String, newOnions: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfOnion())) return@withContext false
            newOnions.forEach {
                storage.members.add(GroupMember(conversationId, OnionAddress.parse(it), GroupRole.MEMBER))
            }
            syncGroup(conversationId)
            true
        }

    suspend fun removeGroupMember(conversationId: String, onion: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfOnion())) return@withContext false
            if (onion == selfOnion()) return@withContext false
            storage.members.remove(conversationId, OnionAddress.parse(onion))
            syncGroup(conversationId)
            true
        }

    suspend fun promoteGroupMember(conversationId: String, onion: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfOnion())) return@withContext false
            storage.members.setRole(conversationId, onion, GroupRole.ADMIN)
            syncGroup(conversationId)
            true
        }

    /**
     * Leaves a group: tells the remaining members you're gone, then removes the group and its
     * messages from this device only (the remaining members' copies are untouched). If you were
     * the only admin, another member is promoted first so the group isn't left without one.
     */
    suspend fun leaveGroup(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (conversation.type != ConversationType.GROUP) return@withContext false
        val self = selfOnion()
        val members = storage.members.listForConversation(conversationId)
        val remaining = members.filter { it.memberOnion.value != self }
        val iWasSoleAdmin = members.any { it.memberOnion.value == self && it.role == GroupRole.ADMIN } &&
            remaining.none { it.role == GroupRole.ADMIN }
        if (iWasSoleAdmin && remaining.isNotEmpty()) {
            storage.members.setRole(conversationId, remaining.first().memberOnion.value, GroupRole.ADMIN)
        }
        val remainingAfterPromotion = storage.members.listForConversation(conversationId)
            .filter { it.memberOnion.value != self }
        val allOnions = remainingAfterPromotion.map { it.memberOnion.value }
        val admins = remainingAfterPromotion.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value }
        val invite = WireMessage.GroupInvite(conversationId, conversation.title, allOnions, admins, epoch = conversation.epoch + 1)
        remaining.forEach { runCatching { ensureSession(it.memberOnion.value); sendWire(it.memberOnion.value, invite) } }
        storage.attachments.listForConversation(conversationId).forEach { runCatching { File(it.filePath).delete() } }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        storage.members.replaceAll(conversationId, emptyList(), emptyList())
        storage.conversations.delete(conversationId)
        notifyChanged()
        true
    }

    private suspend fun syncGroup(conversationId: String) {
        val conversation = storage.conversations.get(conversationId) ?: return
        val members = storage.members.listForConversation(conversationId)
        val allOnions = members.map { it.memberOnion.value }
        val admins = members.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value }
        val nextEpoch = conversation.epoch + 1
        storage.conversations.setEpoch(conversationId, nextEpoch)
        val invite = WireMessage.GroupInvite(conversationId, conversation.title, allOnions, admins, epoch = nextEpoch)
        allOnions.filter { it != selfOnion() }.forEach { runCatching { ensureSession(it); sendWire(it, invite) } }
        notifyChanged()
    }

    suspend fun sendText(conversationId: String, body: String): Message = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        val message = newOutgoingMessage(conversation, MessageType.TEXT, body, attachmentId = null)
        storage.messages.insert(message)
        val wire = WireMessage.Text(conversationId, message.id, selfOnion(), body, conversation.disappearSeconds)
        val delivered = try {
            if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
            deliverToConversation(conversation, wire)
        } catch (_: Exception) {
            false
        }
        finalizeDelivery(message.id, delivered)
        notifyChanged()
        message
    }

    suspend fun sendAttachment(conversationId: String, source: File, mimeType: String): Message =
        withContext(Dispatchers.IO) {
            val conversation = requireConversation(conversationId)
            val attachmentId = UUID.randomUUID().toString()
            val key = storage.attachmentCipher.newKey()
            attachmentsDir.mkdirs()
            val encryptedFile = File(attachmentsDir, "$attachmentId.enc")
            storage.attachmentCipher.encryptFile(key, source, encryptedFile)
            val digest = sha256(encryptedFile)
            val attachment = Attachment(
                id = attachmentId,
                conversationId = conversationId,
                filePath = encryptedFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = source.length(),
                encKey = key,
                digest = digest,
                state = AttachmentState.COMPLETE,
                createdAtEpochMillis = now(),
            )
            storage.attachments.insert(attachment)
            val message = newOutgoingMessage(conversation, MessageType.ATTACHMENT, "", attachmentId)
            storage.messages.insert(message)
            val wire = WireMessage.AttachmentOffer(
                conversationId, message.id, selfOnion(), attachmentId, mimeType,
                source.length(), key, digest, conversation.disappearSeconds,
            )
            val delivered = try {
                if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
                deliverToConversation(conversation, wire)
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                runCatching { pushAttachmentBlob(conversation, attachmentId, encryptedFile) }
            }
            finalizeDelivery(message.id, delivered)
            message
        }

    /**
     * Pushes an attachment's raw encrypted bytes to whoever needs them: the one peer for a
     * DIRECT chat, or every other member for a GROUP. Previously this only ever ran for DIRECT
     * conversations -- a group attachment's metadata offer went out to every member, but the
     * actual file bytes never did, so group attachments could never actually be downloaded.
     * Fan-out here is best-effort per member, same as deliverToConversation()'s existing
     * semantics for a group text message: one member's connection being unreachable doesn't
     * block the others from getting the file.
     */
    private suspend fun pushAttachmentBlob(conversation: Conversation, attachmentId: String, encryptedFile: File) {
        when (conversation.type) {
            ConversationType.DIRECT -> {
                sendAttachmentBlob(conversation.id, attachmentId, encryptedFile)
                Unit
            }
            ConversationType.GROUP -> {
                val recipients = storage.members.listForConversation(conversation.id)
                    .map { it.memberOnion.value }
                    .filter { it != selfOnion() }
                recipients.forEach { onion -> runCatching { sendAttachmentBlob(onion, attachmentId, encryptedFile) } }
                Unit
            }
        }
    }

    private suspend fun sendAttachmentBlob(toOnion: String, attachmentId: String, encFile: File): Boolean =
        transport.connect(OnionAddress.parse(toOnion), servicePort).use { connection ->
            val out = DataOutputStream(connection.output)
            out.writeByte(FRAME_ATTACHMENT)
            val sender = selfOnion().toByteArray(Charsets.UTF_8)
            out.writeInt(sender.size); out.write(sender)
            val idBytes = attachmentId.toByteArray(Charsets.UTF_8)
            out.writeInt(idBytes.size); out.write(idBytes)
            out.writeLong(encFile.length())
            encFile.inputStream().use { it.copyTo(out) }
            out.flush()
            try {
                DataInputStream(connection.input).readByte().toInt() == FRAME_ACK
            } catch (_: Exception) {
                false
            }
        }

    fun attachment(attachmentId: String): Attachment? = storage.attachments.get(attachmentId)

    suspend fun attachmentBytes(attachmentId: String): ByteArray? = withContext(Dispatchers.IO) {
        val att = storage.attachments.get(attachmentId) ?: return@withContext null
        val encFile = File(att.filePath)
        if (!encFile.isFile) return@withContext null
        val out = ByteArrayOutputStream()
        runCatching { storage.attachmentCipher.decrypt(att.encKey, encFile.inputStream(), out) }
            .getOrElse { return@withContext null }
        out.toByteArray()
    }

    suspend fun decryptAttachmentTo(attachmentId: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val att = storage.attachments.get(attachmentId) ?: return@withContext false
        val encFile = File(att.filePath)
        if (!encFile.isFile) return@withContext false
        runCatching {
            encFile.inputStream().use { input ->
                dest.outputStream().use { o -> storage.attachmentCipher.decrypt(att.encKey, input, o) }
            }
        }.isSuccess
    }

    // ---- Profile photos -------------------------------------------------------------------

    private fun profilesDir(): File = File(attachmentsDir.parentFile ?: attachmentsDir, "niix-profiles")

    private fun profileKey(): ByteArray {
        val existing = storage.settings.getString(SettingsStore.KEY_PROFILE_KEY)
        if (existing != null) return Base64.getDecoder().decode(existing)
        val key = storage.attachmentCipher.newKey()
        storage.settings.setString(SettingsStore.KEY_PROFILE_KEY, Base64.getEncoder().encodeToString(key))
        return key
    }

    private fun storeProfile(key: String, bytes: ByteArray?) {
        val dir = profilesDir()
        dir.mkdirs()
        val file = File(dir, "$key.enc")
        if (bytes == null) { file.delete(); return }
        file.outputStream().use { out ->
            storage.attachmentCipher.encrypt(profileKey(), ByteArrayInputStream(bytes), out)
        }
    }

    private fun loadProfile(key: String): ByteArray? {
        val file = File(profilesDir(), "$key.enc")
        if (!file.isFile) return null
        return runCatching {
            val out = ByteArrayOutputStream()
            file.inputStream().use { input -> storage.attachmentCipher.decrypt(profileKey(), input, out) }
            out.toByteArray()
        }.getOrNull()
    }

    suspend fun profileBytes(key: String): ByteArray? = withContext(Dispatchers.IO) { loadProfile(key) }

    suspend fun setSelfProfile(bytes: ByteArray?) = withContext(Dispatchers.IO) {
        storeProfile("self", bytes)
        notifyChanged()
        for (conversation in storage.conversations.list().filter { it.type == ConversationType.DIRECT && !it.pending }) {
            runCatching {
                ensureSession(conversation.id)
                sendWire(conversation.id, WireMessage.ProfileUpdate(selfOnion(), bytes))
            }
        }
    }

    /** Sets (or clears, with `bytes = null`) a group's shared photo and pushes it to every other
     * member. Admin-only -- returns false for anyone else, or if [conversationId] isn't a group. */
    suspend fun setGroupProfile(conversationId: String, bytes: ByteArray?): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (conversation.type != ConversationType.GROUP) return@withContext false
        if (!storage.members.isAdmin(conversationId, selfOnion())) return@withContext false
        storeProfile(conversationId, bytes)
        notifyChanged()
        val recipients = storage.members.listForConversation(conversationId)
            .map { it.memberOnion.value }
            .filter { it != selfOnion() }
        recipients.forEach { runCatching { ensureSession(it); sendWire(it, WireMessage.ProfileUpdate(selfOnion(), bytes, conversationId)) } }
        true
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        out.flush()
    }

    suspend fun setDisappearTimer(conversationId: String, seconds: Long) = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        storage.conversations.setDisappearSeconds(conversationId, seconds)
        deliverToConversation(conversation, WireMessage.TimerUpdate(conversationId, seconds))
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
        if (existing != null && !existing.remoteDeletable) return@withContext
        storage.messages.markDeletedForEveryone(messageId)
        existing?.attachmentId?.let { cleanupAttachment(it) }
        deliverToConversation(conversation, WireMessage.DeleteForEveryone(conversationId, listOf(messageId)))
        Unit
    }

    suspend fun handleIncoming(fromOnion: String, ciphertext: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // Acknowledge (and drop) blocked/allowlisted peers so the sender stops retrying.
        if (storage.blocklist.isBlocked(fromOnion)) return@withContext true
        if (storage.settings.getBool(SettingsStore.KEY_ALLOWLIST_ONLY, false) && !isKnownPeer(fromOnion)) {
            return@withContext true
        }
        return@withContext try {
            val plaintext = crypto.decrypt(fromOnion, ciphertext)
            dispatch(fromOnion, WireCodec.decode(MessagePadding.unpad(plaintext)))
            notifyChanged()
            true
        } catch (_: Exception) {
            // Locked database or undecryptable ciphertext: don't ack, let the sender retry.
            false
        }
    }

    private fun isKnownPeer(onion: String): Boolean =
        storage.contacts.isKnown(onion) ||
            storage.conversations.get(onion) != null ||
            crypto.hasRemoteIdentity(onion)

    private fun dispatch(fromOnion: String, wire: WireMessage) {
        when (wire) {
            is WireMessage.Text -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                val isNew = storage.messages.get(wire.messageId) == null
                ensureDirectConversationIfMissing(convId, fromOnion)
                storage.messages.insert(incomingMessage(convId, wire.messageId, fromOnion, MessageType.TEXT, wire.body, null, wire.expiresSeconds))
                if (isNew) emitIncoming(convId, fromOnion, wire.body)
            }
            is WireMessage.AttachmentOffer -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                ensureDirectConversationIfMissing(convId, fromOnion)
                storage.attachments.insert(
                    Attachment(
                        id = wire.attachmentId,
                        conversationId = convId,
                        filePath = File(attachmentsDir, "${wire.attachmentId}.enc").absolutePath,
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
            is WireMessage.DeleteForEveryone ->
                wire.targetMessageIds.forEach { storage.messages.markDeletedForEveryone(it) }
            is WireMessage.TimerUpdate ->
                storage.conversations.setDisappearSeconds(resolveIncomingConversationId(wire.conversationId, fromOnion), wire.seconds)
            is WireMessage.Receipt -> {
                if (wire.state == READ_RECEIPT_STATE) {
                    // The recipient just read this message -- start its disappearing-message
                    // countdown on our (the sender's) own copy too, now that it's confirmed
                    // seen, rather than back when we sent it. startExpiry() only takes effect
                    // if it isn't already running, so for a group this is simply "first read
                    // starts it" -- later receipts from other members are no-ops.
                    val message = storage.messages.get(wire.messageId)
                    val duration = message?.disappearSeconds
                    if (message != null && duration != null && message.expiresAtEpochMillis == null) {
                        storage.messages.startExpiry(wire.messageId, duration, now())
                        notifyChanged()
                    }
                } else {
                    runCatching { storage.messages.updateDeliveryState(wire.messageId, DeliveryState.valueOf(wire.state)) }
                }
            }
            is WireMessage.GroupInvite -> {
                val existing = storage.conversations.get(wire.conversationId)
                if (existing == null) {
                    // Never seen this group before: do NOT create it or write any membership
                    // data automatically -- that would let any peer fabricate a "group"
                    // containing arbitrary onion addresses (including ones we already trust)
                    // and have it silently planted. Hold it as a pending invite instead; only
                    // acceptGroupInvite() (an explicit user action) ever writes to
                    // conversations/group_members for it.
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
                    // Known group: only an already-recorded admin may push membership changes,
                    // and only if this invite is newer than the last one we applied -- an old,
                    // previously-valid invite (e.g. captured before a member was removed) must
                    // not be replayable back into a stale membership state.
                    val authorized = storage.members.isAdmin(wire.conversationId, fromOnion)
                    if (authorized && wire.epoch > existing.epoch) {
                        storage.conversations.upsert(existing.copy(title = wire.title, epoch = wire.epoch))
                        storage.members.replaceAll(wire.conversationId, wire.members, wire.admins)
                        notifyChanged()
                    }
                }
            }
            is WireMessage.ProfileUpdate -> {
                val groupId = wire.conversationId
                if (groupId == null) {
                    storeProfile(fromOnion, wire.image)
                    notifyChanged()
                } else if (storage.members.isAdmin(groupId, fromOnion)) {
                    // Only an admin of this specific group may push its shared photo -- otherwise
                    // any member (or anyone spoofing a group id) could overwrite it for everyone.
                    storeProfile(groupId, wire.image)
                    notifyChanged()
                }
            }
        }
    }

    private suspend fun deliverToConversation(conversation: Conversation, wire: WireMessage): Boolean {
        return when (conversation.type) {
            ConversationType.DIRECT -> sendWire(conversation.id, wire)
            ConversationType.GROUP -> {
                val recipients = storage.members.listForConversation(conversation.id)
                    .map { it.memberOnion.value }
                    .filter { it != selfOnion() }
                if (recipients.isEmpty()) return false
                recipients.count { sendWire(it, wire) } > 0
            }
        }
    }

    private suspend fun sendWire(toOnion: String, wire: WireMessage): Boolean = try {
        val ciphertext = crypto.encrypt(toOnion, MessagePadding.pad(WireCodec.encode(wire)))
        transportSend(toOnion, ciphertext)
    } catch (_: Exception) {
        false
    }

    private suspend fun transportSend(toOnion: String, ciphertext: ByteArray): Boolean =
        transport.connect(OnionAddress.parse(toOnion), servicePort).use { connection ->
            writeFrame(connection.output, FRAME_MESSAGE, selfOnion(), ciphertext)
            try {
                DataInputStream(connection.input).readByte().toInt() == FRAME_ACK
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Reads one framed request from an inbound connection. A frame is
     * [type:1][senderOnionLen:4][senderOnion][payloadLen:4][payload].
     * MESSAGE frames are decrypted and dispatched; BUNDLE_REQUEST frames are
     * answered on the same connection with our public prekey bundle.
     */
    suspend fun handleConnection(connection: DuplexConnection) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(connection.input)
            val type = input.readByte().toInt()
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
                    val blobLen = input.readLong()
                    if (blobLen < 0 || blobLen > MAX_ATTACHMENT_BYTES) return@withContext

                    // Rejects anything that isn't a plain UUID before it ever reaches a File
                    // constructor. This is the only thing standing between an attacker-supplied
                    // string and a path-traversal write -- e.g. an attachmentId of
                    // "../../../../data/data/app.niix/databases/x" -- since this frame is read
                    // and processed before any Signal decryption or identity check happens.
                    if (!ATTACHMENT_ID_PATTERN.matches(attachmentId)) return@withContext

                    // Only accept bytes for an attachment we already know about: one whose
                    // encrypted AttachmentOffer already arrived over an authenticated Signal
                    // session and created a PENDING row (see the AttachmentOffer branch of
                    // dispatch()). Without this check, anyone who can open a raw connection to
                    // this onion service -- no session, no identity check, not even a known
                    // contact -- could write arbitrary blobs to disk.
                    val pending = storage.attachments.get(attachmentId)
                    if (pending == null || pending.state != AttachmentState.PENDING) return@withContext

                    // Caps total attachment bytes accepted from unauthenticated connections per
                    // rolling window, independent of the connection-count limiter in
                    // MessageReceiver -- that alone wouldn't stop a moderate connection rate each
                    // carrying a near-max-size attachment from filling the disk.
                    if (!attachmentByteLimiter.allow(weight = blobLen)) return@withContext

                    attachmentsDir.mkdirs()
                    val dest = File(attachmentsDir, "$attachmentId.enc")
                    // Defense in depth: even though the UUID check above already rules out
                    // traversal characters, canonicalize and re-verify the resolved path is
                    // still inside attachmentsDir before writing anything.
                    val attachmentsRoot = attachmentsDir.canonicalFile
                    val canonicalDest = dest.canonicalFile
                    if (!canonicalDest.path.startsWith(attachmentsRoot.path + File.separator)) return@withContext

                    canonicalDest.outputStream().use { copyExactly(input, it, blobLen) }

                    // Verify the bytes actually written match the digest promised in the
                    // AttachmentOffer before marking this COMPLETE or surfacing it to the user.
                    val expectedDigest = pending.digest
                    if (expectedDigest != null && !sha256(canonicalDest).contentEquals(expectedDigest)) {
                        canonicalDest.delete()
                        storage.attachments.updateState(attachmentId, AttachmentState.FAILED)
                        notifyChanged()
                        return@withContext
                    }

                    storage.attachments.updateState(attachmentId, AttachmentState.COMPLETE)
                    writeFrame(connection.output, FRAME_ACK, selfOnion(), ByteArray(0))
                    notifyChanged()
                }
                FRAME_MESSAGE, FRAME_BUNDLE_REQUEST -> {
                    val payloadLen = input.readInt()
                    if (payloadLen !in 0..MAX_PAYLOAD_BYTES) return@withContext
                    val payload = ByteArray(payloadLen)
                    if (payloadLen > 0) input.readFully(payload)
                    if (type == FRAME_MESSAGE) {
                        if (payload.isNotEmpty() && handleIncoming(sender, payload)) {
                            writeFrame(connection.output, FRAME_ACK, selfOnion(), ByteArray(0))
                        }
                    } else {
                        writeFrame(connection.output, FRAME_BUNDLE_RESPONSE, selfOnion(), crypto.exportLocalBundle())
                    }
                }
                else -> Unit
            }
        } catch (_: Exception) {
            // Malformed / truncated / dropped connection.
        } finally {
            connection.close()
        }
    }

    /** Connects to a peer and fetches their public prekey bundle (Session-style key exchange). */
    private suspend fun fetchBundle(peerOnion: String): ByteArray = withContext(Dispatchers.IO) {
        transport.connect(OnionAddress.parse(peerOnion), servicePort).use { connection ->
            writeFrame(connection.output, FRAME_BUNDLE_REQUEST, selfOnion(), ByteArray(0))
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

    /**
     * Ensures a Signal session exists with the peer, fetching their bundle over Tor on first
     * contact. The fetched bundle's identity key must match the one captured from the share
     * code, which defeats a machine-in-the-middle substituting its own keys.
     */
    private suspend fun ensureSession(peerOnion: String) {
        if (crypto.hasSession(peerOnion)) return
        val bundle = fetchBundle(peerOnion)
        val expected = storage.settings.getString(identityKeyStoreKey(peerOnion))
        if (expected != null) {
            val actual = Base64.getEncoder().encodeToString(crypto.bundleIdentityKey(bundle))
            require(actual == expected) { "Identity mismatch for $peerOnion" }
        }
        crypto.establishOutboundSession(peerOnion, bundle)
        runCatching {
            val self = loadProfile("self")
            if (self != null) sendWire(peerOnion, WireMessage.ProfileUpdate(selfOnion(), self))
        }
    }

    private fun writeFrame(output: OutputStream, type: Int, senderOnion: String, payload: ByteArray) {
        val stream = DataOutputStream(output)
        val sender = senderOnion.toByteArray(Charsets.UTF_8)
        stream.writeByte(type)
        stream.writeInt(sender.size)
        stream.write(sender)
        stream.writeInt(payload.size)
        stream.write(payload)
        stream.flush()
    }

    private fun identityKeyStoreKey(onion: String): String = "identity:$onion"

    private fun finalizeDelivery(messageId: String, delivered: Boolean) {
        storage.messages.updateDeliveryState(
            messageId,
            if (delivered) DeliveryState.DELIVERED else DeliveryState.PENDING,
        )
    }

    private fun newOutgoingMessage(conversation: Conversation, type: MessageType, body: String, attachmentId: String?): Message {
        val createdAt = now()
        return Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            senderOnion = selfOnion(),
            direction = MessageDirection.OUTGOING,
            type = type,
            body = body,
            attachmentId = attachmentId,
            createdAtEpochMillis = createdAt,
            // Countdown doesn't start at send time -- see markConversationRead() -- so this
            // stays null until the recipient's read receipt starts it.
            expiresAtEpochMillis = null,
            deliveryState = DeliveryState.PENDING,
            deleted = false,
            remoteDeletable = true,
            disappearSeconds = conversation.disappearSeconds.takeIf { it > 0 },
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
            // Countdown doesn't start at receive time either -- only once this device's user
            // actually reads it, via markConversationRead(). Otherwise a disappearing message
            // could vanish before you were ever online to see it.
            expiresAtEpochMillis = null,
            deliveryState = DeliveryState.RECEIVED,
            deleted = false,
            remoteDeletable = true,
            disappearSeconds = expiresSeconds.takeIf { it > 0 },
        )
    }

    // For a DIRECT chat, each side keys the conversation by the OTHER party's onion.
    // The sender's wire conversationId is their id for us (our onion), which is wrong for us,
    // so we key incoming direct messages by the sender's onion. Group ids are shared, so we
    // keep them as-is.
    private fun resolveIncomingConversationId(wireConversationId: String, fromOnion: String): String {
        val existing = storage.conversations.get(wireConversationId)
        return if (existing != null && existing.type == ConversationType.GROUP) wireConversationId else fromOnion
    }

    private fun ensureDirectConversationIfMissing(conversationId: String, fromOnion: String) {
        if (conversationId == fromOnion && storage.conversations.get(conversationId) == null) {
            // A stranger who only has your onion (e.g. from scanning your QR code) can message
            // you without you ever adding them first. Rather than silently starting a normal
            // chat, park it as a pending request -- it shows up under "Requests" on Home until
            // you accept (saves them as a contact) or block them. If they're already a saved
            // contact of yours, though, this is just their side of a conversation you both
            // already agreed to, so it opens normally.
            val isStranger = !storage.contacts.isKnown(fromOnion)
            storage.conversations.upsert(
                Conversation(fromOnion, ConversationType.DIRECT, fromOnion, 0, now(), pending = isStranger),
            )
        }
    }

    private fun requireConversation(conversationId: String): Conversation =
        storage.conversations.get(conversationId)
            ?: throw IllegalArgumentException("No conversation $conversationId")

    private fun cleanupAttachment(attachmentId: String) {
        storage.attachments.get(attachmentId)?.let { File(it.filePath).delete() }
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

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val SELF = "self"
        private const val READ_RECEIPT_STATE = "READ"
        // How long a message can sit PENDING before retryPending() gives up and marks it
        // FAILED instead of retrying forever with no visible end state.
        private const val MAX_PENDING_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val ATTACHMENT_ID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private const val FRAME_MESSAGE = 1
        private const val FRAME_BUNDLE_REQUEST = 2
        private const val FRAME_BUNDLE_RESPONSE = 3
        private const val FRAME_ACK = 4
        private const val FRAME_ATTACHMENT = 5
        private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
        private const val MAX_ONION_BYTES = 256
        private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}
