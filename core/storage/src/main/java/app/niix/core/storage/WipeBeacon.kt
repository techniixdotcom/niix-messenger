package app.niix.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What a wipe of the whole account needs, kept outside the database.
 *
 * The devices of an account share a random wipe key. Any of them can erase the others by sending
 * it; this is how the duress passcode, a manual wipe and removing a device reach the rest. It has
 * to work when the database can't be opened: under the duress passcode it never is, and a seized
 * phone is usually locked.
 *
 * So two files. The key and the other devices' addresses, encrypted with a keystore key, for
 * sending; the phone is unlocked whenever someone is typing into the app. And the key's SHA-256,
 * for checking a wipe that arrives, which needs no secret and works while the phone is locked.
 * Both exist on every install, with a random key on a single device, so their presence says
 * nothing about whether there are other devices.
 */
class WipeBeacon internal constructor(private val dir: File) {

    private val keystore by lazy { KeystoreKeyManager(KEY_ALIAS) }
    private val sealedFile get() = File(dir, SEALED_FILE)
    private val checkFile get() = File(dir, CHECK_FILE)

    fun update(key: ByteArray, otherDevices: List<String>) {
        require(key.size == KEY_BYTES) { "Wipe key must be $KEY_BYTES bytes" }
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { s ->
                s.write(key)
                s.writeByte(otherDevices.size)
                otherDevices.forEach(s::writeUTF)
            }
        }.toByteArray()
        writeAtomically(sealedFile, keystore.wrap(plain).serialize())
        writeAtomically(checkFile, sha256(key))
    }

    /** The key and the devices to send it to, or null if there's no beacon or it can't be opened. */
    fun read(): Pair<ByteArray, List<String>>? = runCatching {
        val plain = keystore.unwrap(WrappedBytes.deserialize(sealedFile.readBytes()))
        DataInputStream(ByteArrayInputStream(plain)).use { s ->
            val key = ByteArray(KEY_BYTES).also(s::readFully)
            key to List(s.readUnsignedByte()) { s.readUTF() }
        }
    }.getOrNull()

    /** Whether [key] is this account's wipe key. Constant time, and needs nothing unlocked. */
    fun matches(key: ByteArray): Boolean {
        if (key.size != KEY_BYTES) return false
        val expected = runCatching { checkFile.readBytes() }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, sha256(key))
    }

    fun destroy() {
        sealedFile.delete()
        checkFile.delete()
        keystore.clear()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun writeAtomically(target: File, data: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not write ${target.name}")
        }
    }

    companion object {
        const val KEY_BYTES = 32
        private const val KEY_ALIAS = "niix_wipe_beacon"
        private const val SEALED_FILE = "wipe-beacon"
        private const val CHECK_FILE = "wipe-check"

        fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
    }
}
