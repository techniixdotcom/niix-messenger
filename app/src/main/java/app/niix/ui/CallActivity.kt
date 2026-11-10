package app.niix.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.niix.R
import app.niix.call.CallService
import app.niix.core.messaging.CallEnded
import app.niix.core.messaging.CallPhase
import app.niix.core.messaging.CallQuality
import app.niix.core.messaging.CallState
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The call screen: ringing, calling, talking. Opened to place a call ([EXTRA_PEER]), to pick one up
 * ([ACTION_ANSWER]), or just to show the call that's going on.
 *
 * Shown over the lock screen so a call can be answered like any other, but while the phone is
 * locked it doesn't say who is calling: anyone holding the phone would see it.
 */
class CallActivity : SecureActivity() {

    private val calls get() = container.conversations.calls

    /**
     * A call is ringing while NiiX is locked. All this screen knows is that someone the user
     * allowed calls with is calling; it offers to answer, which goes through the passcode, or to
     * decline. See Doorbell.
     */
    private fun ringingLocked(): Boolean =
        !container.storage.appLock.isUnlocked() && container.conversations.lockedRing.value

    /** Set once this screen has shown a locked ring, so it closes when that ring stops. */
    private var shownLockedRing = false

    override val showsWhileLocked: Boolean get() = ringingLocked()

    private lateinit var avatar: TextView
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var codeHint: TextView
    private lateinit var quality: TextView
    private lateinit var controls: View
    private lateinit var mute: MaterialButton
    private lateinit var speaker: MaterialButton
    private lateinit var end: MaterialButton
    private lateinit var answer: MaterialButton

    private var shownPeer: String? = null
    private var timerJob: Job? = null
    private var closeJob: Job? = null
    private var serviceStarted = false
    private var proximityLock: PowerManager.WakeLock? = null

    /** Started with nothing going on and nothing to start: there's nothing to show. */
    private var sawLiveCall = false

