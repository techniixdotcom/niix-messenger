package app.niix.desktop

import app.niix.core.messaging.CallAudio
import app.niix.core.messaging.CallAudioSession
import app.niix.core.messaging.CallCodecs
import app.niix.core.messaging.MediaRecord
import app.niix.core.model.DiagnosticLog
import java.util.concurrent.ConcurrentLinkedDeque
import org.freedesktop.gstreamer.app.AppSink
import org.freedesktop.gstreamer.app.AppSrc
import org.freedesktop.gstreamer.gst.Bin
import org.freedesktop.gstreamer.gst.Buffer
import org.freedesktop.gstreamer.gst.ClockTime
import org.freedesktop.gstreamer.gst.Element
import org.freedesktop.gstreamer.gst.ElementFactory
import org.freedesktop.gstreamer.gst.Gst
import org.freedesktop.gstreamer.gst.MessageType
import org.freedesktop.gstreamer.gst.State
import org.javagi.base.Out

/** GStreamer is started once, by whichever of calls, voice messages and the ringer needs it first. */
object GstSupport {
    @Volatile
    private var initialised = false

    @Synchronized
    fun ensure() {
        if (!initialised) {
            Gst.initCheck(null)
            // Loads the bindings for these, so elements a pipeline hands back come as their type.
            AppSrc.getType()
            AppSink.getType()
            initialised = true
        }
    }

    fun has(vararg factories: String): Boolean = factories.all { ElementFactory.find(it) != null }
}

/**
 * A call's audio on the desktop, through GStreamer: the default microphone encoded as the same
 * Opus the phones send (16 kHz mono, 16 kbit/s, 20 ms packets), and what arrives decoded to the
 * default output. Where the WebRTC audio processing plugin is installed, the microphone goes
 * through its echo canceller, noise suppression and gain control, as a phone's does; without it a
 * headset is the way to keep the other person from hearing themselves.
 */
class DesktopCallAudio : CallAudio {

    override fun open(): CallAudioSession {
        GstSupport.ensure()
        if (!GstSupport.has("opusenc", "opusdec", "appsink", "appsrc")) {
            throw IllegalStateException("GStreamer has no Opus support")
        }
        if (GstSupport.has("webrtcdsp", "webrtcechoprobe")) {
            runCatching { return Session(echoCancel = true) }
                .onFailure { DiagnosticLog.record("call", "echo cancelling could not start (${it.javaClass.simpleName}), going without") }
        }
        return Session(echoCancel = false)
    }

    private class Session(private val echoCancel: Boolean) : CallAudioSession {

        override val codec: Int = CallCodecs.OPUS

        private val playback: Element
        private val playbackSource: AppSrc
        private val capture: Element
        private val captureSink: AppSink

        /** Built only if the other side turns out to send G.711, which only old phones do. */
        private var mulawPlayback: Element? = null
        private var mulawSource: AppSrc? = null

        private val queue = ConcurrentLinkedDeque<Pair<Int, ByteArray>>()

        @Volatile
        private var closed = false

        @Volatile
        private var muted = false

        private var nextCaptureAtNanos = System.nanoTime()

        private val player = Thread(::playLoop, "niix-call-playback").apply { isDaemon = true }

