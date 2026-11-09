package app.niix.core.nostr

import java.security.MessageDigest
import java.security.SecureRandom

/** A NIP-01 event, limited to what we publish and read. */
internal data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {

    fun toJson(): String =
        "{\"id\":\"$id\",\"pubkey\":\"$pubkey\",\"created_at\":$createdAt,\"kind\":$kind," +
            "\"tags\":${tags.joinToString(",", "[", "]") { Json.stringArray(it) }}," +
            "\"content\":${Json.quote(content)},\"sig\":\"$sig\"}"

    fun tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    companion object {
        private const val MAX_TAGS = 32

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray {
            val serialized = "[0,${Json.quote(pubkey, forId = true)},$createdAt,$kind," +
                "${tags.joinToString(",", "[", "]") { Json.stringArray(it, forId = true) }}," +
                "${Json.quote(content, forId = true)}]"
            return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray())
        }

        /** Signs with a fresh key that is wiped as soon as the signature exists. */
        fun signWithThrowawayKey(
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
            rng: SecureRandom,
        ): NostrEvent {
            val secret = ByteArray(32)
            do rng.nextBytes(secret) while (!Bip340.isValidSecret(secret))
            try {
                val pubkey = Hex.encode(Bip340.publicKey(secret))
                val id = computeId(pubkey, createdAt, kind, tags, content)
                val sig = Bip340.sign(secret, id, ByteArray(32).also(rng::nextBytes))
                return NostrEvent(Hex.encode(id), pubkey, createdAt, kind, tags, content, Hex.encode(sig))
            } finally {
                secret.fill(0)
            }
        }

        /** Null for anything malformed. Relays are untrusted input. */
        fun fromJson(value: Any?): NostrEvent? {
            val map = value as? Map<*, *> ?: return null
            val rawTags = map["tags"] as? List<*> ?: return null
            if (rawTags.size > MAX_TAGS) return null
            val tags = rawTags.map { tag -> (tag as? List<*>)?.map { it as? String ?: return null } ?: return null }
            return NostrEvent(
                id = map["id"] as? String ?: return null,
                pubkey = map["pubkey"] as? String ?: return null,
                createdAt = map["created_at"] as? Long ?: return null,
                kind = (map["kind"] as? Long)?.toInt() ?: return null,
                tags = tags,
                content = map["content"] as? String ?: return null,
                sig = map["sig"] as? String ?: return null,
            )
        }
    }
}

internal object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Not hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Lower-case hex of exactly [bytes] bytes. */
    fun isHex(value: String, bytes: Int): Boolean =
        value.length == bytes * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
