package app.niix.core.nostr

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The layer between a Signal ciphertext and the relay.
 *
 * Each contact gets a random 32-byte secret from the recipient, sent inside the Signal session.
 * Every week (epoch) it yields an AES-GCM key and picks one of [BUCKETS] buckets. The relay files
 * the event under the bucket's tag, which is the same for every mailbox that lands in that bucket
 * that week, whoever's it is. So a relay can't tell one mailbox from another or count the traffic
 * between two people; it only sees which buckets someone listens to, and [decoyTags] pads those
 * out so that doesn't count their contacts either. The recipient tries the keys of its own
 * mailboxes in the bucket, and the one that opens the envelope says who sent it. Signal decryption
 * still authenticates them afterwards.
 */
object MailboxCrypto {

    const val SECRET_BYTES = 32
    const val EPOCH_SECONDS = 7L * 24 * 60 * 60

    /**
     * Fewer buckets mix more people together; more mean less traffic that isn't ours to download.
     * Changing this changes every tag, so it's part of the protocol.
     */
    const val BUCKETS = 1024

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val SALT = "niix-nostr-mailbox-v1".toByteArray()
    private val BUCKET_LABEL = "niix-nostr-bucket-v1".toByteArray()

    fun epochOf(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, EPOCH_SECONDS)

    fun newSecret(rng: SecureRandom): ByteArray = ByteArray(SECRET_BYTES).also(rng::nextBytes)

    /** The tag [secret]'s envelopes carry in [epoch]: its bucket's. */
    fun tag(secret: ByteArray, epoch: Long): String = bucketTag(epoch, bucketOf(derive(secret, "bucket", epoch)))

    /** A bucket's tag, the same for everyone: 64 hex chars. */
    fun bucketTag(epoch: Long, bucket: Int): String {
        require(bucket in 0 until BUCKETS) { "No such bucket" }
        val input = ByteBuffer.allocate(BUCKET_LABEL.size + 10).put(BUCKET_LABEL).putLong(epoch).putShort(bucket.toShort())
        return Hex.encode(MessageDigest.getInstance("SHA-256").digest(input.array()))
    }

    /**
     * [count] tags of other buckets to listen to as well, none of them in [exclude]. They come from
     * [seed], a secret of this device, so they stay the same all epoch: decoys that changed from
     * one connection to the next would give the real ones away.
     */
    fun decoyTags(seed: ByteArray, epoch: Long, count: Int, exclude: Set<String>): List<String> {
        val decoys = LinkedHashSet<String>()
        var i = 0
        // Bounded in case nearly every bucket is excluded, which no real account comes close to.
        while (decoys.size < count && i < count * 8 + 64) {
            val tag = bucketTag(epoch, bucketOf(derive(seed, "decoy$i", epoch)))
            if (tag !in exclude) decoys.add(tag)
            i++
        }
        return decoys.toList()
    }

    fun key(secret: ByteArray, epoch: Long): ByteArray = derive(secret, "key", epoch)

    fun seal(secret: ByteArray, epoch: Long, plaintext: ByteArray, rng: SecureRandom): String {
        val key = key(secret, epoch)
        try {
            val nonce = ByteArray(NONCE_BYTES).also(rng::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(tag(secret, epoch)))
            return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext))
        } finally {
            key.fill(0)
        }
    }

    /** Null if malformed or not sealed with [key]. */
    fun open(key: ByteArray, tag: String, content: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(content)
        if (raw.size <= NONCE_BYTES + TAG_BITS / 8) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
        cipher.updateAAD(aad(tag))
        cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
    }.getOrNull()

    private fun aad(tag: String): ByteArray = SALT + tag.toByteArray()

    private fun bucketOf(derived: ByteArray): Int {
        val bucket = (((derived[0].toInt() and 0xFF) shl 8) or (derived[1].toInt() and 0xFF)) % BUCKETS
        derived.fill(0)
        return bucket
    }

    // HKDF-SHA256 (RFC 5869) with a single output block.
    private fun derive(secret: ByteArray, label: String, epoch: Long): ByteArray {
        require(secret.size == SECRET_BYTES) { "Mailbox secret must be $SECRET_BYTES bytes" }
        val prk = hmac(SALT, secret)
        try {
            return hmac(prk, label.toByteArray() + ByteBuffer.allocate(8).putLong(epoch).array() + 1.toByte())
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
