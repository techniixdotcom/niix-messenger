package app.niix.desktop.ui

import app.niix.core.messaging.CallEnded
import app.niix.core.messaging.CallPhase
import app.niix.core.messaging.CallQuality
import app.niix.core.messaging.CallState
import app.niix.desktop.DesktopContainer
import app.niix.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.gtk.Align
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.Widget

/**
 * The call in progress, above the chat list: who, how it's going, the safety code to compare, and
 * the buttons to answer, decline, mute and hang up. It stays there while the chats are used.
 */
class CallBanner(private val container: DesktopContainer) {

    private val box = vbox(6)
    val widget: Widget get() = box

    /** True for a few seconds after a call ends, while how it ended is still shown. */
    var showingEnd: Boolean = false
        private set

    private val name = Label("")
    private val status = Label("")
    private val quality = Label("")
    private val code = Label("")
    private val codeHint = wrappingLabel(tr("call_safety_hint"), "dim-label", "caption")
    private val answer = Button.withLabel(tr("call_answer"))
    private val mute = Button.withLabel(tr("call_mute"))
    private val end = Button.withLabel(tr("call_hang_up"))
    private val calls get() = container.conversations.calls
    private var nameJob: Job? = null
    private var timerJob: Job? = null
    private var endJob: Job? = null
    private var shownPeer: String? = null
    var onVisibilityChanged: (() -> Unit)? = null

    init {
        box.addCssClass("call-panel")
        name.addCssClass("title-4")
        name.ellipsize = org.gnome.pango.EllipsizeMode.END
        status.addCssClass("dim-label")
        quality.addCssClass("danger")
        quality.visible = false
        code.addCssClass("monospace")
        code.selectable = true
        code.visible = false
        codeHint.visible = false
        codeHint.justify = org.gnome.gtk.Justification.CENTER
        codeHint.xalign = 0.5f
        answer.addCssClass("suggested-action")
        end.addCssClass("destructive-action")
        answer.onClicked { calls.answer() }
        mute.onClicked { calls.setMuted(!calls.state.value.muted) }
        end.onClicked {
            val state = calls.state.value
            if (state.phase == CallPhase.INCOMING) calls.decline() else calls.hangUp()
        }
        val buttons = hbox(8)
        buttons.halign = Align.CENTER
        buttons.append(answer)
        buttons.append(mute)
        buttons.append(end)
        listOf(name, status, quality, code, codeHint, buttons).forEach(box::append)
        box.visible = false
    }

    fun update(state: CallState) {
        endJob?.cancel()
        timerJob?.cancel()
        when (state.phase) {
            CallPhase.IDLE -> {
                showingEnd = false
                box.visible = false
                onVisibilityChanged?.invoke()
                return
            }
            CallPhase.ENDED -> {
                showingEnd = true
                status.label = tr(endedText(state.ended))
                answer.visible = false
                mute.visible = false
                end.visible = false
                code.visible = false
                codeHint.visible = false
                quality.visible = false
                endJob = uiScope.launch {
                    delay(END_SHOWN_MILLIS)
                    showingEnd = false
                    box.visible = false
                    onVisibilityChanged?.invoke()
                }
            }
            else -> {
                showingEnd = false
                answer.visible = state.phase == CallPhase.INCOMING
                mute.visible = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE
                mute.label = tr(if (state.muted) "call_unmute" else "call_mute")
                end.visible = true
                end.label = tr(if (state.phase == CallPhase.INCOMING) "call_decline" else "call_hang_up")
                val safety = state.safetyCode.takeIf { state.phase == CallPhase.ACTIVE }
                code.label = safety?.let { tr("call_safety_code", it) } ?: ""
                code.visible = safety != null
                codeHint.visible = safety != null
                val trouble = when (state.quality.takeIf { state.phase == CallPhase.ACTIVE }) {
                    CallQuality.POOR -> tr("call_quality_poor")
                    CallQuality.STALLED -> tr("call_quality_stalled")
                    else -> null
                }
                quality.label = trouble ?: ""
                quality.visible = trouble != null
                when (state.phase) {
                    CallPhase.OUTGOING -> status.label = tr("call_status_calling")
                    CallPhase.INCOMING -> status.label = tr("call_status_incoming")
                    CallPhase.CONNECTING -> status.label = tr("call_status_connecting")
                    else -> {
                        val since = state.connectedAtNanos ?: System.nanoTime()
                        timerJob = uiScope.launch {
                            while (isActive) {
                                val seconds = (System.nanoTime() - since) / 1_000_000_000L
                                status.label = "%d:%02d".format(seconds / 60, seconds % 60)
                                delay(1_000)
                            }
                        }
                    }
                }
            }
        }
        showPeer(state.peer)
        box.visible = true
        onVisibilityChanged?.invoke()
    }

    private fun showPeer(peer: String?) {
        if (peer == null || peer == shownPeer) return
        shownPeer = peer
        name.label = peer.take(10) + "…"
        nameJob?.cancel()
        nameJob = uiScope.launch {
            val shown = withContext(Dispatchers.IO) { runCatching { container.conversations.displayName(peer) }.getOrNull() }
            if (shown != null && shownPeer == peer) name.label = shown
        }
    }

    private fun endedText(ended: CallEnded?): String = when (ended) {
        CallEnded.HUNG_UP, CallEnded.REMOTE_HUNG_UP, null -> "call_ended"
        CallEnded.DECLINED -> "call_declined"
        CallEnded.BUSY -> "call_busy"
        CallEnded.NOT_PERMITTED -> "call_not_permitted"
        CallEnded.NO_ANSWER -> "call_no_answer"
        CallEnded.MISSED -> "call_missed"
        CallEnded.ANSWERED_ELSEWHERE -> "call_answered_elsewhere"
        CallEnded.UNREACHABLE -> "call_unreachable"
        CallEnded.CONNECTION_LOST -> "call_connection_lost"
        CallEnded.AUDIO_FAILED -> "call_audio_failed"
        CallEnded.FAILED -> "call_failed"
    }

    fun dispose() {
        nameJob?.cancel()
        timerJob?.cancel()
        endJob?.cancel()
    }

    private companion object {
        const val END_SHOWN_MILLIS = 4_000L
    }
}
