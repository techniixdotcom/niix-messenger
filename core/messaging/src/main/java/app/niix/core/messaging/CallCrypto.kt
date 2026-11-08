package app.niix.core.messaging

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The keys of one call.
 *
 * Each side contributes 32 random bytes: the caller in its CallOffer, the callee in its CallAnswer.
 * Both travel only inside the Signal session (PQXDH and the Triple Ratchet, post-quantum included),
 * so nobody who can't read that session can work out these keys, now or with a quantum computer
 * later. Everything below is symmetric: HKDF-SHA256 and AES-256-GCM, neither of which a quantum
 * computer meaningfully weakens at these sizes.
 *
 * The secrets are fresh for every call and the keys are dropped when it ends, so a call can't be
 * decrypted from anything left on either phone afterwards.
 */
class CallKeys private constructor(
    /** Caller to callee. */
    val callerToCallee: ByteArray,
    /** Callee to caller. */
    val calleeToCaller: ByteArray,
    /** Shown by the caller when it opens the media connection. */
    val callerProof: ByteArray,
    /** Shown by the callee in reply, before any audio flows. */
    val calleeProof: ByteArray,
    /**
     * Eight digits both phones show during the call. Read aloud and matching, they confirm both
     * phones hold the same keys, so nobody is relaying the call in between. Only as strong as the
     * eight digits; the real protection is the Signal session the secrets came through.
     */
    val safetyCode: String,
) {

    fun wipe() {
        callerToCallee.fill(0)
        calleeToCaller.fill(0)
        callerProof.fill(0)
        calleeProof.fill(0)
    }

    companion object {
        private val SALT = "niix-call-v1".toByteArray(Charsets.UTF_8)
        const val KEY_BYTES = 32
        const val PROOF_BYTES = 32

        /**
         * Derives the call's keys. [callerAccount] and [calleeAccount] are the two accounts' addresses
         * and [callId] the offer's id, all bound in, so keys for one call never fit another.
         */
        fun derive(
            offerSecret: ByteArray,
            answerSecret: ByteArray,
            callId: String,
            callerAccount: String,
            calleeAccount: String,
        ): CallKeys {
            require(offerSecret.size == WireCodec.CALL_SECRET_BYTES) { "Invalid offer secret" }
            require(answerSecret.size == WireCodec.CALL_SECRET_BYTES) { "Invalid answer secret" }
            require(!MessageDigest.isEqual(offerSecret, answerSecret)) { "Both halves of a call key are the same" }
            val ikm = offerSecret + answerSecret
            val context = ByteArrayOutputStream().apply {
                for (part in listOf(callId, callerAccount, calleeAccount)) {
                    val bytes = part.toByteArray(Charsets.UTF_8)
                    write(bytes.size ushr 8)
                    write(bytes.size and 0xff)
                    write(bytes)
                }
            }.toByteArray()
            val prk = Hkdf.extract(SALT, ikm)
            ikm.fill(0)
            try {
                return CallKeys(
                    callerToCallee = Hkdf.expand(prk, label("caller-to-callee", context), KEY_BYTES),
                    calleeToCaller = Hkdf.expand(prk, label("callee-to-caller", context), KEY_BYTES),
                    callerProof = Hkdf.expand(prk, label("caller-proof", context), PROOF_BYTES),
                    calleeProof = Hkdf.expand(prk, label("callee-proof", context), PROOF_BYTES),
                    safetyCode = safetyCode(Hkdf.expand(prk, label("safety-code", context), 8)),
                )
            } finally {
                prk.fill(0)
            }
        }

        private fun safetyCode(bytes: ByteArray): String {
            var value = 0L
            for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
            bytes.fill(0)
            // 64 random bits reduced to 8 digits: the bias is far below anything noticeable.
            val digits = java.lang.Long.remainderUnsigned(value, 100_000_000L).toString().padStart(8, '0')
            return digits.substring(0, 4) + " " + digits.substring(4)
        }

        private fun label(name: String, context: ByteArray): ByteArray =
            name.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + context
    }
}

/** HKDF-SHA256 (RFC 5869). */
internal object Hkdf {
    private const val HASH_BYTES = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_BYTES) { "Invalid HKDF length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        previous.fill(0)
        return out
    }
}

/**
 * One direction of a call's audio: AES-256-GCM with a counter for the nonce.
 *
 * The counter is never sent. Both ends count the records they've sealed or opened, and the
 * connection is a single ordered stream, so a record that was dropped, repeated, reordered or
 * altered fails to open and ends the call, rather than being played out of place. Each direction
 * has its own key, so the two counters can't produce the same nonce under the same key.
 */
class MediaCipher(key: ByteArray) {

    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    init {
        require(key.size == CallKeys.KEY_BYTES) { "Invalid media key" }
    }