    /** What to do once the microphone permission is granted. */
    private var pendingAction: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.perm_mic_needed, Toast.LENGTH_LONG).show()
            // A ringing call stays declinable from the screen; one we were about to place is off.
            if (calls.state.value.phase != CallPhase.INCOMING) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_call)
        avatar = findViewById(R.id.call_avatar)
        name = findViewById(R.id.call_name)
        status = findViewById(R.id.call_status)
        code = findViewById(R.id.call_code)
        codeHint = findViewById(R.id.call_code_hint)
        quality = findViewById(R.id.call_quality)
        controls = findViewById(R.id.call_controls)
        mute = findViewById(R.id.call_mute)
        speaker = findViewById(R.id.call_speaker)
        end = findViewById(R.id.call_end)
        answer = findViewById(R.id.call_answer)

        mute.setOnClickListener { calls.setMuted(!calls.state.value.muted) }
        speaker.setOnClickListener { calls.setSpeaker(!calls.state.value.speaker) }
        end.setOnClickListener {
            when {
                ringingLocked() -> {
                    container.conversations.declineLockedRing()
                    finish()
                }
                calls.state.value.isLive -> calls.hangUp()
                else -> finish()
            }
        }
        answer.setOnClickListener {
            if (ringingLocked()) answerAfterUnlock() else withMicrophone { calls.answer() }
        }

        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                calls.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.conversations.lockedRing.collect { ringing ->
                    if (ringing && !container.storage.appLock.isUnlocked()) {
                        renderLockedRing()
                    } else if (shownLockedRing && !container.storage.appLock.isUnlocked()) {
                        // The caller gave up, or it was answered on another device.
                        finish()
                    }
                }
            }
        }
    }

    private fun renderLockedRing() {
        shownLockedRing = true
        shownPeer = null
        name.setText(R.string.call_hidden_name)
        avatar.text = ""
        status.setText(R.string.call_status_incoming)
        answer.visibility = View.VISIBLE
        end.setText(R.string.call_decline)
        controls.visibility = View.GONE
        code.visibility = View.GONE
        codeHint.visibility = View.GONE
        quality.visibility = View.GONE
    }

    /**
     * Answer, on a locked app: the microphone permission is settled first, then the passcode
     * screen opens, and the call is picked up as soon as the unlock reveals it.
     */
    private fun answerAfterUnlock() {
        withMicrophone {
            container.conversations.answerLockedRing()
            startActivity(Intent(this, PasscodeActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER)
        when {
            peer != null -> {
                // Consumed, so turning the screen doesn't place the call again.
                intent.removeExtra(EXTRA_PEER)
                withMicrophone {
                    // A call already going on (one still ringing, say) is shown instead: there's
                    // one call at a time, and this screen is where it's ended.
                    if (!calls.startCall(peer) && !calls.state.value.isLive) {
                        Toast.makeText(this, R.string.call_failed, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            intent.action == ACTION_ANSWER -> {
                intent.action = null
                if (ringingLocked()) {
                    answerAfterUnlock()
                } else if (calls.state.value.phase == CallPhase.INCOMING) {
                    withMicrophone { calls.answer() }
                }
            }
        }
    }

    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun render(state: CallState) {
        if (state.isLive) {
            sawLiveCall = true
            closeJob?.cancel()
            closeJob = null
        }
        if (state.phase == CallPhase.IDLE) {
            if (ringingLocked()) {
                renderLockedRing()
                return
            }
            // Nothing going on, or a call placed from here hasn't shown up yet.
            if (sawLiveCall || pendingAction == null && intent.getStringExtra(EXTRA_PEER) == null) {
                closeSoon(0)
            }
            return
        }
        showPeer(state.peer)

        val connected = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE
        if (connected && !serviceStarted) {
            serviceStarted = true
            CallService.start(this)
        }

        answer.visibility = if (state.phase == CallPhase.INCOMING) View.VISIBLE else View.GONE
        end.setText(
            when (state.phase) {
                CallPhase.INCOMING -> R.string.call_decline
                CallPhase.ENDED -> R.string.dialog_close
                else -> R.string.call_hang_up
            },
        )
        controls.visibility = if (state.isLive && state.phase != CallPhase.INCOMING) View.VISIBLE else View.GONE
        mute.setText(if (state.muted) R.string.call_unmute else R.string.call_mute)
        speaker.setText(if (state.speaker) R.string.call_earpiece else R.string.call_speaker)

        val safetyCode = state.safetyCode.takeIf { state.phase == CallPhase.ACTIVE }
        code.text = safetyCode?.let { getString(R.string.call_safety_code, it) }
        code.visibility = if (safetyCode != null) View.VISIBLE else View.GONE
        codeHint.visibility = code.visibility

        showQuality(state)

        timerJob?.cancel()
        timerJob = null
        when (state.phase) {
            CallPhase.OUTGOING -> status.setText(R.string.call_status_calling)
            CallPhase.INCOMING -> status.setText(R.string.call_status_incoming)
            CallPhase.CONNECTING -> status.setText(R.string.call_status_connecting)
            CallPhase.ACTIVE -> {
                val since = state.connectedAtNanos ?: System.nanoTime()
                timerJob = lifecycleScope.launch {
                    while (isActive) {
                        status.text = formatDuration((System.nanoTime() - since) / 1_000_000_000L)
                        delay(1_000)
                    }
                }
            }
            CallPhase.ENDED -> {
                status.setText(endedText(state.ended))
                closeSoon(ENDED_SHOWN_MILLIS)
            }
            CallPhase.IDLE -> Unit
        }
        updateProximity(state.phase == CallPhase.ACTIVE || state.phase == CallPhase.CONNECTING, state.speaker)
    }

    /**
     * Says when the other side's audio is arriving late or not at all. Over Tor that's the circuit,
     * not the call, and without saying so a slow moment just looks like NiiX misbehaving.
     */
    private fun showQuality(state: CallState) {
        val text = when {
            state.phase != CallPhase.ACTIVE -> null
            state.quality == CallQuality.POOR -> R.string.call_quality_poor
            state.quality == CallQuality.STALLED -> R.string.call_quality_stalled
            else -> null
        }
        if (text == null) {
            quality.visibility = View.GONE
            return
        }
        quality.setText(text)
        quality.setTextColor(
            themeColor(if (state.quality == CallQuality.STALLED) R.attr.niixDanger else R.attr.niixPrimary),
        )
        quality.visibility = View.VISIBLE
    }

    private fun showPeer(peer: String?) {
        if (peer == null) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            shownPeer = null
            name.setText(R.string.call_hidden_name)
            avatar.text = ""
            return
        }
        if (peer == shownPeer) return
        shownPeer = peer
        lifecycleScope.launch {
            val display = runCatching { container.conversations.displayName(peer) }.getOrDefault(peer.take(8))
            if (shownPeer != peer) return@launch
            name.text = display
            avatar.text = display.trim().firstOrNull()?.uppercase() ?: "?"
        }
    }

    /** Screen off when the phone is held to the ear, as on any call; not on speaker. */
    @Suppress("WakelockTimeout")
    private fun updateProximity(talking: Boolean, onSpeaker: Boolean) {
        val want = talking && !onSpeaker
        val held = proximityLock
        if (want && held == null) {
            val power = getSystemService(PowerManager::class.java) ?: return
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "niix:call-proximity").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        } else if (!want && held != null) {
            runCatching { held.release() }
            proximityLock = null
        }
    }

    private fun closeSoon(afterMillis: Long) {
        if (closeJob?.isActive == true) return
        closeJob = lifecycleScope.launch {
            delay(afterMillis)
            if (!calls.state.value.isLive) finish()
        }
    }

    private fun endedText(reason: CallEnded?): Int = when (reason) {
        CallEnded.HUNG_UP, CallEnded.REMOTE_HUNG_UP, null -> R.string.call_ended
        CallEnded.DECLINED -> R.string.call_declined
        CallEnded.BUSY -> R.string.call_busy
        CallEnded.NOT_PERMITTED -> R.string.call_not_permitted
        CallEnded.NO_ANSWER -> R.string.call_no_answer
        CallEnded.MISSED -> R.string.call_missed
        CallEnded.ANSWERED_ELSEWHERE -> R.string.call_answered_elsewhere
        CallEnded.UNREACHABLE -> R.string.call_unreachable
        CallEnded.CONNECTION_LOST -> R.string.call_connection_lost
        CallEnded.AUDIO_FAILED -> R.string.call_audio_failed
        CallEnded.FAILED -> R.string.call_failed
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // The phone may have been unlocked since: the name can be shown now.
        shownPeer = null
        showPeer(calls.state.value.peer)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        updateProximity(talking = false, onSpeaker = false)
        super.onDestroy()
    }

    companion object {
        /** An account to call. */
        const val EXTRA_PEER = "peer"

        /** Pick up the call that's ringing. */
        const val ACTION_ANSWER = "app.niix.call.ANSWER"

        private const val ENDED_SHOWN_MILLIS = 1_800L

        fun call(context: Context, peer: String) {
            context.startActivity(
                Intent(context, CallActivity::class.java)
                    .putExtra(EXTRA_PEER, peer)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
