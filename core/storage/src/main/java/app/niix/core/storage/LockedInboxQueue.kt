package app.niix.core.storage

import android.content.Context
import java.io.File

/**
 * Holds messages that arrive while the app is locked, until it is unlocked.
 *
 * Decrypting an incoming message needs the Signal session store, which lives inside the
 * SQLCipher database -- so while the app is locked and that database is closed, a message
 * genuinely cannot be processed. Previously the connection simply failed, the sender treated it
 * as undelivered, and the message arrived only after an unlock plus a retry or a relay round
 * trip. Nothing was lost, but nothing arrived either.
 *
 * Queueing lets the device accept the message immediately and process it on unlock, without ever
 * opening the database while locked.
 *
 * Two things about how it is stored matter:
 *
 * The queued bytes are already end-to-end encrypted -- this is the Signal ciphertext exactly as
 * it came off the wire, and nothing here can read it. What would otherwise be exposed is
 * metadata: who sent it and when. So each entry is wrapped again with a hardware-backed keystore
 * key. That key is usable while the app is locked (it requires the *device* to be unlocked, not
 * the app), which is precisely what makes this possible without weakening at-rest protection.
 *
 * The queue is capped. An attacker who can reach this onion address could otherwise fill the
 * disk with entries that are never processed because the user never unlocks.
 */
class LockedInboxQueue internal constructor(
    private val appContext: Context,
    private val keystore: KeystoreKeyManager,
) {

    private fun dir(): File = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** Stores one inbound message. Returns false if the queue is full or the write fails, so the
     * caller can fall back to refusing the connection rather than silently dropping it. */
    fun enqueue(senderOnion: String, ciphertext: ByteArray, viaRelay: Boolean): Boolean {
        return runCatching {
            val directory = dir()
            val files = directory.listFiles().orEmpty()
            if (files.size >= MAX_ENTRIES) return false
            if (ciphertext.size > MAX_ENTRY_BYTES) return false

            // An entry count alone is a weak bound: 500 entries at the per-entry maximum is
            // still 2GB. Cap the aggregate too, so the worst case is a fixed amount of disk
            // rather than a number that happens to look small.
            val currentBytes = files.sumOf { it.length() }
            if (currentBytes + ciphertext.size > MAX_TOTAL_BYTES) return false

            // And cap per sender, so one peer cannot consume the whole queue and crowd out
            // everyone else's messages. The sender is not readable from the stored files (they
            // are wrapped), so this is tracked by a per-sender counter file whose name is a hash
            // of the address -- which keeps the address itself off the disk.
            val senderTag = senderHash(senderOnion)
            val senderCount = files.count { it.name.startsWith(senderTag) }
            if (senderCount >= MAX_ENTRIES_PER_SENDER) return false

            // sender length | sender | flag | ciphertext, all wrapped together so the sender's
            // address is protected as well as the payload.
            val senderBytes = senderOnion.toByteArray(Charsets.UTF_8)
            val payload = java.io.ByteArrayOutputStream().apply {
                java.io.DataOutputStream(this).use { out ->
                    out.writeInt(senderBytes.size)
                    out.write(senderBytes)
                    out.writeBoolean(viaRelay)
                    out.writeInt(ciphertext.size)
                    out.write(ciphertext)
                }
            }.toByteArray()

            val wrapped = keystore.wrap(payload).serialize()
            // Written to a temporary name and renamed, so a partially written entry is never
            // picked up as though it were complete.
            val target = File(directory, "$senderTag-${System.currentTimeMillis()}-${counter++}.q")
            val temp = File(directory, target.name + ".part")
            temp.writeBytes(wrapped)
            temp.renameTo(target)
        }.getOrDefault(false)
    }

    /** Everything queued, oldest first, decrypted. Entries that cannot be read are discarded
     * rather than retried forever -- an unreadable entry is not going to become readable. */
    fun drain(): List<Entry> {
        val directory = dir()
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".q") }
            ?.sortedBy { it.name }
            ?: return emptyList()

        val entries = mutableListOf<Entry>()
        for (file in files) {
            runCatching {
                val payload = keystore.unwrap(WrappedBytes.deserialize(file.readBytes()))
                java.io.DataInputStream(payload.inputStream()).use { input ->
                    val senderLen = input.readInt()
                    require(senderLen in 1..512) { "bad sender length" }
                    val senderBytes = ByteArray(senderLen).also { input.readFully(it) }
                    val viaRelay = input.readBoolean()
                    val size = input.readInt()
                    require(size in 0..MAX_ENTRY_BYTES) { "bad ciphertext length" }
                    val ciphertext = ByteArray(size).also { input.readFully(it) }
                    entries.add(Entry(String(senderBytes, Charsets.UTF_8), ciphertext, viaRelay))
                }
            }
            runCatching { file.delete() }
        }
        return entries
    }

    fun clear() {
        runCatching { dir().deleteRecursively() }
    }

    fun size(): Int = runCatching { dir().listFiles()?.count { it.name.endsWith(".q") } ?: 0 }.getOrDefault(0)

    /** Short hash of a sender address, used only as a filename prefix for per-sender counting.
     * Hashed rather than used directly so the queue directory does not list who has been
     * messaging this device. */
    private fun senderHash(senderOnion: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(senderOnion.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    data class Entry(val senderOnion: String, val ciphertext: ByteArray, val viaRelay: Boolean)

    private companion object {
        const val DIR_NAME = "locked-inbox"

        /** Bounded so a peer cannot fill the disk while the user is away from the device. */
        const val MAX_ENTRIES = 500
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        /** Aggregate ceiling. Without this, the entry count alone permits 500 x 4MB = 2GB. */
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        /** Per-sender ceiling, so one peer cannot occupy the entire queue. */
        const val MAX_ENTRIES_PER_SENDER = 50

        @Volatile
        var counter = 0
    }
}
