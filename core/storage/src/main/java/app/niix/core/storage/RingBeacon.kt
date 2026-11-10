package app.niix.core.storage

import java.io.File
import java.security.MessageDigest

/**
 * What a locked device needs to know that a call it should ring for is coming in: one 32-byte ring
 * key per contact the user allowed calls with, and nothing else. No names, no addresses, nothing
 * that reads messages or answers a call. See the doorbell in CallManager.
 *
 * Like the receive beacon, it's kept outside the passcode-protected database, under a keystore key
 * that works while the device is locked, because that is the only time it's needed. Someone who
 * could run code as the app could take the keys and make a locked device ring; that is all they do.
 * Turning "Ring while locked" off, or "Disconnect when locked" on, removes the file.
 */
class RingBeacon internal constructor(private val dir: File) {

    private val keystore by lazy { KeystoreKeyManager(KEY_ALIAS, unlockedDeviceRequired = false) }

    /** Replaces the stored keys. An empty list removes the file. */
    @Synchronized
    fun update(keys: List<ByteArray>) {
        val valid = keys.filter { it.size == KEY_BYTES }
        if (valid.isEmpty()) {
            File(dir, FILE).delete()
            return
        }
        val plain = ByteArray(valid.size * KEY_BYTES)
        valid.forEachIndexed { i, key -> System.arraycopy(key, 0, plain, i * KEY_BYTES, KEY_BYTES) }
        try {
            dir.mkdirs()
            val target = File(dir, FILE)
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeBytes(keystore.wrap(plain).serialize())
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    error("Could not write the ring beacon")
                }
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Whether [matches] accepts any stored key. False without a beacon, or if it can't be opened. */
    @Synchronized
    fun anyKey(matches: (ByteArray) -> Boolean): Boolean {
        val file = File(dir, FILE)
        if (!file.isFile) return false
        val plain = runCatching { keystore.unwrap(WrappedBytes.deserialize(file.readBytes())) }.getOrNull() ?: return false
        try {
            if (plain.size % KEY_BYTES != 0) return false
            var found = false
            // Every key is tried, whichever matches, so the time taken says nothing about which.
            for (i in 0 until plain.size / KEY_BYTES) {
                val key = plain.copyOfRange(i * KEY_BYTES, (i + 1) * KEY_BYTES)
                if (matches(key)) found = true
                key.fill(0)
            }
            return found
        } finally {
            plain.fill(0)
        }
    }

    fun exists(): Boolean = File(dir, FILE).isFile

    @Synchronized
    fun destroy() {
        File(dir, FILE).delete()
        keystore.clear()
    }

    companion object {
        const val KEY_BYTES = 32
        private const val KEY_ALIAS = "niix_ring_beacon"
        private const val FILE = "r1.bin"

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
    }
}
