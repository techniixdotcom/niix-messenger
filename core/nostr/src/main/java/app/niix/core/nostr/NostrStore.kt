package app.niix.core.nostr

import app.niix.core.storage.KeyValueStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** How to reach a contact: the mailbox secret they gave us and the relays they read. */
class Outbox(val secret: ByteArray, val relays: List<RelayUrl>)

/** What we tell a contact about our own inbox. An empty secret withdraws it. */
class InboxAnnouncement(val secret: ByteArray, val relays: List<RelayUrl>) {
    val isRevocation: Boolean get() = secret.isEmpty()

    /** Changes whenever the announcement would, so we resend exactly when needed. */
    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(secret)
        relays.forEach { md.update(0); md.update(it.toString().toByteArray()) }
        return Hex.encode(md.digest()).take(32)
    }
}

/**
 * Nostr state, kept in the encrypted settings table. Per contact there's the inbox secret we
 * generated for them ("in"), a fingerprint of what they last acknowledged ("in-sent"), and the
 * secret and relays they gave us ("out").
 */
class NostrStore(private val kv: KeyValueStore, private val rng: SecureRandom = SecureRandom()) {

    fun isEnabled(): Boolean = kv.getBool(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) = kv.setBool(KEY_ENABLED, enabled)

    fun ownRelays(): List<RelayUrl> {
        val stored = kv.getString(KEY_RELAYS) ?: return DEFAULT_RELAYS.mapNotNull(RelayUrl::parse)
        return stored.split('\n').mapNotNull(RelayUrl::parse).distinct().take(NostrMailbox.MAX_OWN_RELAYS)
    }

    /** Saves whichever entries parse and returns them. Empty is allowed: listen nowhere. */
    fun setOwnRelays(raw: List<String>): List<RelayUrl> {
        val parsed = raw.mapNotNull(RelayUrl::parse).distinct().take(NostrMailbox.MAX_OWN_RELAYS)
        kv.setString(KEY_RELAYS, parsed.joinToString("\n"))
        return parsed
    }

    /** Everyone we've issued a mailbox to, including group members who aren't contacts. */
    fun inboxPeers(): List<String> = kv.getString(KEY_PEERS)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun existingInboxSecret(peer: String): ByteArray? =
        kv.getString(inKey(peer))?.let(::unb64)?.takeIf { it.size == MailboxCrypto.SECRET_BYTES }

    /** What [peer] should be told now, or null if they already have it. */
    fun announcementDue(peer: String): InboxAnnouncement? {
        val relays = ownRelays()
        val current = if (isEnabled() && relays.isNotEmpty()) {
            InboxAnnouncement(inboxSecret(peer), relays)
        } else {
            InboxAnnouncement(ByteArray(0), emptyList())
        }
        val sent = kv.getString(sentKey(peer))
        // No point withdrawing something they were never given.
        if (sent == null && current.isRevocation) return null
        return current.takeIf { sent != it.fingerprint() }
    }

    fun recordAnnounced(peer: String, announcement: InboxAnnouncement) {
        kv.setString(sentKey(peer), announcement.fingerprint())
    }

    /** Stores what [peer] told us; an empty secret removes it. False if unusable. */
    fun recordOutbox(peer: String, secret: ByteArray, rawRelays: List<String>): Boolean {
        val relays = rawRelays.take(MAX_PEER_RELAYS).mapNotNull(RelayUrl::parse).distinct()
        if (secret.size != MailboxCrypto.SECRET_BYTES || relays.isEmpty()) {
            kv.remove(outKey(peer))
            return secret.isEmpty()
        }
        kv.setString(outKey(peer), b64(secret) + "|" + relays.joinToString(","))
        return true
    }

    fun outbox(peer: String): Outbox? {
        val (encoded, relayList) = kv.getString(outKey(peer))?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val secret = unb64(encoded)?.takeIf { it.size == MailboxCrypto.SECRET_BYTES } ?: return null
        val relays = relayList.split(',').mapNotNull(RelayUrl::parse)
        return if (relays.isEmpty()) null else Outbox(secret, relays)
    }

