package app.niix.core.messaging

import java.io.Closeable

/** Voice codecs a call's records can carry. */
object CallCodecs {
    /** Opus, 16 kHz mono, 20 ms packets. Every Android 10+ phone with a microphone can encode it. */
    const val OPUS = 1

    /** G.711 µ-law, 8 kHz mono, 20 ms packets. Only if a phone can't encode Opus. */
    const val PCMU = 2

    val ALL: Set<Int> = setOf(OPUS, PCMU)
}

/**
 * The phone's side of a call's audio: microphone in, speaker out. The messaging layer moves the
 * packets; the app provides this, since only it can reach Android's audio.
 */
fun interface CallAudio {
    /** Opens the microphone and speaker. Throws if they can't be opened. */
    fun open(): CallAudioSession
}

interface CallAudioSession : Closeable {

    /** The codec [capture] encodes with, one of [CallCodecs]. Fixed for the session. */
    val codec: Int

    /**
     * Blocks until the next [MediaRecord.RECORD_MILLIS] of microphone audio has been captured, and
     * returns it as encoded packets. May return fewer or more packets than the time strictly holds
     * while an encoder catches up. Throws if the microphone fails.
     */
    fun capture(): List<ByteArray>

    /** Queues packets from the other side for playing. Never blocks for long. */
    fun play(codec: Int, packets: List<ByteArray>)

    /** Loudspeaker on or off. */
    fun setSpeaker(on: Boolean)

    /**
     * Microphone off or on. While off, [capture] must still return every
     * [MediaRecord.RECORD_MILLIS], with no packets, because records go out at a steady rate either
     * way; but the microphone itself should be closed, so the phone shows it isn't listening.
     */
    fun setMuted(muted: Boolean) {}
}

/**
 * G.711 µ-law (ITU-T G.711), the fallback codec. Simple enough to need no platform support at all,
 * at the cost of four times Opus's bitrate.
 */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val DECODE_TABLE = ShortArray(256) { decodeOne(it).toShort() }

    fun encode(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray =
        ByteArray(length) { encodeOne(pcm[offset + it].toInt()) }

    fun decode(ulaw: ByteArray): ShortArray = ShortArray(ulaw.size) { DECODE_TABLE[ulaw[it].toInt() and 0xff] }

    fun encodeOne(sample: Int): Byte {
        var pcm = sample
        val sign = if (pcm < 0) 0x80 else 0
        if (pcm < 0) pcm = -pcm
        if (pcm > CLIP) pcm = CLIP
        pcm += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (pcm and mask) == 0) {
            exponent--
            mask = mask ushr 1
        }
        val mantissa = (pcm shr (exponent + 3)) and 0x0f
        return ((sign or (exponent shl 4) or mantissa).inv() and 0xff).toByte()
    }

    private fun decodeOne(code: Int): Int {
        val u = code.inv() and 0xff
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0f
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return if (sign != 0) -magnitude else magnitude
    }
}
