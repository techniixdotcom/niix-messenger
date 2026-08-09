package app.niix.core.messaging

/**
 * Pads plaintext to the next size bucket from a fixed set before it goes into libsignal's
 * encrypt() call, and strips that padding back off after decrypt. Without this, ciphertext
 * length on the wire directly reflects plaintext length, letting an observer of Tor circuit
 * traffic sizes fingerprint message content by size even without decrypting anything.
 *
 * Only wraps the small message-envelope path (WireCodec-encoded WireMessages sent through
 * sendWire/handleIncoming) -- attachment blobs go over the separate raw FRAME_ATTACHMENT path
 * and are not padded here.
 */
object MessagePadding {

    /** 256B / 1KB / 4KB / 16KB: covers typical text + small-metadata message sizes. Anything
     * larger rounds up to the next 16KB multiple instead of failing. */
    private val BUCKETS = longArrayOf(256, 1024, 4096, 16384)
    private const val LENGTH_PREFIX_BYTES = 4

    fun pad(data: ByteArray): ByteArray {
        val needed = data.size.toLong() + LENGTH_PREFIX_BYTES
        val bucket = BUCKETS.firstOrNull { it >= needed }
            ?: run {
                val unit = BUCKETS.last()
                ((needed + unit - 1) / unit) * unit
            }
        val out = ByteArray(bucket.toInt())
        // 4-byte big-endian length prefix so the receiver knows how much of the bucket is real
        // content versus padding. The rest of `out` is already zero-filled by ByteArray's
        // default init.
        out[0] = (data.size ushr 24).toByte()
        out[1] = (data.size ushr 16).toByte()
        out[2] = (data.size ushr 8).toByte()
        out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, LENGTH_PREFIX_BYTES, data.size)
        return out
    }

    fun unpad(padded: ByteArray): ByteArray {
        require(padded.size >= LENGTH_PREFIX_BYTES) { "Padded message shorter than the length prefix" }
        val length = ((padded[0].toInt() and 0xFF) shl 24) or
            ((padded[1].toInt() and 0xFF) shl 16) or
            ((padded[2].toInt() and 0xFF) shl 8) or
            (padded[3].toInt() and 0xFF)
        require(length in 0..(padded.size - LENGTH_PREFIX_BYTES)) { "Corrupt or tampered padding length" }
        return padded.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length)
    }
}
