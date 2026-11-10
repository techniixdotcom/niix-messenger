package app.niix.core.storage

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Holds messages that arrive while the app is locked.
 *
 * Decrypting needs the Signal session store, which is inside the SQLCipher database, and that's
 * closed while locked. This queue lets us accept the message right away and process it on unlock.
 *
 * The stored bytes are already end-to-end encrypted, but who sent them and when is metadata, so
 * each entry is encrypted again. That has to work while the phone itself is locked, which is when
 * most messages arrive, but reading them back shouldn't. So each entry gets a fresh AES key wrapped
 * with the public half of a keystore RSA key: anyone can encrypt with a public key, and only the
 * keystore can decrypt, and only while the phone is unlocked.
 *
 * On the desktop the private key is kept wrapped by a key from the desktop keyring (see KeyVault),
 * and the public key in a plain file next to it.
 *
 * The queue is capped, or anyone who can reach our onion address could fill the disk while the user
 * never unlocks. Direct deliveries name a sender nobody has checked yet, so one peer posing as many
 * could fill a shared cap; they get only part of it. The rest is kept for envelopes from relays,
 * which come through mailboxes given to known contacts, and a sender refused directly falls back
 * to exactly that.
 */
class LockedInboxQueue internal constructor(private val filesDir: File, private val keyDir: File) {

    private val rng = SecureRandom()
    private var sequence = 0
    private val privateKeyWrapper = KeystoreKeyManager(keyAlias = KEY_ALIAS, unlockedDeviceRequired = true)

    private fun dir(): File = File(filesDir, DIR_NAME).apply { StoragePaths.ensurePrivateDirectory(this) }

    /**
     * Stores one incoming message. False if the queue is full or the write fails, so the caller can
     * refuse the connection instead of silently dropping it. [receivedAt] is kept when an entry is
     * put back after a failed attempt, so its age still counts from when it first arrived.
     */
    @Synchronized
    fun enqueue(senderOnion: String, ciphertext: ByteArray, viaRelay: Boolean, receivedAt: Long = System.currentTimeMillis()): Boolean {
        return runCatching {
            val directory = dir()
            val files = directory.listFiles().orEmpty()
            if (files.size >= MAX_ENTRIES) return false
            if (ciphertext.size > MAX_ENTRY_BYTES) return false
            // A count alone isn't much of a limit (500 entries at the per-entry maximum is 2 GB),
            // so cap the total size too.
            if (files.sumOf { it.length() } + ciphertext.size > MAX_TOTAL_BYTES) return false
            val senderTag = senderHash(senderOnion)
            if (!viaRelay) {
                val direct = files.filterNot { it.name.endsWith(RELAY_SUFFIX) }
                if (direct.size >= MAX_DIRECT_ENTRIES) return false
                if (direct.sumOf { it.length() } + ciphertext.size > MAX_DIRECT_BYTES) return false
                // And per sender, so one peer can't crowd everyone else out. The sender isn't
                // readable from the sealed files, so the file name carries a hash of the address.
                if (files.count { it.name.endsWith("-$senderTag.q") } >= MAX_ENTRIES_PER_SENDER) return false
            }

            val senderBytes = senderOnion.toByteArray(Charsets.UTF_8)
            val payload = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(senderBytes.size)
                    out.write(senderBytes)
                    out.writeBoolean(viaRelay)
                    out.writeInt(ciphertext.size)
                    out.write(ciphertext)
                }
            }.toByteArray()

