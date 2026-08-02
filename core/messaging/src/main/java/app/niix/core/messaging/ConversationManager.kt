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

    suspend fun messagesFor(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        storage.messages.listForConversation(conversationId)
    }

    suspend fun lastMessage(conversationId: String): Message? = withContext(Dispatchers.IO) {
        storage.messages.listForConversation(conversationId).lastOrNull()
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
        for (msg in pending) {
            val conversation = storage.conversations.get(msg.conversationId) ?: continue
            if (conversation.type != ConversationType.DIRECT || msg.deleted || msg.type != MessageType.TEXT) continue
            val wire = WireMessage.Text(conversation.id, msg.id, selfOnion(), msg.body, conversation.disappearSeconds)
            val delivered = try {
                ensureSession(conversation.id)
                deliverToConversation(conversation, wire)
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                storage.messages.updateDeliveryState(msg.id, DeliveryState.DELIVERED)
                notifyChanged()
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

    suspend fun searchMessageConversationIds(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else runCatching { storage.messages.searchConversationIds(query) }.getOrDefault(emptyList())
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

    suspend fun createGroup(title: String, memberOnions: List<String>): Conversation =
        withContext(Dispatchers.IO) {
            val conversationId = UUID.randomUUID().toString()
            val conversation = Conversation(
                id = conversationId,
                type = ConversationType.GROUP,
                title = title,
                disappearSeconds = 0,
                createdAtEpochMillis = now(),
            )
            storage.conversations.upsert(conversation)
            val self = selfOnion()
            val allMembers = (memberOnions + self).distinct()
            val admins = listOf(self)
            storage.members.replaceAll(conversationId, allMembers, admins)
            val invite = WireMessage.GroupInvite(conversationId, title, allMembers, admins)
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

    private suspend fun syncGroup(conversationId: String) {
        val conversation = storage.conversations.get(conversationId) ?: return
        val members = storage.members.listForConversation(conversationId)
        val allOnions = members.map { it.memberOnion.value }
        val admins = members.filter { it.role == GroupRole.ADMIN }.map { it.memberOnion.value }
        val invite = WireMessage.GroupInvite(conversationId, conversation.title, allOnions, admins)
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
            if (delivered && conversation.type == ConversationType.DIRECT) {
                runCatching { sendAttachmentBlob(conversation.id, attachmentId, encryptedFile) }
            }
            finalizeDelivery(message.id, delivered)
            message
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
        for (conversation in storage.conversations.list().filter { it.type == ConversationType.DIRECT }) {
            runCatching {
                ensureSession(conversation.id)
                sendWire(conversation.id, WireMessage.ProfileUpdate(selfOnion(), bytes))
            }
        }
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
            dispatch(fromOnion, WireCodec.decode(plaintext))
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
                storage.conversations.setDisappearSeconds(wire.conversationId, wire.seconds)
            is WireMessage.Receipt ->
                runCatching { storage.messages.updateDeliveryState(wire.messageId, DeliveryState.valueOf(wire.state)) }
            is WireMessage.GroupInvite -> {
                val existing = storage.conversations.get(wire.conversationId)
                // Only accept membership changes to an existing group from one of its admins;
                // the first invite (group not yet known) is always accepted.
                val authorized = existing == null || storage.members.isAdmin(wire.conversationId, fromOnion)
                if (authorized) {
                    storage.conversations.upsert(
                        Conversation(
                            wire.conversationId,
                            ConversationType.GROUP,
                            wire.title,
                            existing?.disappearSeconds ?: 0,
                            existing?.createdAtEpochMillis ?: now(),
                        ),
                    )
                    storage.members.replaceAll(wire.conversationId, wire.members, wire.admins)
                }
            }
            is WireMessage.ProfileUpdate -> {
                storeProfile(fromOnion, wire.image)
                notifyChanged()
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
        val ciphertext = crypto.encrypt(toOnion, WireCodec.encode(wire))
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
                    attachmentsDir.mkdirs()
                    val dest = File(attachmentsDir, "$attachmentId.enc")
                    dest.outputStream().use { copyExactly(input, it, blobLen) }
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
            expiresAtEpochMillis = expiryFrom(conversation.disappearSeconds, createdAt),
            deliveryState = DeliveryState.PENDING,
            deleted = false,
            remoteDeletable = true,
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
            expiresAtEpochMillis = expiryFrom(expiresSeconds, createdAt),
            deliveryState = DeliveryState.RECEIVED,
            deleted = false,
            remoteDeletable = true,
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
            storage.conversations.upsert(
                Conversation(fromOnion, ConversationType.DIRECT, fromOnion, 0, now()),
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

    private fun expiryFrom(seconds: Long, createdAt: Long): Long? =
        if (seconds > 0) createdAt + seconds * 1000 else null

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
