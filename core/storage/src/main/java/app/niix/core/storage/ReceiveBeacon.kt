package app.niix.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What receiving needs while the database is closed, kept outside it: the onion key, the Nostr
 * inbox (relays, mailbox secrets, decoy seed) and how far the inbox has been read.
 *
 * With a passcode the database is only open after unlocking in the app, and the app locks a minute
 * after it's left. If Android then stops the app, or the phone restarts, nothing could be received
 * until the app was opened again: not directly, since publishing the onion service needs its key,
 * and not through relays, since the mailbox secrets are in the database. With this copy the
 * service starts again by itself and messages wait, still encrypted, in the locked inbox.
 *
 * The files are encrypted with a keystore key that works while the phone is locked, because that
 * is when this is needed. So unlike the database, they aren't protected by the passcode: someone
 * able to run code as the app could take the onion key and answer on our address, and see when
 * envelopes arrive in our inbox. Messages stay unreadable without the database. "Disconnect when
 * locked" removes the files, and they're written only while it's off.
 */
class ReceiveBeacon internal constructor(private val dir: File) {

    class Inbox(val relays: List<String>, val mailboxes: List<Pair<String, ByteArray>>, val decoySeed: ByteArray)

    class ReadState(val cursor: Long?, val seen: List<String>)

    private val keystore by lazy { KeystoreKeyManager(KEY_ALIAS, unlockedDeviceRequired = false) }

    fun exists(): Boolean = File(dir, ONION_FILE).isFile

    fun updateOnionKey(seed: ByteArray) = write(ONION_FILE, seed)

    fun onionKey(): ByteArray? = read(ONION_FILE)

    fun updateInbox(inbox: Inbox) = write(
        INBOX_FILE,
        encode { s ->
            s.writeShort(inbox.relays.size)
            inbox.relays.forEach(s::writeUTF)
            s.writeShort(inbox.mailboxes.size)
            inbox.mailboxes.forEach { (peer, secret) ->
                s.writeUTF(peer)
                writeBlock(s, secret)
            }
            writeBlock(s, inbox.decoySeed)
        },
    )

    fun inbox(): Inbox? = read(INBOX_FILE)?.let { bytes ->
        decode(bytes) { s ->
            val relays = List(s.readUnsignedShort()) { s.readUTF() }
            val mailboxes = List(s.readUnsignedShort()) { s.readUTF() to readBlock(s) }
            Inbox(relays, mailboxes, readBlock(s))
        }
    }

    fun updateReadState(state: ReadState) = write(
        STATE_FILE,
        encode { s ->
            s.writeLong(state.cursor ?: -1L)
            s.writeShort(state.seen.size)
            state.seen.forEach(s::writeUTF)
        },
    )

    fun readState(): ReadState? = read(STATE_FILE)?.let { bytes ->
        decode(bytes) { s -> ReadState(s.readLong().takeIf { it >= 0 }, List(s.readUnsignedShort()) { s.readUTF() }) }
    }

    fun destroy() {
        listOf(ONION_FILE, INBOX_FILE, STATE_FILE).forEach { File(dir, it).delete() }
        keystore.clear()
    }

    private fun write(name: String, plain: ByteArray) {
        try {
            dir.mkdirs()
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(keystore.wrap(plain).serialize())
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Could not write $name")
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Null if there's no such file or it can't be opened, for instance after the key was lost. */
    private fun read(name: String): ByteArray? = runCatching {
        val file = File(dir, name)
        if (!file.isFile) return null
        keystore.unwrap(WrappedBytes.deserialize(file.readBytes()))
    }.getOrNull()

    private fun encode(body: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(body) }.toByteArray()

    private fun <T> decode(bytes: ByteArray, body: (DataInputStream) -> T): T? = try {
        runCatching { DataInputStream(ByteArrayInputStream(bytes)).use(body) }.getOrNull()
    } finally {
        bytes.fill(0)
    }

    private fun writeBlock(s: DataOutputStream, bytes: ByteArray) {
        s.writeShort(bytes.size)
        s.write(bytes)
    }

    private fun readBlock(s: DataInputStream): ByteArray = ByteArray(s.readUnsignedShort()).also(s::readFully)

    private companion object {
        const val KEY_ALIAS = "niix_receive_beacon"
        const val ONION_FILE = "receive-onion"
        const val INBOX_FILE = "receive-inbox"
        const val STATE_FILE = "receive-state"
    }
}