        init {
            val probe = if (echoCancel) "audioresample ! audio/x-raw,format=S16LE,rate=48000,channels=1 ! webrtcechoprobe name=$PROBE ! audioconvert ! " else ""
            playback = Gst.parseLaunch(
                "appsrc name=src format=time is-live=false block=false max-bytes=6000 " +
                    "caps=\"audio/x-opus, channel-mapping-family=(int)0, channels=(int)1, rate=(int)48000\" ! " +
                    "opusdec plc=true ! audioconvert ! $probe" + "audioresample ! autoaudiosink sync=false",
            )
            playbackSource = (playback as Bin).getByName("src") as? AppSrc ?: error("No appsrc")
            val dsp = if (echoCancel) {
                "audio/x-raw,format=S16LE,rate=48000,channels=1 ! webrtcdsp probe=$PROBE echo-cancel=true noise-suppression=true gain-control=true ! audioconvert ! audioresample ! "
            } else {
                ""
            }
            capture = Gst.parseLaunch(
                "autoaudiosrc ! audioconvert ! audioresample ! $dsp" +
                    "audio/x-raw,format=S16LE,rate=16000,channels=1 ! " +
                    "opusenc bitrate=16000 bitrate-type=cbr frame-size=20 audio-type=voice ! " +
                    "appsink name=sink sync=false max-buffers=25 drop=true",
            )
            captureSink = (capture as Bin).getByName("sink") as? AppSink ?: error("No appsink")
            try {
                playback.setState(State.PLAYING)
                capture.setState(State.PLAYING)
                // A microphone or a plugin that won't start says so on the bus within moments.
                val problem = capture.bus?.timedPopFiltered(ClockTime(START_CHECK_NANOS), MessageType.ERROR)
                    ?: playback.bus?.timedPopFiltered(ClockTime(0), MessageType.ERROR)
                if (problem != null) throw IllegalStateException("Audio would not start")
            } catch (e: Exception) {
                runCatching { capture.setState(State.NULL) }
                runCatching { playback.setState(State.NULL) }
                throw e
            }
            player.start()
        }

        override fun capture(): List<ByteArray> {
            if (closed) throw IllegalStateException("Audio is closed")
            if (muted) {
                // The microphone is closed; records still go out on time, with nothing in them.
                val now = System.nanoTime()
                if (nextCaptureAtNanos < now - RECORD_NANOS) nextCaptureAtNanos = now
                nextCaptureAtNanos += RECORD_NANOS
                val wait = (nextCaptureAtNanos - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                return emptyList()
            }
            val packets = ArrayList<ByteArray>(MediaRecord.MAX_PACKETS)
            val deadline = System.nanoTime() + CAPTURE_WAIT_NANOS
            while (packets.size < PACKETS_PER_RECORD) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val sample = captureSink.tryPullSample(ClockTime(remaining)) ?: break
                sample.buffer?.let(::bytesOf)?.let(packets::add)
            }
            // Fallen behind, an encoder catching up: one more, so the backlog drains.
            if (packets.size == PACKETS_PER_RECORD) {
                captureSink.tryPullSample(ClockTime(0))?.buffer?.let(::bytesOf)?.let(packets::add)
            }
            nextCaptureAtNanos = System.nanoTime()
            return packets
        }

        /** One encoded packet, copied out of GStreamer's memory; null if it isn't a sane one. */
        private fun bytesOf(buffer: Buffer): ByteArray? {
            val size = buffer.size
            if (size <= 0 || size > MAX_PACKET_BYTES) return null
            val out = Out(ByteArray(size.toInt()))
            val copied = buffer.extract(0, out)
            return out.get()?.takeIf { copied == size && it.size.toLong() == size }
        }

        override fun play(codec: Int, packets: List<ByteArray>) {
            if (closed || codec !in CallCodecs.ALL) return
            packets.forEach { queue.add(codec to it) }
            // Arrived in a burst after a stall: skip ahead rather than play further and further
            // behind what's being said.
            if (queue.size > MAX_QUEUED) {
                while (queue.size > TRIM_TO) queue.poll()
            }
        }

