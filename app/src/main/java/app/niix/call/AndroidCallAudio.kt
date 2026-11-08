package app.niix.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import app.niix.core.messaging.CallAudio
import app.niix.core.messaging.CallAudioSession
import app.niix.core.messaging.CallCodecs
import app.niix.core.messaging.G711
import app.niix.core.messaging.MediaRecord
import app.niix.core.model.DiagnosticLog
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's microphone and speaker for calls.
 *
 * Captures 16 kHz mono through the voice-call input (so the phone's own echo cancelling and noise
 * suppression apply) and encodes it with Opus, which every Android 10+ phone with a microphone has
 * to be able to do. If Opus can't be opened, G.711 µ-law, which needs nothing from the phone, is
 * used instead. Plays through the voice-call output, earpiece by default.
 *
 * Nothing is written anywhere: audio exists only in memory, on its way between the microphone and
 * the call, or the call and the speaker.
 */
class AndroidCallAudio(context: Context) : CallAudio {

    private val appContext = context.applicationContext

    override fun open(): CallAudioSession = AndroidCallAudioSession(appContext)
}

private class AndroidCallAudioSession(private val context: Context) : CallAudioSession {

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val previousMode = audioManager.mode
    private val voiceAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(voiceAttributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var recorder: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()
    private var captureRate = 0
    private var encoder: VoiceEncoder? = null
    private var player: Player? = null

    @Volatile private var closed = false
    private var capturePrioritySet = false

    private var chosenCodec = CallCodecs.OPUS
    override val codec: Int get() = chosenCodec

    init {
        try {
            audioManager.requestAudioFocus(focus)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            recorder = openRecorder()
            val opus = runCatching { OpusEncoder() }
                .onFailure { DiagnosticLog.record("call", "Opus encoder unavailable (${it::class.java.simpleName}), using G.711") }
                .getOrNull()
            encoder = opus ?: PcmuEncoder()
            chosenCodec = if (opus != null) CallCodecs.OPUS else CallCodecs.PCMU
            player = Player(voiceAttributes).also { it.start() }
            recorder!!.startRecording()
            if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone didn't start")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** The first rate the microphone accepts, preferring the one Opus is fed at. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission not granted")
        }
        for (rate in CAPTURE_RATES) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val frameBytes = rate / FRAMES_PER_SECOND * 2
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, frameBytes * 10),
                )
            }.getOrNull() ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            captureRate = rate
            attachEffects(record.audioSessionId)
            return record
        }
        throw IOException("No usable microphone configuration")
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()?.let { effects += it }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()?.let { effects += it }
        if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()?.let { effects += it }
        effects.forEach { runCatching { it.enabled = true } }
    }

    override fun capture(): List<ByteArray> {
        if (!capturePrioritySet) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            capturePrioritySet = true
        }
        val record = recorder ?: throw IOException("Microphone closed")
        val enc = encoder ?: throw IOException("Encoder closed")
        val frame = ShortArray(captureRate / FRAMES_PER_SECOND)
        val packets = ArrayList<ByteArray>(FRAMES_PER_RECORD + 1)
        repeat(FRAMES_PER_RECORD) {
            var filled = 0
            while (filled < frame.size) {
                if (closed) throw IOException("Closed")
                val read = record.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                if (read < 0) throw IOException("Microphone read failed ($read)")
                if (read == 0) Thread.sleep(2)
                filled += read
            }
            enc.encode(Resampler.convert(frame, captureRate, enc.sampleRate), packets)
        }
        return packets
    }

    override fun play(codec: Int, packets: List<ByteArray>) {
        player?.enqueue(codec, packets)
    }

    override fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        recorder?.let { record ->
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        recorder = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { encoder?.close() }
        encoder = null
        runCatching { player?.close() }
        player = null
        runCatching { setSpeaker(false) }
        runCatching { audioManager.mode = previousMode }
        runCatching { audioManager.abandonAudioFocusRequest(focus) }
    }

    /**
     * Plays the other side's audio, with a small buffer against the uneven arrival that Tor is
     * prone to. It fills up before playing starts, and if it grows too long (audio arriving in a
     * burst after a stall), the oldest is dropped so the delay doesn't keep growing.
     */
    private class Player(attributes: AudioAttributes) : Closeable {
        private val track: AudioTrack
        private val queue = ArrayDeque<Pair<Int, ByteArray>>()
        private val lock = Any()
        private var opus: OpusDecoder? = null
        private var opusFailures = 0
        @Volatile private var running = true
        private val silence = ShortArray(PLAY_RATE / FRAMES_PER_SECOND)
        private var thread: Thread? = null

        init {
            val minimum = AudioTrack.getMinBufferSize(PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAY_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, PLAY_RATE / FRAMES_PER_SECOND * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IOException("Speaker didn't open")
            }
        }

        fun start() {
            track.play()
            thread = Thread({ loop() }, "niix-call-play").apply {
                isDaemon = true
                start()
            }
        }

        fun enqueue(codec: Int, packets: List<ByteArray>) {
            synchronized(lock) {
                packets.forEach { queue.addLast(codec to it) }
                if (queue.size > MAX_QUEUED) {
                    while (queue.size > TRIM_TO) queue.removeFirst()
                }
            }
        }

        private fun loop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            var buffering = true
            while (running) {
                val next = synchronized(lock) {
                    if (buffering && queue.size >= PREBUFFER) buffering = false
                    if (buffering) null else queue.removeFirstOrNull().also { if (it == null) buffering = true }
                }
                val pcm = next?.let { (codec, packet) -> decode(codec, packet) }
                // Silence while waiting, so the track keeps time and the next audio isn't late.
                val out = if (pcm == null || pcm.isEmpty()) silence else pcm
                val written = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    DiagnosticLog.record("call", "speaker write failed ($written)")
                    Thread.sleep(MILLIS_PER_FRAME)
                }
            }
        }

        private fun decode(codec: Int, packet: ByteArray): ShortArray? = when (codec) {
            CallCodecs.PCMU -> Resampler.convert(G711.decode(packet), PCMU_RATE, PLAY_RATE)
            CallCodecs.OPUS -> decodeOpus(packet)
            else -> null
        }

        private fun decodeOpus(packet: ByteArray): ShortArray? {
            if (opusFailures >= MAX_DECODER_FAILURES) return null
            return try {
                val decoder = opus ?: OpusDecoder().also { opus = it }
                decoder.decode(packet).also { opusFailures = 0 }
            } catch (e: Exception) {
                // A decoder in a bad state is replaced; one that keeps failing is given up on, and
                // the call carries on in silence rather than ending.
                opusFailures++
                DiagnosticLog.record("call", "Opus decode failed (${e::class.java.simpleName})")
                runCatching { opus?.close() }
                opus = null
                null
            }
        }

        override fun close() {
            running = false
            runCatching { track.pause() }
            runCatching { track.flush() }
            thread?.let {
                it.interrupt()
                runCatching { it.join(500) }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { opus?.close() }
            opus = null
        }
    }

    private companion object {
        val CAPTURE_RATES = intArrayOf(16_000, 48_000, 44_100)
        const val FRAMES_PER_SECOND = 50
        const val MILLIS_PER_FRAME = 1000L / FRAMES_PER_SECOND
        val FRAMES_PER_RECORD = (MediaRecord.RECORD_MILLIS / MILLIS_PER_FRAME).toInt()
        const val PLAY_RATE = 48_000
        const val PCMU_RATE = 8_000

        /** In 20 ms packets: start playing at 160 ms buffered, never hold more than 600 ms. */
        const val PREBUFFER = 8
        const val MAX_QUEUED = 30
        const val TRIM_TO = 12
        const val MAX_DECODER_FAILURES = 20
    }
}