    /** Drops everything held for [peer]. */
    fun forget(peer: String) {
        kv.setString(KEY_PEERS, (inboxPeers() - peer).joinToString("\n"))
        kv.remove(inKey(peer))
        kv.remove(sentKey(peer))
        kv.remove(outKey(peer))
    }

    /** Created on first use and kept. See MailboxCrypto.decoyTags. */
    fun decoySeed(): ByteArray {
        kv.getString(KEY_DECOY_SEED)?.let(::unb64)?.takeIf { it.size == MailboxCrypto.SECRET_BYTES }?.let { return it }
        val seed = MailboxCrypto.newSecret(rng)
        kv.setString(KEY_DECOY_SEED, b64(seed))
        return seed
    }

    fun loadCursor(): Long? = kv.getString(KEY_CURSOR)?.toLongOrNull()

    fun saveCursor(unixSeconds: Long) = kv.setString(KEY_CURSOR, unixSeconds.toString())

    fun loadSeen(): List<String> = kv.getString(KEY_SEEN)?.split(',')?.filter { Hex.isHex(it, 32) } ?: emptyList()

    fun saveSeen(ids: List<String>) = kv.setString(KEY_SEEN, ids.joinToString(","))

    private fun inboxSecret(peer: String): ByteArray {
        existingInboxSecret(peer)?.let { return it }
        val secret = MailboxCrypto.newSecret(rng)
        kv.setString(inKey(peer), b64(secret))
        kv.setString(KEY_PEERS, (inboxPeers() + peer).distinct().joinToString("\n"))
        return secret
    }

    private fun inKey(peer: String) = "nostr:in:$peer"
    private fun sentKey(peer: String) = "nostr:in-sent:$peer"
    private fun outKey(peer: String) = "nostr:out:$peer"

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    companion object {
        private const val KEY_ENABLED = "nostr_enabled"
        private const val KEY_RELAYS = "nostr_relays"
        private const val KEY_CURSOR = "nostr_cursor"
        private const val KEY_SEEN = "nostr_seen"
        private const val KEY_PEERS = "nostr_inbox_peers"
        private const val KEY_DECOY_SEED = "nostr_decoy_seed"
        private const val MAX_PEER_RELAYS = 6

        /**
         * Big, long-running public relays (all strfry, open to writes). They only ever see
         * ciphertext. There are several so one going away or blocking Tor exits doesn't stop
         * delivery, and users can swap in their own.
         *
         * Two are reached at their operators' onion addresses, so that traffic never leaves Tor
         * through an exit. The other two have no onion service and stay as a fallback in case an
         * onion service is down.
         */
        val DEFAULT_RELAYS = listOf(
            "ws://nostrnetl6yd5whkldj3vqsxyyaq3tkuspy23a3qgx7cdepb4564qgqd.onion",
            "ws://oxtrdevav64z64yb7x6rjg4ntzqjhedm5b5zjqulugknhzr46ny2qbad.onion",
            "wss://relay.damus.io",
            "wss://nos.lol",
        )
    }
}

class StoreMailboxDirectory(private val store: NostrStore) : MailboxDirectory {

    override fun ownRelays(): List<RelayUrl> = if (store.isEnabled()) store.ownRelays() else emptyList()

    override fun inboundMailboxes(): List<InboundMailbox> {
        if (!store.isEnabled()) return emptyList()
        return store.inboxPeers().mapNotNull { peer -> store.existingInboxSecret(peer)?.let { InboundMailbox(peer, it) } }
    }

    override fun decoySeed(): ByteArray = store.decoySeed()
    override fun loadCursor(): Long? = store.loadCursor()
    override fun saveCursor(unixSeconds: Long) = store.saveCursor(unixSeconds)
    override fun loadSeen(): List<String> = store.loadSeen()
    override fun saveSeen(ids: List<String>) = store.saveSeen(ids)
}
