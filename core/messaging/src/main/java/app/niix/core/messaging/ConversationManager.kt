package app.niix.core.messaging

import app.niix.core.crypto.CryptoEngine
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
import app.niix.core.model.Contact
import app.niix.core.model.Conversation
import app.niix.core.model.ConversationType
import app.niix.core.model.clampDisappearSeconds
import app.niix.core.model.DeliveryState
import app.niix.core.model.GroupMember
import app.niix.core.model.GroupRole
import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.model.OnionAddress
import app.niix.core.model.TrustState
import app.niix.core.relay.RelayManager
import app.niix.core.storage.AttachmentFiles
import app.niix.core.storage.GroupSenderKeyState
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
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IncomingNotice(val conversationId: String, val title: String, val preview: String)

class ConversationManager(
    private val storage: SecureStorage,
    private val crypto: CryptoEngine,
    private val transport: TorTransport,
    private val attachmentsDir: File,
    private val servicePort: Int,
    private val selfOnionProvider: () -> String?,

    private val sendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),

    private val relay: RelayManager? = null,
) {

    private fun selfOnion(): String = selfOnionProvider() ?: SELF

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    private val _incoming = MutableSharedFlow<IncomingNotice>(extraBufferCapacity = 32)
    val incoming: SharedFlow<IncomingNotice> = _incoming.asSharedFlow()

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
        notifyChanged()
        true
    }

    suspend fun blockRequest(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        val conversation = storage.conversations.get(conversationId) ?: return@withContext false
        if (!conversation.pending) return@withContext false
        storage.blocklist.block(conversationId)
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
        storage.messages.listForConversation(conversationId).lastOrNull()
    }

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

            if (nowMs - msg.createdAtEpochMillis > MAX_PENDING_AGE_MILLIS) {
                storage.messages.updateDeliveryState(msg.id, DeliveryState.FAILED)
                notifyChanged()
                continue
            }

            if (conversation.type == ConversationType.DIRECT && msg.type == MessageType.TEXT) {
                val wire = WireMessage.Text(conversation.id, msg.id, selfOnion(), msg.body, conversation.disappearSeconds)
                val outcome = try {
                    ensureSession(conversation.id)
                    sendWireDirectOrRelay(conversation.id, wire)
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
                continue
            }

            val delivered = try {
                when (msg.type) {
                    MessageType.TEXT -> {
                        val wire = WireMessage.Text(conversation.id, msg.id, selfOnion(), msg.body, conversation.disappearSeconds)
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

    private suspend fun sendWireDirectOrRelay(toOnion: String, wire: WireMessage): DeliveryOutcome {
        val ciphertext = try {
            crypto.encrypt(toOnion, MessagePadding.pad(WireCodec.encode(wire)))
        } catch (_: Exception) {
            return DeliveryOutcome.FAILED
        }
        if (transportSend(toOnion, ciphertext)) return DeliveryOutcome.DELIVERED
        val relay = relay ?: return DeliveryOutcome.FAILED
        val relayed = try {
            relay.storeForOffline(toOnion, ciphertext)
        } catch (_: Exception) {
            false
        }
        return if (relayed) DeliveryOutcome.RELAYED else DeliveryOutcome.FAILED
    }

    suspend fun fetchRelayedMessages() = withContext(Dispatchers.IO) {
        val relay = relay ?: return@withContext
        if (!storage.appLock.isUnlocked()) return@withContext
        val envelopes = try {
            relay.fetchIncoming()
        } catch (_: Exception) {
            return@withContext
        }
        for ((senderIdKey, envelope) in envelopes) {
            val senderOnion = crypto.onionForIdentityKey(senderIdKey) ?: continue
            runCatching { handleIncoming(senderOnion, envelope, viaRelay = true) }
        }
    }

    suspend fun setRelayModeEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val relay = relay ?: return@withContext
        relay.setHostingEnabled(enabled)
        val wire = WireMessage.RelayCapabilityUpdate(selfOnion(), enabled)
        storage.contacts.list().forEach { contact ->
            val onion = contact.onionAddress.value
            runCatching { if (crypto.hasSession(onion)) sendWire(onion, wire) }
        }
    }

    fun isRelayModeEnabled(): Boolean = relay?.isHostingEnabled() ?: false

    fun relayStorageBudgetBytes(): Long? = relay?.storageBudgetBytes()

    suspend fun setRelayStorageBudgetBytes(bytes: Long) = withContext(Dispatchers.IO) {
        relay?.setStorageBudgetBytes(bytes)
    }

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
        val encFile = storage.attachmentFile(attachmentId) ?: return false
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

    suspend fun addContactByCode(peerOnion: String, identityKey: ByteArray, title: String): Conversation =
        withContext(Dispatchers.IO) {
            // Both a scanned QR code and a pasted string are untrusted input -- validate both
            // fields completely before writing anything, rather than interleaving validation
            // with mutation. A malformed onion or key failing partway through used to leave an
            // orphaned settings row and/or a Conversation with an invalid id sitting in the
            // database even though the whole operation ultimately failed.
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
            notifyChanged()
            conversation
        }

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

    suspend fun trustState(onion: String): TrustState? = withContext(Dispatchers.IO) {
        storage.contacts.get(onion)?.trustState
    }

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
            ensureGroupSenderKey(conversation)
            conversation
        }

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
        storage.conversations.delete(conversationId)
        notifyChanged()
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

            revokeSenderKeysFor(conversationId, listOf(onion))
            true
        }

    suspend fun promoteGroupMember(conversationId: String, onion: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!storage.members.isAdmin(conversationId, selfOnion())) return@withContext false
            storage.members.setRole(conversationId, onion, GroupRole.ADMIN)
            syncGroup(conversationId)
            true
        }

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
        storage.attachments.listForConversation(conversationId).forEach { runCatching { storage.deleteAttachmentFile(it.id) } }
        storage.attachments.deleteForConversation(conversationId)
        storage.messages.deleteForConversation(conversationId)
        storage.members.replaceAll(conversationId, emptyList(), emptyList())
        storage.conversations.delete(conversationId)
        storage.groupSenderKeyState.delete(conversationId)
        storage.groupRemoteSenderKeys.deleteForConversation(conversationId)
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

        val refreshed = storage.conversations.get(conversationId) ?: return
        ensureGroupSenderKey(refreshed)
        notifyChanged()
    }

    suspend fun sendText(conversationId: String, body: String): Message = withContext(Dispatchers.IO) {
        val conversation = requireConversation(conversationId)
        val message = newOutgoingMessage(conversation, MessageType.TEXT, body, attachmentId = null)
        storage.messages.insert(message)
        notifyChanged()
        sendScope.launch { deliverTextNow(conversation, message) }
        message
    }

    private suspend fun deliverTextNow(conversation: Conversation, message: Message) = withContext(Dispatchers.IO) {
        val wire = WireMessage.Text(conversation.id, message.id, selfOnion(), message.body, conversation.disappearSeconds)
        val delivered = try {
            if (conversation.type == ConversationType.DIRECT) ensureSession(conversation.id)
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
            storage.attachmentCipher.encryptFile(key, source, encryptedFile)
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
            conversation.id, message.id, selfOnion(), attachmentId, mimeType,
            sizeBytes, key, digest, conversation.disappearSeconds,
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
        notifyChanged()
    }

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
        val encFile = storage.attachmentFile(attachmentId) ?: return@withContext null
        if (!encFile.isFile) return@withContext null
        val out = ByteArrayOutputStream()
        runCatching { storage.attachmentCipher.decrypt(att.encKey, encFile.inputStream(), out) }
            .getOrElse { return@withContext null }
        out.toByteArray()
    }

    suspend fun decryptAttachmentTo(attachmentId: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val att = storage.attachments.get(attachmentId) ?: return@withContext false
        val encFile = storage.attachmentFile(attachmentId) ?: return@withContext false
        if (!encFile.isFile) return@withContext false
        runCatching {
            encFile.inputStream().use { input ->
                dest.outputStream().use { o -> storage.attachmentCipher.decrypt(att.encKey, input, o) }
            }
        }.isSuccess
    }

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

    suspend fun handleIncoming(fromOnion: String, ciphertext: ByteArray, viaRelay: Boolean = false): Boolean = withContext(Dispatchers.IO) {

        if (storage.blocklist.isBlocked(fromOnion)) return@withContext true
        if (storage.settings.getBool(SettingsStore.KEY_ALLOWLIST_ONLY, false) && !isKnownPeer(fromOnion)) {
            return@withContext true
        }
        return@withContext try {
            val plaintext = crypto.decrypt(fromOnion, ciphertext)
            dispatch(fromOnion, WireCodec.decode(MessagePadding.unpad(plaintext)), viaRelay)
            notifyChanged()
            runCatching { maybeIssueRelayGrant(fromOnion) }
            true
        } catch (_: Exception) {

            false
        }
    }

    private fun isKnownPeer(onion: String): Boolean =
        storage.contacts.isKnown(onion) ||
            storage.conversations.get(onion) != null ||
            crypto.hasRemoteIdentity(onion)

    private fun dispatch(fromOnion: String, wire: WireMessage, viaRelay: Boolean = false) {
        when (wire) {
            is WireMessage.Text -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
                val isNew = storage.messages.get(wire.messageId) == null
                ensureDirectConversationIfMissing(convId, fromOnion)
                storage.messages.insert(incomingMessage(convId, wire.messageId, fromOnion, MessageType.TEXT, wire.body, null, wire.expiresSeconds))
                if (isNew) emitIncoming(convId, fromOnion, wire.body)
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

                if (!AttachmentFiles.isValidId(wire.attachmentId)) return

                if (wire.sizeBytes < 0 || wire.sizeBytes > MAX_ATTACHMENT_BYTES) return
                if (wire.digest == null) return
                val resolvedFile = AttachmentFiles.resolve(attachmentsDir, wire.attachmentId) ?: return
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)
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
            is WireMessage.DeleteForEveryone ->
                wire.targetMessageIds.forEach { messageId ->
                    val message = storage.messages.get(messageId)

                    if (message != null && message.senderOnion == fromOnion) {
                        storage.messages.markDeletedForEveryone(messageId)
                    }
                }
            is WireMessage.TimerUpdate -> {
                val convId = resolveIncomingConversationId(wire.conversationId, fromOnion)

                if (storage.conversations.get(convId) == null || isAuthorizedForConversation(convId, fromOnion)) {
                    storage.conversations.setDisappearSeconds(convId, wire.seconds)
                }
            }
            is WireMessage.Receipt -> {

                val message = storage.messages.get(wire.messageId)
                val authorized = message != null && isAuthorizedForConversation(message.conversationId, fromOnion)
                if (!authorized || message == null) return
                if (wire.state == READ_RECEIPT_STATE) {

                    val duration = message.disappearSeconds
                    if (duration != null && message.expiresAtEpochMillis == null) {
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

                    val authorized = storage.members.isAdmin(wire.conversationId, fromOnion)
                    if (authorized && wire.epoch > existing.epoch) {
                        val previousMembers = storage.members.listForConversation(wire.conversationId)
                            .map { it.memberOnion.value }
                        storage.conversations.upsert(existing.copy(title = wire.title, epoch = wire.epoch))
                        storage.members.replaceAll(wire.conversationId, wire.members, wire.admins)

                        val removed = previousMembers - wire.members.toSet()
                        revokeSenderKeysFor(wire.conversationId, removed)
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

                    storeProfile(groupId, wire.image)
                    notifyChanged()
                }
            }
            is WireMessage.SenderKeyDistribution -> {

                if (isAuthorizedForConversation(wire.conversationId, fromOnion)) {
                    val distributionId = runCatching {
                        crypto.processGroupDistribution(fromOnion, wire.distributionBytes)
                    }.getOrNull()
                    if (distributionId != null) {
                        storage.groupRemoteSenderKeys.record(wire.conversationId, fromOnion, distributionId)
                    }
                }
            }
            is WireMessage.GroupCiphertext -> {

                if (!isAuthorizedForConversation(wire.conversationId, fromOnion)) return

                val innerBytes = runCatching {
                    MessagePadding.unpad(crypto.groupDecrypt(fromOnion, wire.ciphertext))
                }.getOrNull()
                val inner = innerBytes?.let { runCatching { WireCodec.decode(it) }.getOrNull() }
                if (inner != null) dispatch(fromOnion, inner)
            }
            is WireMessage.RelayGrant -> {
                val relay = relay ?: return

                if (!wire.granteeIdentityKey.contentEquals(crypto.localIdentityKey())) return
                val issuerIdentityKey = crypto.remoteIdentityKeyBytes(fromOnion) ?: return
                relay.recordGrantReceived(fromOnion, issuerIdentityKey, wire.issuedAt, wire.expiresAt, wire.signature)
            }
            is WireMessage.RelayCapabilityUpdate -> {
                val relay = relay ?: return
                val identityKey = crypto.remoteIdentityKeyBytes(fromOnion) ?: return
                relay.onContactAnnouncedRelayCapability(fromOnion, identityKey, wire.enabled)
            }
            is WireMessage.Dummy -> {

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
                val outgoing = groupWrapIfApplicable(conversation, wire) ?: wire
                recipients.count { sendWire(it, outgoing) } > 0
            }
        }
    }

    private suspend fun groupWrapIfApplicable(conversation: Conversation, wire: WireMessage): WireMessage? {
        if (wire !is WireMessage.Text && wire !is WireMessage.AttachmentOffer) return null
        val state = ensureGroupSenderKey(conversation) ?: return null
        val distributionId = runCatching { UUID.fromString(state.distributionId) }.getOrNull() ?: return null
        return try {
            val innerBytes = MessagePadding.pad(WireCodec.encode(wire))
            val ciphertext = crypto.groupEncrypt(distributionId, innerBytes)
            WireMessage.GroupCiphertext(conversation.id, selfOnion(), ciphertext)
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
            .filter { it != selfOnion() }
        val distributionWire = WireMessage.SenderKeyDistribution(conversation.id, selfOnion(), distribution.messageBytes)
        recipients.forEach { runCatching { ensureSession(it); sendWire(it, distributionWire) } }
        return state
    }

    private fun revokeSenderKeysFor(conversationId: String, removedOnions: List<String>) {
        removedOnions.forEach { onion ->
            storage.groupRemoteSenderKeys.listDistributionIds(conversationId, onion).forEach { distributionId ->
                runCatching { crypto.revokeGroupSenderKey(onion, distributionId) }
            }
            storage.groupRemoteSenderKeys.deleteForConversationAndSender(conversationId, onion)
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

    suspend fun handleConnection(connection: DuplexConnection) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(connection.input)
            val type = input.readByte().toInt()

            if (type in FRAME_RELAY_STORE..FRAME_RELAY_FIND_NODE_RESPONSE) {
                relay?.handleFrame(type, input, connection.output)
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
                    val blobLen = input.readLong()
                    if (blobLen < 0 || blobLen > MAX_ATTACHMENT_BYTES) return@withContext

                    attachmentsDir.mkdirs()
                    val canonicalDest = AttachmentFiles.resolve(attachmentsDir, attachmentId) ?: return@withContext

                    val pending = storage.attachments.get(attachmentId)
                    if (pending == null || pending.state != AttachmentState.PENDING) return@withContext

                    val expectedDigest = pending.digest
                    if (expectedDigest == null || blobLen != pending.sizeBytes) {
                        storage.attachments.updateState(attachmentId, AttachmentState.FAILED)
                        notifyChanged()
                        return@withContext
                    }

                    if (!attachmentByteLimiter.allow(weight = blobLen)) return@withContext

                    canonicalDest.outputStream().use { copyExactly(input, it, blobLen) }

                    if (!sha256(canonicalDest).contentEquals(expectedDigest)) {
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

        } finally {
            connection.close()
        }
    }

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
        runCatching { maybeIssueRelayGrant(peerOnion) }
    }

    private suspend fun maybeIssueRelayGrant(peerOnion: String) {
        val relay = relay ?: return
        val identityKey = crypto.remoteIdentityKeyBytes(peerOnion) ?: return
        val grant = relay.grantDue(peerOnion, identityKey) ?: return
        val wire = WireMessage.RelayGrant(grant.granteeIdentityKey, grant.issuedAt, grant.expiresAt, grant.signature)
        if (sendWire(peerOnion, wire)) {
            relay.recordGrantIssued(peerOnion, grant)
        }
    }

    suspend fun refreshRelayGrants() = withContext(Dispatchers.IO) {
        if (relay == null) return@withContext
        if (!storage.appLock.isUnlocked()) return@withContext
        val contacts = try { storage.contacts.list() } catch (_: Exception) { return@withContext }
        for (contact in contacts) {
            val onion = contact.onionAddress.value
            if (!crypto.hasSession(onion)) continue
            runCatching { maybeIssueRelayGrant(onion) }
        }
    }

    private val coverTrafficMisses = ConcurrentHashMap<String, Int>()

    suspend fun sendCoverTraffic() = withContext(Dispatchers.IO) {
        if (!storage.appLock.isUnlocked()) return@withContext
        val candidates = try {
            storage.contacts.list()
                .map { it.onionAddress.value }
                .filter { crypto.hasSession(it) }
                .filter { (coverTrafficMisses[it] ?: 0) < MAX_CONSECUTIVE_COVER_MISSES }
        } catch (_: Exception) {
            return@withContext
        }
        val onion = candidates.randomOrNull() ?: return@withContext

        val filler = ByteArray(COVER_TRAFFIC_MIN_FILLER_BYTES + secureRandomInt(COVER_TRAFFIC_FILLER_JITTER_BYTES))
        SecureRandom().nextBytes(filler)
        val ciphertext = try {
            crypto.encrypt(onion, MessagePadding.pad(WireCodec.encode(WireMessage.Dummy(filler))))
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

    private fun isAuthorizedForConversation(conversationId: String, fromOnion: String): Boolean {
        val conversation = storage.conversations.get(conversationId) ?: return false
        return when (conversation.type) {
            ConversationType.DIRECT -> conversationId == fromOnion
            ConversationType.GROUP -> storage.members.isMember(conversationId, fromOnion)
        }
    }

    private fun ensureDirectConversationIfMissing(conversationId: String, fromOnion: String) {
        if (conversationId == fromOnion && storage.conversations.get(conversationId) == null) {

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

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val SELF = "self"
        private const val READ_RECEIPT_STATE = "READ"

        private const val MAX_PENDING_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val FRAME_MESSAGE = 1
        private const val FRAME_BUNDLE_REQUEST = 2
        private const val FRAME_BUNDLE_RESPONSE = 3
        private const val FRAME_ACK = 4
        private const val FRAME_ATTACHMENT = 5

        private const val FRAME_RELAY_STORE = 6
        private const val FRAME_RELAY_FETCH = 7
        private const val FRAME_RELAY_FETCH_RESPONSE = 8
        private const val FRAME_RELAY_DELETE_RECEIPT = 9
        private const val FRAME_RELAY_ANNOUNCE = 10
        private const val FRAME_RELAY_REJECT = 11
        private const val FRAME_RELAY_FIND_NODE = 12
        private const val FRAME_RELAY_FIND_NODE_RESPONSE = 13

        private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
        private const val MAX_ONION_BYTES = 256
        private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

        private const val COVER_TRAFFIC_MIN_FILLER_BYTES = 16
        private const val COVER_TRAFFIC_FILLER_JITTER_BYTES = 184
        private const val MAX_CONSECUTIVE_COVER_MISSES = 50
    }
}

private enum class DeliveryOutcome { DELIVERED, RELAYED, FAILED }