            // Arrival time first in the name, so sorting by name is arrival order. Written under a
            // temporary name and renamed, so a half-written entry is never read.
            val suffix = if (viaRelay) RELAY_SUFFIX else ".q"
            val target = File(directory, "%013d-%06d-%s%s".format(receivedAt, sequence++ % 1_000_000, senderTag, suffix))
            val temp = File(directory, target.name + ".part")
            temp.writeBytes(seal(payload))
            temp.renameTo(target)
        }.getOrDefault(false)
    }

    /**
     * Everything queued, oldest first, decrypted. Entries that are damaged are thrown away, since
     * they won't become readable later.
     *
     * Opening needs the keyring, and these messages were already acknowledged, so nothing else
     * will bring them back. An entry that can't be opened because the keyring is unavailable is kept
     * for the next try rather than deleted, up to [MAX_UNOPENED_AGE_MILLIS].
     */
    @Synchronized
    fun drain(): List<Entry> {
        val files = dir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".q") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        val entries = mutableListOf<Entry>()
        for (file in files) {
            val plain = try {
                open(file.readBytes())
            } catch (e: Exception) {
                if (isDamaged(e) || System.currentTimeMillis() - receivedAtOf(file) > MAX_UNOPENED_AGE_MILLIS) {
                    runCatching { file.delete() }
                    continue
                }
                // The keyring refused: most likely it's locked or not running. Try again later.
                break
            }
            runCatching {
                DataInputStream(plain.inputStream()).use { input ->
                    val senderLen = input.readInt()
                    require(senderLen in 1..512) { "bad sender length" }
                    val senderBytes = ByteArray(senderLen).also { input.readFully(it) }
                    val viaRelay = input.readBoolean()
                    val size = input.readInt()
                    require(size in 0..MAX_ENTRY_BYTES) { "bad ciphertext length" }
                    val ciphertext = ByteArray(size).also { input.readFully(it) }
                    entries.add(Entry(String(senderBytes, Charsets.UTF_8), ciphertext, viaRelay, receivedAtOf(file)))
                }
            }
            runCatching { file.delete() }
        }
        return entries
    }

    fun isEmpty(): Boolean = dir().list { _, name -> name.endsWith(".q") }.isNullOrEmpty()

    // Older builds put the sender tag first; only this format leads with the 13-digit time.
    private fun receivedAtOf(file: File): Long =
        file.name.substringBefore('-').takeIf { it.length == 13 }?.toLongOrNull() ?: file.lastModified()

    /** Whether opening failed because the entry itself is bad, as opposed to the keystore saying no. */
    private fun isDamaged(e: Exception): Boolean =
        e is javax.crypto.AEADBadTagException || e is java.io.IOException || e is IllegalArgumentException

    /** Creates the key if it doesn't exist yet. Called while unlocked, so the first message doesn't pay for it. */
    fun prepare() {
        runCatching { publicKey() }
    }

    /** Deletes every entry and both keys. */
    @Synchronized
    fun destroy() {
        runCatching { dir().deleteRecursively() }
        runCatching { publicKeyFile().delete() }
        runCatching { privateKeyFile().delete() }
        runCatching { privateKeyWrapper.clear() }
    }

    private fun seal(plaintext: ByteArray): ByteArray {
        val aesKey = ByteArray(32).also(rng::nextBytes)
        try {
            val wrappedKey = Cipher.getInstance(RSA_TRANSFORMATION)
                .apply { init(Cipher.ENCRYPT_MODE, publicKey(), OAEP) }
                .doFinal(aesKey)
            val gcm = Cipher.getInstance(AES_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES")) }
            gcm.updateAAD(wrappedKey)
            val body = gcm.doFinal(plaintext)
            return ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeByte(FORMAT_SEALED)
                    out.writeShort(wrappedKey.size)
                    out.write(wrappedKey)
                    out.writeByte(gcm.iv.size)
                    out.write(gcm.iv)
                    out.write(body)
                }
            }.toByteArray()
        } finally {
            aesKey.fill(0)
        }
    }

    private fun open(blob: ByteArray): ByteArray {
        if (blob.isEmpty() || blob[0].toInt() != FORMAT_SEALED) throw IllegalArgumentException("Unknown entry format")
        val input = DataInputStream(blob.inputStream())
        input.readByte()
        val wrappedKey = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val body = input.readBytes()
        val privateKey = privateKey()
        val aesKey = Cipher.getInstance(RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP) }.doFinal(wrappedKey)
        try {
            val gcm = Cipher.getInstance(AES_TRANSFORMATION)
            gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            gcm.updateAAD(wrappedKey)
            return gcm.doFinal(body)
        } finally {
            aesKey.fill(0)
        }
    }

    /**
     * The public key, made with its private half the first time. Encrypting needs only this, so it
     * never touches the keyring.
     */
    @Synchronized
    private fun publicKey(): PublicKey {
        val file = publicKeyFile()
        if (file.isFile && privateKeyFile().isFile) {
            return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(file.readBytes()))
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSAKeyGenParameterSpec(RSA_KEY_BITS, RSAKeyGenParameterSpec.F4), rng)
        }.generateKeyPair()
        val encodedPrivate = pair.private.encoded
        try {
            writePrivately(privateKeyFile(), privateKeyWrapper.wrap(encodedPrivate).serialize())
        } finally {
            encodedPrivate.fill(0)
        }
        writePrivately(file, pair.public.encoded)
        return pair.public
    }

    private fun privateKey(): PrivateKey {
        val file = privateKeyFile()
        if (!file.isFile) error("Locked inbox key is missing")
        val encoded = privateKeyWrapper.unwrap(WrappedBytes.deserialize(file.readBytes()))
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        } finally {
            encoded.fill(0)
        }
    }

    private fun publicKeyFile(): File = File(keyDir, PUBLIC_KEY_FILE)

    private fun privateKeyFile(): File = File(keyDir, PRIVATE_KEY_FILE)

    private fun writePrivately(target: File, data: ByteArray) {
        StoragePaths.ensurePrivateDirectory(keyDir)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        StoragePaths.makePrivate(tmp)
        java.io.FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Unable to persist locked inbox key" }
        }
    }

    private fun senderHash(senderOnion: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(senderOnion.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    data class Entry(val senderOnion: String, val ciphertext: ByteArray, val viaRelay: Boolean, val receivedAt: Long)

    private companion object {
        const val DIR_NAME = "locked-inbox"
        const val KEY_ALIAS = "niix_locked_inbox_rsa"
        const val PUBLIC_KEY_FILE = "inbox.pub"
        const val PRIVATE_KEY_FILE = "inbox.key"

        const val FORMAT_SEALED = 2
        const val RSA_KEY_BITS = 3072
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        /** SHA-256 for both the hash and MGF1: there is no keystore here that needs SHA-1. */
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

        /** Bounded so a peer can't fill the disk while the user is away. */
        const val MAX_ENTRIES = 500
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        /** Total size limit. The count alone would allow 500 x 4 MB = 2 GB. */
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        /** Per-sender limit, so one peer can't take the whole queue. */
        const val MAX_ENTRIES_PER_SENDER = 50

        /** What direct deliveries may take of the queue; the rest is for envelopes from relays. */
        const val MAX_DIRECT_ENTRIES = 300
        const val MAX_DIRECT_BYTES = 40L * 1024 * 1024

        /** Marks an envelope that came through a relay, so it counts against the relay share. */
        const val RELAY_SUFFIX = "-r.q"

        /** How long an entry the keystore won't open is kept and retried. */
        const val MAX_UNOPENED_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
