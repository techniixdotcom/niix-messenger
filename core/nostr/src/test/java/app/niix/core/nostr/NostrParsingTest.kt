package app.niix.core.nostr

import app.niix.core.storage.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NostrParsingTest {

    @Test
    fun `normal relay urls are accepted`() {
        assertEquals(RelayUrl("relay.damus.io", 443, "/", true), RelayUrl.parse("wss://relay.damus.io"))
        assertEquals(RelayUrl("relay.example.com", 8443, "/nostr", true), RelayUrl.parse(" wss://Relay.Example.com:8443/nostr "))
        val onion = "ws://" + "a".repeat(56) + ".onion"
        assertEquals(80, RelayUrl.parse(onion)!!.port)
        assertEquals("wss://relay.damus.io/", RelayUrl.parse("wss://relay.damus.io")!!.toString())
    }

    @Test
    fun `unsafe or malformed relay urls are rejected`() {
        listOf(
            "ws://relay.damus.io", // cleartext through a Tor exit
            "https://relay.damus.io",
            "wss://127.0.0.1",
            "wss://localhost",
            "wss://user:pass@relay.example.com",
            "wss://relay.example.com/?x=1",
            "wss://relay.example.com/#f",
            "wss://-bad.example.com",
            "",
            "wss://" + "a".repeat(250) + ".com",
            "not a url",
        ).forEach { assertNull(it, RelayUrl.parse(it)) }
    }

    @Test
    fun `relay messages parse`() {
        val parsed = Json.parse("""["OK","abc",true,"duplicate: already have it"]""") as List<*>
        assertEquals(listOf("OK", "abc", true, "duplicate: already have it"), parsed)
        val event = Json.parse("""["EVENT","niix",{"kind":4459,"tags":[["z","t"]],"content":"aé\n\"b\""}]""") as List<*>
        val obj = event[2] as Map<*, *>
        assertEquals(4459L, obj["kind"])
        assertEquals("aé\n\"b\"", obj["content"])
        assertEquals(listOf(null, false, -1.5), Json.parse("[null,false,-1.5]"))
    }

    @Test
    fun `malformed or deeply nested JSON is rejected`() {
        listOf("[", "[1,]", "{\"a\" 1}", "\"unterminated", "[1] x", "tru", "\"bad \\q escape\"").forEach {
            try {
                Json.parse(it)
                fail("Parsed malformed JSON: $it")
            } catch (_: Json.ParseException) {
            }
        }
        try {
            Json.parse("[".repeat(50) + "]".repeat(50))
            fail("Parsed JSON nested beyond the limit")
        } catch (_: Json.ParseException) {
        }
    }

    @Test
    fun `events with wrong field types are rejected`() {
        assertNull(NostrEvent.fromJson(Json.parse("""{"id":"x","pubkey":"y","created_at":"1","kind":1,"tags":[],"content":"","sig":""}""")))
        assertNull(NostrEvent.fromJson(Json.parse("""{"id":"x","pubkey":"y","created_at":1,"kind":1,"tags":[[1]],"content":"","sig":""}""")))
        assertNull(NostrEvent.fromJson("not an object"))
    }

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun setString(key: String, value: String) { map[key] = value }
        override fun getBool(key: String, default: Boolean) = map[key]?.toBooleanStrictOrNull() ?: default
        override fun setBool(key: String, value: Boolean) { map[key] = value.toString() }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `announces once, then only when something changes`() {
        val store = NostrStore(MapStore())
        val peer = "peer.onion"
        val first = store.announcementDue(peer)
        assertNotNull(first)
        assertFalse(first!!.isRevocation)
        assertEquals(NostrStore.DEFAULT_RELAYS.size, first.relays.size)
        store.recordAnnounced(peer, first)
        assertNull(store.announcementDue(peer))

        store.setOwnRelays(listOf("wss://relay.example.com"))
        val changed = store.announcementDue(peer)!!
        assertEquals(listOf(RelayUrl.parse("wss://relay.example.com")), changed.relays)
        assertTrue(changed.secret.contentEquals(first.secret)) // secret is stable across relay changes
        store.recordAnnounced(peer, changed)

        store.setEnabled(false)
        val revocation = store.announcementDue(peer)!!
        assertTrue(revocation.isRevocation)
        store.recordAnnounced(peer, revocation)
        assertNull(store.announcementDue(peer))
    }

    @Test
    fun `never withdraws from someone who was never told`() {
        val store = NostrStore(MapStore())
        store.setEnabled(false)
        assertNull(store.announcementDue("peer.onion"))
    }

    @Test
    fun `outbox validation`() {
        val store = NostrStore(MapStore())
        val secret = ByteArray(32) { 1 }
        assertFalse(store.recordOutbox("p", ByteArray(5), listOf("wss://relay.example.com")))
        assertFalse(store.recordOutbox("p", secret, listOf("ws://cleartext.example.com", "garbage")))
        assertNull(store.outbox("p"))
        assertTrue(store.recordOutbox("p", secret, listOf("wss://relay.example.com", "garbage")))
        val outbox = store.outbox("p")!!
        assertTrue(outbox.secret.contentEquals(secret))
        assertEquals(1, outbox.relays.size)
        assertTrue(store.recordOutbox("p", ByteArray(0), emptyList()))
        assertNull(store.outbox("p"))
    }

    @Test
    fun `directory lists issued mailboxes and respects the toggle`() {
        val store = NostrStore(MapStore())
        val a = store.announcementDue("a.onion")!!.secret
        store.announcementDue("b.onion")
        assertTrue(a.contentEquals(store.existingInboxSecret("a.onion"))) // stable, not regenerated
        val directory = StoreMailboxDirectory(store)
        assertEquals(listOf("a.onion", "b.onion"), directory.inboundMailboxes().map { it.peer })
        store.forget("b.onion")
        assertEquals(listOf("a.onion"), directory.inboundMailboxes().map { it.peer })
        store.setEnabled(false)
        assertTrue(directory.inboundMailboxes().isEmpty())
        assertTrue(directory.ownRelays().isEmpty())
    }
}
