package app.niix.core.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

object RelayProtocol {

    const val FRAME_RELAY_STORE = 6
    const val FRAME_RELAY_FETCH = 7
    const val FRAME_RELAY_FETCH_RESPONSE = 8
    const val FRAME_RELAY_DELETE_RECEIPT = 9
    const val FRAME_RELAY_ANNOUNCE = 10
    const val FRAME_RELAY_REJECT = 11
    const val FRAME_RELAY_FIND_NODE = 12
    const val FRAME_RELAY_FIND_NODE_RESPONSE = 13

    const val FRAME_ACK = 4

    const val MAX_RELAY_ENVELOPE_BYTES = 8 * 1024L

    const val MAX_RELAY_ENVELOPES_PER_HASH = 50

    const val MAX_RELAY_TTL_MILLIS = 6L * 60 * 60 * 1000

    const val RELAY_FETCH_PROOF_WINDOW_MS = 2L * 60 * 1000

    const val DEFAULT_MAX_TOTAL_RELAY_BYTES = 50L * 1024 * 1024
    const val DEFAULT_MAX_STORES_PER_SENDER_PER_HOUR = 20
    const val RATE_LIMIT_WINDOW_MILLIS = 60L * 60 * 1000

    const val GRANT_VALIDITY_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val GRANT_REISSUE_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

    const val KADEMLIA_K = 8

    const val KADEMLIA_ALPHA = 3

    const val LOOKUP_K = 5

    const val NODE_ID_BYTES = 32

    const val MAX_IDENTITY_KEY_BYTES = 64
    const val MAX_SIGNATURE_BYTES = 128
    const val MAX_ONION_BYTES = 256
    const val MAX_LOOKUP_RESULTS = 64
    const val MAX_ENVELOPE_HASH_BYTES = 64

    const val RPC_TIMEOUT_MILLIS = 20_000L

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun nodeId(identityKeyBytes: ByteArray): ByteArray = sha256(identityKeyBytes)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun Long.toBigEndianBytes(): ByteArray {
        val out = ByteArrayOutputStream(8)
        DataOutputStream(out).writeLong(this)
        return out.toByteArray()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun xorDistanceComparator(target: ByteArray): Comparator<NodeInfo> = Comparator { a, b ->
        val da = xor(a.nodeId, target)
        val db = xor(b.nodeId, target)
        for (i in da.indices) {
            val cmp = (da[i].toInt() and 0xFF).compareTo(db[i].toInt() and 0xFF)
            if (cmp != 0) return@Comparator cmp
        }
        0
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    fun writeBlock(s: DataOutputStream, data: ByteArray) {
        s.writeInt(data.size)
        s.write(data)
    }

    fun readBlock(s: DataInputStream, maxLen: Int): ByteArray {
        val length = s.readInt()
        require(length in 0..maxLen) { "Invalid block length $length (max $maxLen)" }
        return ByteArray(length).also { s.readFully(it) }
    }

    fun writeOnion(s: DataOutputStream, onion: String) = writeBlock(s, onion.toByteArray(Charsets.UTF_8))

    fun readOnion(s: DataInputStream): String =
        String(readBlock(s, MAX_ONION_BYTES), Charsets.UTF_8)
}