    fun seal(plaintext: ByteArray): ByteArray = synchronized(this) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nextNonce()))
        cipher.doFinal(plaintext)
    }

    /** Throws if [sealed] isn't the next record from the other side, untouched. */
    @Throws(GeneralSecurityException::class)
    fun open(sealed: ByteArray): ByteArray = synchronized(this) {
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nextNonce()))
        cipher.doFinal(sealed)
    }

    private fun nextNonce(): ByteArray {
        check(counter < MAX_RECORDS) { "Too many records under one key" }
        val nonce = ByteArray(NONCE_BYTES)
        var value = counter++
        for (i in NONCE_BYTES - 1 downTo NONCE_BYTES - 8) {
            nonce[i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return nonce
    }

    companion object {
        const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val NONCE_BYTES = 12

        /** Far beyond any real call (a record every 60 ms for a year is about 5e8). */
        private const val MAX_RECORDS = 1L shl 40
    }
}

/**
 * What goes inside each sealed record.
 *
 * Every record from one side of a call is the same size, padded, and they're sent at a steady rate
 * whether anyone is speaking or not, muted included. Variable-sized voice packets give away a
 * surprising amount about what's being said, and gaps give away when; fixed-size records at a
 * fixed rate give away neither, only that a call is going on.
 */
object MediaRecord {
    const val TYPE_AUDIO = 1
    const val TYPE_HANGUP = 2

    /** The most voice packets one record carries. Normally three (60 ms). */
    const val MAX_PACKETS = 4

    /** How long one record covers. */
    const val RECORD_MILLIS = 60L

    private const val HEADER_BYTES = 3

    /** Plaintext size of every record for [codec]. */
    fun sizeFor(codec: Int): Int = when (codec) {
        CallCodecs.OPUS -> 320
        CallCodecs.PCMU -> 656
        else -> throw IllegalArgumentException("Unknown codec $codec")
    }

    /** Every size a sealed record may have on the wire. */
    val SEALED_SIZES: Set<Int> = CallCodecs.ALL.map { sizeFor(it) + MediaCipher.TAG_BYTES }.toSet()

    data class Decoded(val type: Int, val codec: Int, val packets: List<ByteArray>)

    /**
     * Builds an audio record. Packets that don't fit are left out, oldest first kept: a record that
     * overflows only happens when the encoder briefly runs over its bitrate, and dropping 20 ms
     * then is better than growing the record and giving away that something loud was said.
     */
    fun audio(codec: Int, packets: List<ByteArray>): ByteArray {
        val size = sizeFor(codec)
        val out = ByteArray(size)
        out[0] = TYPE_AUDIO.toByte()
        out[1] = codec.toByte()
        var offset = HEADER_BYTES
        var count = 0
        for (packet in packets) {
            if (count == MAX_PACKETS) break
            if (packet.isEmpty() || packet.size > 0xffff) continue
            if (offset + 2 + packet.size > size) continue
            out[offset] = (packet.size ushr 8).toByte()
            out[offset + 1] = packet.size.toByte()
            System.arraycopy(packet, 0, out, offset + 2, packet.size)
            offset += 2 + packet.size
            count++
        }
        out[2] = count.toByte()
        return out
    }

    /** A hangup, padded to the same size as audio so it doesn't stand out. */
    fun hangup(codec: Int): ByteArray = ByteArray(sizeFor(codec)).also {
        it[0] = TYPE_HANGUP.toByte()
        it[1] = codec.toByte()
    }

    /** Parses an opened record. Throws on anything malformed. */
    fun decode(plaintext: ByteArray): Decoded {
        require(plaintext.size >= HEADER_BYTES) { "Record too short" }
        val type = plaintext[0].toInt() and 0xff
        val codec = plaintext[1].toInt() and 0xff
        require(codec in CallCodecs.ALL) { "Unknown codec $codec" }
        require(plaintext.size == sizeFor(codec)) { "Record size doesn't match its codec" }
        return when (type) {
            TYPE_HANGUP -> Decoded(type, codec, emptyList())
            TYPE_AUDIO -> {
                val count = plaintext[2].toInt() and 0xff
                require(count <= MAX_PACKETS) { "Too many packets" }
                var offset = HEADER_BYTES
                val packets = ArrayList<ByteArray>(count)
                repeat(count) {
                    require(offset + 2 <= plaintext.size) { "Truncated packet length" }
                    val length = ((plaintext[offset].toInt() and 0xff) shl 8) or (plaintext[offset + 1].toInt() and 0xff)
                    require(length > 0 && offset + 2 + length <= plaintext.size) { "Packet overruns record" }
                    packets.add(plaintext.copyOfRange(offset + 2, offset + 2 + length))
                    offset += 2 + length
                }
                Decoded(type, codec, packets)
            }
            else -> throw IllegalArgumentException("Unknown record type $type")
        }
    }
}