        /** Feeds the output one 20 ms packet every 20 ms, after a short run-up that absorbs jitter. */
        private fun playLoop() {
            var buffering = true
            var next = System.nanoTime()
            while (!closed) {
                if (buffering && queue.size >= PREBUFFER) buffering = false
                if (!buffering) {
                    val item = queue.poll()
                    if (item == null) {
                        buffering = true
                    } else {
                        runCatching { push(item.first, item.second) }
                    }
                }
                next += FRAME_NANOS
                val wait = next - System.nanoTime()
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        return
                    }
                } else if (wait < -10 * FRAME_NANOS) {
                    next = System.nanoTime()
                }
            }
        }

        private fun push(codec: Int, packet: ByteArray) {
            val source = if (codec == CallCodecs.OPUS) playbackSource else mulawSource()
            source.pushBuffer(Buffer.wrapped(packet))
        }

        private fun mulawSource(): AppSrc {
            mulawSource?.let { return it }
            val pipeline = Gst.parseLaunch(
                "appsrc name=src format=time is-live=false block=false max-bytes=6000 " +
                    "caps=\"audio/x-mulaw, rate=(int)8000, channels=(int)1\" ! " +
                    "mulawdec ! audioconvert ! audioresample ! autoaudiosink sync=false",
            )
            val source = (pipeline as Bin).getByName("src") as? AppSrc ?: error("No appsrc")
            pipeline.setState(State.PLAYING)
            mulawPlayback = pipeline
            mulawSource = source
            return source
        }

        /** The computer has one output; there is no earpiece to switch from. */
        override fun setSpeaker(on: Boolean) = Unit

        override fun setMuted(muted: Boolean) {
            if (closed || this.muted == muted) return
            this.muted = muted
            // Stopped, not just ignored, so the desktop's microphone indicator goes out.
            runCatching { capture.setState(if (muted) State.NULL else State.PLAYING) }
                .onFailure { DiagnosticLog.record("call", "microphone state change failed (${it.javaClass.simpleName})") }
        }

        override fun close() {
            if (closed) return
            closed = true
            player.interrupt()
            runCatching { capture.setState(State.NULL) }
            runCatching { playback.setState(State.NULL) }
            runCatching { mulawPlayback?.setState(State.NULL) }
            queue.clear()
        }
    }

    private companion object {
        const val PROBE = "niixprobe"
        const val PACKETS_PER_RECORD = 3
        const val MAX_PACKET_BYTES = 400L
        const val FRAME_NANOS = 20_000_000L
        const val RECORD_NANOS = MediaRecord.RECORD_MILLIS * 1_000_000L
        const val CAPTURE_WAIT_NANOS = 140_000_000L
        const val START_CHECK_NANOS = 400_000_000L
        const val PREBUFFER = 6
        const val MAX_QUEUED = 30
        const val TRIM_TO = 12
    }
}

/** The ring for an incoming call: a soft tone, repeated until told to stop. */
class Ringer {

    private var thread: Thread? = null

    @Volatile
    private var ringing = false

    @Synchronized
    fun start() {
        if (ringing) return
        ringing = true
        thread = Thread(::ringLoop, "niix-ringer").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        ringing = false
        thread?.interrupt()
        thread = null
    }

    private fun ringLoop() {
        val me = Thread.currentThread()
        if (runCatching { GstSupport.ensure() }.isFailure) return
        while (ringing && !me.isInterrupted) {
            val pipeline = runCatching { Gst.parseLaunch(TONE) }.getOrNull() ?: return
            try {
                pipeline.setState(State.PLAYING)
                // Played to its end rather than for a fixed time, however long the output takes
                // to open; looked at often enough that answering silences it at once.
                val giveUpAt = System.nanoTime() + MAX_RING_NANOS
                while (ringing && !me.isInterrupted && System.nanoTime() < giveUpAt) {
                    val bus = pipeline.bus ?: break
                    if (bus.timedPopFiltered(ClockTime(POLL_NANOS), MessageType.EOS, MessageType.ERROR) != null) break
                }
            } catch (_: Exception) {
            } finally {
                runCatching { pipeline.setState(State.NULL) }
            }
            try {
                Thread.sleep(PAUSE_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val TONE = "audiotestsrc wave=sine freq=660 volume=0.2 num-buffers=44 ! audioconvert ! audioresample ! autoaudiosink"
        const val POLL_NANOS = 100_000_000L
        const val MAX_RING_NANOS = 4_000_000_000L
        const val PAUSE_MILLIS = 2_000L
    }
}
