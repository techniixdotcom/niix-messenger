package app.niix.core.messaging

import app.niix.core.nostr.InboundMailbox
import app.niix.core.nostr.MailboxDirectory
import app.niix.core.nostr.NostrStore
import app.niix.core.nostr.RelayUrl
import app.niix.core.nostr.StoreMailboxDirectory
import app.niix.core.storage.ReceiveBeacon
import app.niix.core.storage.SecureStorage
import app.niix.core.storage.SettingsStore
import java.security.MessageDigest

/**
 * The Nostr inbox as the mailbox sees it: from the database while it's open, and from the receive
 * beacon while it isn't. Every read while open also brings the beacon up to date, so after a
 * restart while locked the inbox listens exactly as it did at the last unlock. How far the inbox
 * has been read goes to both, so a restart while locked doesn't fetch two weeks of envelopes into
 * the locked inbox again.
 */
internal class InboxDirectory(private val storage: SecureStorage, nostrStore: NostrStore) : MailboxDirectory {

    private val store = StoreMailboxDirectory(nostrStore)
    private val beacon: ReceiveBeacon get() = storage.receiveBeacon
    private val open: Boolean get() = storage.appLock.isUnlocked()

    /** A hash of what the beacon last got, so an unchanged inbox isn't written again. */
    @Volatile
    private var mirrored: String? = null

    @Volatile
    private var cursor: Long? = null
    private var seen: List<String> = emptyList()

    override fun ownRelays(): List<RelayUrl> =
        if (open) store.ownRelays() else beacon.inbox()?.relays?.mapNotNull(RelayUrl::parse).orEmpty()

    override fun inboundMailboxes(): List<InboundMailbox> {
        if (!open) return beacon.inbox()?.mailboxes?.map { (peer, secret) -> InboundMailbox(peer, secret) }.orEmpty()
        return store.inboundMailboxes().also(::mirror)
    }

    override fun decoySeed(): ByteArray =
        if (open) store.decoySeed() else beacon.inbox()?.decoySeed ?: error("No inbox while locked")

    override fun loadCursor(): Long? {
        val stored = if (open) runCatching { store.loadCursor() }.getOrNull() else null
        return listOfNotNull(stored, beacon.readState()?.cursor).maxOrNull()
    }

    override fun saveCursor(unixSeconds: Long) {
        if (open) runCatching { store.saveCursor(unixSeconds) }
        cursor = unixSeconds
        saveReadState()
    }

    override fun loadSeen(): List<String> {
        val stored = if (open) runCatching { store.loadSeen() }.getOrDefault(emptyList()) else emptyList()
        return (beacon.readState()?.seen.orEmpty() + stored).distinct()
    }

    override fun saveSeen(ids: List<String>) {
        if (open) runCatching { store.saveSeen(ids) }
        seen = ids
        saveReadState()
    }

    private fun saveReadState() {
        if (!beacon.exists()) return
        runCatching { beacon.updateReadState(ReceiveBeacon.ReadState(cursor, seen)) }
    }

    private fun mirror(mailboxes: List<InboundMailbox>) {
        if (storage.settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)) {
            if (mirrored != null || beacon.exists()) beacon.destroy()
            mirrored = null
            return
        }
        val relays = store.ownRelays().map { it.toString() }
        val seed = store.decoySeed()
        val fingerprint = MessageDigest.getInstance("SHA-256").run {
            relays.forEach { update(it.toByteArray()); update(0) }
            mailboxes.forEach { update(it.peer.toByteArray()); update(0); update(it.secret) }
            update(seed)
            digest().joinToString("") { "%02x".format(it) }
        }
        if (fingerprint == mirrored) return
        runCatching { beacon.updateInbox(ReceiveBeacon.Inbox(relays, mailboxes.map { it.peer to it.secret }, seed)) }
            .onSuccess { mirrored = fingerprint }
    }
}
