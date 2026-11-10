package app.niix.desktop.ui

import app.niix.core.model.DiagnosticLog
import app.niix.desktop.TempFiles
import java.io.File
import org.freedesktop.gstreamer.gst.Bin
import org.freedesktop.gstreamer.gst.ClockTime
import org.freedesktop.gstreamer.gst.Element
import org.freedesktop.gstreamer.gst.ElementFactory
import org.freedesktop.gstreamer.gst.Event
import org.freedesktop.gstreamer.gst.Gst
import org.freedesktop.gstreamer.gst.MessageType
import org.freedesktop.gstreamer.gst.State

/**
 * Records voice messages through GStreamer from the default microphone. AAC in MP4, as the phone
 * records them, where an AAC encoder is installed; Opus in Ogg otherwise, which phones play too.
 * The recording goes to the private temporary folder and is deleted once sent or cancelled.
 */
class VoiceRecorder(private val temp: TempFiles) {

    class Recording(val file: File, val mimeType: String)

    private class Format(val encoder: String, val muxer: String, val rate: Int, val mimeType: String, val extension: String)

    @Volatile
    var isRecording: Boolean = false
        private set

    private var pipeline: Element? = null
    private var file: File? = null
    private var format: Format? = null

    /** Starts recording. False if GStreamer, a microphone or an encoder isn't available. */
    @Synchronized
    fun start(): Boolean {
        if (isRecording) return true
        return runCatching {
            app.niix.desktop.GstSupport.ensure()
            val chosen = FORMATS.firstOrNull { ElementFactory.find(it.encoder) != null && ElementFactory.find(it.muxer) != null }
                ?: return false
            val target = temp.create("rec_", chosen.extension)
            val description = "autoaudiosrc ! audioconvert ! audioresample ! " +
                "audio/x-raw,rate=${chosen.rate},channels=1 ! ${chosen.encoder} ! ${chosen.muxer} ! filesink name=sink"
            val built = Gst.parseLaunch(description)
            (built as Bin).getByName("sink")?.setStringProperty("location", target.absolutePath)
                ?: error("No file sink in the pipeline")
            built.setState(State.PLAYING)
            pipeline = built
            file = target
            format = chosen
            isRecording = true
            true
        }.getOrElse {
            DiagnosticLog.record("voice", "recording could not start (${it.javaClass.simpleName})")
            cleanUp(deleteFile = true)
            false
        }
    }

    /**
     * Finishes the recording properly, so the file's headers are written, and returns it. Blocks
     * for up to a few seconds while GStreamer finishes; call it off the UI thread.
     */
    @Synchronized
    fun stop(): Recording? {
        val p = pipeline ?: return null
        val f = file
        val fmt = format
        return try {
            p.sendEvent(Event.eos())
            val bus = p.bus
            val message = bus?.timedPopFiltered(ClockTime(STOP_TIMEOUT_NANOS), MessageType.EOS, MessageType.ERROR)
            val ok = message != null && message.readType().contains(MessageType.EOS)
            p.setState(State.NULL)
            if (ok && f != null && fmt != null && f.length() > MIN_BYTES) Recording(f, fmt.mimeType) else {
                f?.delete()
                null
            }
        } catch (e: Exception) {
            DiagnosticLog.record("voice", "recording failed (${e.javaClass.simpleName})")
            f?.delete()
            null
        } finally {
            pipeline = null
            file = null
            format = null
            isRecording = false
        }
    }

    @Synchronized
    fun cancel() {
        cleanUp(deleteFile = true)
    }

    private fun cleanUp(deleteFile: Boolean) {
        runCatching { pipeline?.setState(State.NULL) }
        if (deleteFile) file?.delete()
        pipeline = null
        file = null
        format = null
        isRecording = false
    }

    private companion object {
        const val STOP_TIMEOUT_NANOS = 5_000_000_000L
        const val MIN_BYTES = 256L

        val FORMATS = listOf(
            Format("fdkaacenc", "mp4mux", 44_100, "audio/mp4", "m4a"),
            Format("avenc_aac", "mp4mux", 44_100, "audio/mp4", "m4a"),
            Format("voaacenc", "mp4mux", 44_100, "audio/mp4", "m4a"),
            Format("opusenc", "oggmux", 48_000, "audio/ogg", "ogg"),
        )
    }
}
