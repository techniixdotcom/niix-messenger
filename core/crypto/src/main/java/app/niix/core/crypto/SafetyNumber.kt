package app.niix.core.crypto

import java.security.MessageDigest

object SafetyNumber {

    private const val ITERATIONS = 5200
    private const val FINGERPRINT_BYTES = 30
    private const val CHUNK_BYTES = 5
    private val VERSION = byteArrayOf(0x00, 0x00)

    fun compute(
        localIdentityKey: ByteArray,
        localStableId: String,
        remoteIdentityKey: ByteArray,
        remoteStableId: String,
    ): String {
        val local = fingerprint(localIdentityKey, localStableId.toByteArray(Charsets.UTF_8))
        val remote = fingerprint(remoteIdentityKey, remoteStableId.toByteArray(Charsets.UTF_8))
        val ordered = if (localStableId <= remoteStableId) local to remote else remote to local
        return display(ordered.first) + display(ordered.second)
    }

    fun formatted(safetyNumber: String): String =
        safetyNumber.chunked(5).joinToString(" ")

    private fun fingerprint(identityKey: ByteArray, stableId: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        var hash = VERSION + identityKey + stableId
        repeat(ITERATIONS) {
            digest.reset()
            digest.update(hash)
            digest.update(identityKey)
            hash = digest.digest()
        }
        return hash.copyOf(FINGERPRINT_BYTES)
    }

    private fun display(fingerprint: ByteArray): String {
        val builder = StringBuilder()
        var offset = 0
        while (offset < fingerprint.size) {
            builder.append(encodeChunk(fingerprint, offset))
            offset += CHUNK_BYTES
        }
        return builder.toString()
    }

    private fun encodeChunk(data: ByteArray, offset: Int): String {
        var value = 0L
        for (i in 0 until CHUNK_BYTES) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return (value % 100000L).toString().padStart(5, '0')
    }
}