private interface VoiceEncoder : Closeable {
    /** The rate it takes PCM at. */
    val sampleRate: Int

    /** Encodes one 20 ms frame, adding whatever packets are ready to [out]. */
    fun encode(pcm: ShortArray, out: MutableList<ByteArray>)
}

private class PcmuEncoder : VoiceEncoder {
    override val sampleRate = 8_000
    override fun encode(pcm: ShortArray, out: MutableList<ByteArray>) {
        out += G711.encode(pcm)
    }
    override fun close() = Unit
}

/** Opus through the phone's own codec, 16 kHz mono at a constant 16 kbit/s where supported. */
private class OpusEncoder : VoiceEncoder {
    override val sampleRate = 16_000
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * 4)
                val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS).encoderCapabilities
                // Constant bitrate keeps packet sizes from following the speech. Records are padded
                // to a fixed size anyway; this just keeps more of them from overflowing.
                if (capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    override fun encode(pcm: ShortArray, out: MutableList<ByteArray>) {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: throw IOException("No encoder input buffer")
            buffer.clear()
            val bytes = pcm.size * 2
            if (buffer.capacity() < bytes) throw IOException("Encoder input buffer too small")
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            codec.queueInputBuffer(index, 0, bytes, presentationUs, 0)
            presentationUs += FRAME_US
        }
        // Otherwise the encoder is behind and this frame is dropped: 20 ms lost beats a delay.
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        out += ByteArray(info.size).also { buffer.get(it) }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else {
                break
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val BITRATE = 16_000
        const val FRAME_SAMPLES = 320
        const val FRAME_US = 20_000L
        const val INPUT_TIMEOUT_US = 10_000L
    }
}

/** Opus through the phone's own codec, decoded to 48 kHz mono. */
private class OpusDecoder : Closeable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private var outputRate = OUTPUT_RATE
    private var outputChannels = 1

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OUTPUT_RATE, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", nanos(0L))
                setByteBuffer("csd-2", nanos(SEEK_PREROLL_NANOS))
            }
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    fun decode(packet: ByteArray): ShortArray {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: throw IOException("No decoder input buffer")
            buffer.clear()
            if (buffer.capacity() < packet.size) throw IOException("Decoder input buffer too small")
            buffer.put(packet)
            codec.queueInputBuffer(index, 0, packet.size, presentationUs, 0)
            presentationUs += 20_000L
        }
        var pcm = ShortArray(0)
        var timeout = TIMEOUT_US
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val samples = ShortArray(info.size / 2)
                        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
                        pcm += samples
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
                else -> break
            }
        }
        return Resampler.convert(Resampler.toMono(pcm, outputChannels), outputRate, OUTPUT_RATE)
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val OUTPUT_RATE = 48_000
        const val TIMEOUT_US = 10_000L
        const val SEEK_PREROLL_NANOS = 80_000_000L

        /** RFC 7845 identification header: one channel, no pre-skip, 16 kHz input, no gain. */
        fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1.toByte())
            put(1.toByte())
            putShort(0.toShort())
            putInt(16_000)
            putShort(0.toShort())
            put(0.toByte())
        }.array()

        fun nanos(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}

/** Sample-rate and channel conversion, good enough for speech. */
private object Resampler {

    fun convert(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        if (fromRate > toRate && fromRate % toRate == 0) {
            // Whole-number downsampling: average each group, which also filters out some of what
            // would otherwise alias.
            val factor = fromRate / toRate
            return ShortArray(input.size / factor) { i ->
                var sum = 0
                for (j in 0 until factor) sum += input[i * factor + j]
                (sum / factor).toShort()
            }
        }
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        val step = fromRate.toDouble() / toRate
        return ShortArray(outLength) { i ->
            val position = i * step
            val index = position.toInt()
            val next = minOf(index + 1, input.size - 1)
            val fraction = position - index
            (input[index] * (1 - fraction) + input[next] * fraction).toInt().toShort()
        }
    }

    fun toMono(input: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return input
        return ShortArray(input.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += input[i * channels + c]
            (sum / channels).toShort()
        }
    }
}
