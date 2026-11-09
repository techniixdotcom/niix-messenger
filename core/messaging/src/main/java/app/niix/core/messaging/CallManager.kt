package app.niix.core.messaging

import app.niix.core.model.DiagnosticLog
import app.niix.core.transport.DuplexConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CallPhase {
    /** No call. */
    IDLE,

    /** We're ringing them. */
    OUTGOING,

    /** They're ringing us. */
    INCOMING,

    /** Picked up; the audio connection is being set up over Tor. */
    CONNECTING,

    /** Talking. */
    ACTIVE,

    /** Over; [CallState.ended] says why. Goes back to IDLE a few seconds later. */
    ENDED,
}

/** How the other side's audio is arriving, measured on this phone. */
enum class CallQuality {
    /** Arriving on time. */
    GOOD,

    /** Late or patchy: the Tor circuit is slow or congested. */
    POOR,

    /** Nothing for a few seconds: the circuit has stalled. The call ends if it stays that way. */
    STALLED,
}

enum class CallEnded {
    HUNG_UP,
    REMOTE_HUNG_UP,
    DECLINED,
    BUSY,
    NOT_PERMITTED,
    NO_ANSWER,
    MISSED,
    ANSWERED_ELSEWHERE,
    UNREACHABLE,
    CONNECTION_LOST,
    AUDIO_FAILED,
    FAILED,
}

data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    /** The other person's account address. */
    val peer: String? = null,
    val outgoing: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** System.nanoTime() when audio started flowing, for the call timer. */
    val connectedAtNanos: Long? = null,
    val ended: CallEnded? = null,
    /** See CallKeys.safetyCode. Known from picking up onwards. */
    val safetyCode: String? = null,
    /** While talking, how the other side's audio is arriving. */
    val quality: CallQuality = CallQuality.GOOD,
) {
    val isLive: Boolean get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
}

/**
 * What the call layer needs from the messaging layer. Implemented by ConversationManager.
 */
internal interface CallHost {
    /** This account's address. */
    fun selfAccount(): String

    /** This device's own address, which media connections are opened from. */
    fun selfDevice(): String

    /**
     * Whether we may call [account]: we allow calls with them, and nothing else stands in the way.
     * Whether they allow it is theirs to decide when the offer arrives, and their phone refuses it
     * before anything rings if not; what we last heard from them may be out of date.
     */
    fun canCall(account: String): Boolean

    /** Whether a call from [account] may ring here: we allow it, and nothing else stands in the way. */
    fun canRing(account: String): Boolean

    /**
     * What a call just showed about whether [account] allows calls with us: an offer or an answer
     * from them says yes, every one of their devices refusing as not permitted says no. Keeps the
     * belief from their last CallPermission current when that message is late or was lost.
     */
    fun peerAllowsCalls(account: String, allowed: Boolean)

    /**
     * Sends [wire] directly over Tor, never through a relay, to [account]'s devices ([deviceIds],
     * or all of them when null). Returns the ids of the devices that acknowledged it.
     */
    suspend fun sendToDevices(account: String, wire: WireMessage, deviceIds: Set<Int>? = null): Set<Int>

    fun deviceIds(account: String): Set<Int>

    fun deviceOnion(account: String, deviceId: Int): String?

    suspend fun connect(onion: String): DuplexConnection

    /**
     * Opens connections to these device addresses now, if they aren't open, and keeps them open
     * until [releaseConnections] with the same addresses. Tor reuses a circuit to an onion for every
     * connection to it, so holding one means the next message, and the next connection, to that
     * device skips building a circuit, which is most of the wait.
     */
    fun holdConnections(onions: Collection<String>)

    fun releaseConnections(onions: Collection<String>)

    /** Wall-clock time, for comparing with the timestamp in another phone's offer. */
    fun wallClockMillis(): Long
}

/**
 * Voice calls between two people who have both allowed them.
 *
 * Ringing, answering and hanging up travel as ordinary messages inside the Signal session, direct
 * over Tor and never through a Nostr relay. The audio goes over a Tor connection of its own, opened
 * by the caller to the onion address of the callee's device that answered, so neither phone learns
 * the other's IP address. There's no STUN, TURN or WebRTC anywhere: those exist to find a direct
 * path between the two phones, and a direct path is exactly what would give their addresses away.
 *
 * The audio is sealed with keys derived from a secret each side sent inside the Signal session
 * (see CallKeys), in fixed-size records at a fixed rate (see MediaRecord).
 */
class CallManager internal constructor(
    private val host: CallHost,
    private val audio: CallAudio?,
    private val scope: CoroutineScope,
) {

    private val lock = Any()

    /** The call, if any. Replaced, never reused. Guarded by [lock]. */
    private var current: Call? = null

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _missed = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The account of someone whose call wasn't picked up here. */
    val missed: SharedFlow<String> = _missed.asSharedFlow()

    /** Offers already handled, so a second copy doesn't ring twice. Guarded by [lock]. */
    private val seenOffers = LinkedHashSet<String>()

    private val random = SecureRandom()

    fun isInCall(): Boolean = state.value.isLive

    // ---- What the user does ----

    /**
     * Rings [account]. False if a call is already going on. Whether they may be called is checked
     * again in the background, and the call ends as NOT_PERMITTED if not.
     */
    fun startCall(account: String): Boolean {
        val call = synchronized(lock) {
            if (current?.ended == false) return false
            Call(
                id = UUID.randomUUID().toString(),
                peer = account,
                outgoing = true,
                offerSecret = randomSecret(),
                // Read once, here: the database may be locked by the time the call ends.
                self = host.selfAccount(),
            ).also {
                it.phase = CallPhase.OUTGOING
                current = it
                publish(it)
            }
        }
        call.jobs += scope.launch(Dispatchers.IO) { ring(call) }
        call.jobs += scope.launch(Dispatchers.IO) {
            delay(RING_TIMEOUT_MILLIS)
            if (phaseOf(call) == CallPhase.OUTGOING) {
                finish(call, CallEnded.NO_ANSWER, EndNotice(CallEndReasons.TIMEOUT, null))
            }
        }
        return true
    }

    /** Picks up the call that's ringing. */
    fun answer() {
        val call = synchronized(lock) {
            val call = current ?: return
            if (call.ended || call.outgoing || call.phase != CallPhase.INCOMING) return
            val answerSecret = randomSecret()
            val keys = try {
                CallKeys.derive(call.offerSecret, answerSecret, call.id, callerAccount = call.peer, calleeAccount = call.self)
            } catch (e: Exception) {
                DiagnosticLog.record("call", "could not derive call keys (${e::class.java.simpleName})")
                answerSecret.fill(0)
                null
            }
            if (keys == null) {
                finish(call, CallEnded.FAILED, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
                return
            }
            call.answerSecret = answerSecret
            call.keys = keys
            call.phase = CallPhase.CONNECTING
            publish(call)
            call
        }
        call.jobs += scope.launch(Dispatchers.IO) {
            val answer = WireMessage.CallAnswer(call.id, call.answerSecret?.copyOf() ?: return@launch)
            // Tried again until it lands, for as long as the call waits for it: one attempt that
            // fails on a shaky circuit shouldn't lose a call that was picked up.
            val delivered = deliver(call.peer, answer, setOf(call.peerDeviceId), CONNECT_TIMEOUT_MILLIS) {
                phaseOf(call) == CallPhase.CONNECTING
            }
            if (delivered.isEmpty() && phaseOf(call) == CallPhase.CONNECTING) {
                DiagnosticLog.record("call", "the caller's device didn't take the answer")
                finish(call, CallEnded.UNREACHABLE, null)
            }
        }
        call.jobs += scope.launch(Dispatchers.IO) {
            delay(CONNECT_TIMEOUT_MILLIS)
            if (phaseOf(call) == CallPhase.CONNECTING) {
                DiagnosticLog.record("call", "the caller never opened the audio connection")
                finish(call, CallEnded.UNREACHABLE, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            }
        }
    }

    /** Turns down the call that's ringing. */
    fun decline() {
        synchronized(lock) {
            val call = current ?: return
            if (call.ended || call.outgoing || call.phase != CallPhase.INCOMING) return
            finish(call, CallEnded.DECLINED, EndNotice(CallEndReasons.DECLINED, setOf(call.peerDeviceId)))
        }
    }

    /** Ends the call, whatever stage it's at. Declines one that's ringing here. */
    fun hangUp() {
        synchronized(lock) {
            val call = current ?: return
            if (call.ended) return
            when (call.phase) {
                CallPhase.OUTGOING -> finish(call, CallEnded.HUNG_UP, EndNotice(CallEndReasons.HANGUP, null))
                CallPhase.INCOMING -> finish(call, CallEnded.DECLINED, EndNotice(CallEndReasons.DECLINED, setOf(call.peerDeviceId)))
                CallPhase.CONNECTING, CallPhase.ACTIVE -> finish(
                    call,
                    CallEnded.HUNG_UP,
                    // Also over the Signal session, in case the audio connection is what failed.
                    EndNotice(CallEndReasons.HANGUP, setOf(call.peerDeviceId)),
                    hangupInBand = true,
                )
                CallPhase.IDLE, CallPhase.ENDED -> Unit
            }
        }
    }

    fun setMuted(muted: Boolean) {
        val session = synchronized(lock) {
            val call = current ?: return
            if (call.ended) return
            call.muted = muted
            publish(call)
            call.media
        }
        // Closes the microphone itself, not only what's sent. Records keep going out on time,
        // without audio; see MediaRecord.
        session?.let { runCatching { it.audio.setMuted(muted) } }
    }

    fun setSpeaker(on: Boolean) {
        val session = synchronized(lock) {
            val call = current ?: return
            if (call.ended) return
            call.speaker = on
            publish(call)
            call.media
        }
        session?.let { runCatching { it.audio.setSpeaker(on) } }
    }

    /** Ends any call without telling anyone. For a wipe, when nothing should be sent any more. */
    fun endAll() {
        synchronized(lock) {
            seenOffers.clear()
            current?.let { finish(it, CallEnded.FAILED, null) }
        }
    }

    /** [account] stopped allowing calls with us, or we with them: any call with them ends. */
    internal fun permissionWithdrawn(account: String, byThem: Boolean) {
        synchronized(lock) {
            val call = current ?: return
            if (call.ended || call.peer != account) return
            val notice = when (call.phase) {
                CallPhase.OUTGOING -> EndNotice(CallEndReasons.HANGUP, null)
                CallPhase.IDLE, CallPhase.ENDED -> null
                else -> EndNotice(CallEndReasons.NOT_PERMITTED, setOf(call.peerDeviceId))
            }
            finish(
                call,
                if (byThem) CallEnded.NOT_PERMITTED else CallEnded.HUNG_UP,
                if (byThem) null else notice,
                hangupInBand = !byThem,
            )
        }
    }

    // ---- What arrives from the other side ----

    internal fun onOffer(account: String, deviceId: Int, offer: WireMessage.CallOffer) {
        synchronized(lock) {
            if (!seenOffers.add(offer.callId)) return
            while (seenOffers.size > MAX_SEEN_OFFERS) seenOffers.remove(seenOffers.first())
        }
        val allowed = runCatching { host.canRing(account) }.getOrDefault(false)
        if (!allowed) {
            // They're told, so their phone stops ringing at once instead of after a minute. They
            // already know whether we allow calls; this tells them nothing new.
            DiagnosticLog.record("call", "refused a call: calls aren't allowed with that contact")
            sendEnd(account, setOf(deviceId), offer.callId, CallEndReasons.NOT_PERMITTED)
            return
        }
        // Calling us is them allowing it.
        runCatching { host.peerAllowsCalls(account, true) }
        val age = host.wallClockMillis() - offer.sentAtMillis
        if (age > OFFER_MAX_AGE_MILLIS || age < -OFFER_MAX_AGE_MILLIS) {
            // Waited too long, typically in the locked inbox. Too late to ring, not too late to say
            // someone tried.
            DiagnosticLog.record("call", "a call arrived too late to ring")
            _missed.tryEmit(account)
            return
        }
        val deviceOnion = host.deviceOnion(account, deviceId) ?: return
        val self = runCatching { host.selfAccount() }.getOrNull() ?: return
        var autoAnswer = false
        val call = synchronized(lock) {
            val existing = current
            if (existing != null && !existing.ended) {
                val bothCalling = existing.outgoing && existing.peer == account && existing.phase == CallPhase.OUTGOING
                if (!bothCalling) {
                    sendEnd(account, setOf(deviceId), offer.callId, CallEndReasons.BUSY)
                    _missed.tryEmit(account)
                    return
                }
                // Both rang each other at once. Both phones keep the call with the higher id and
                // drop the other, so exactly one survives. Ours is the one dropped here, and theirs
                // is picked up straight away: we were calling them anyway.
                if (offer.callId <= existing.id) return
                finish(existing, CallEnded.HUNG_UP, EndNotice(CallEndReasons.HANGUP, null), publishState = false)
                autoAnswer = true
            }
            Call(
                id = offer.callId,
                peer = account,
                outgoing = false,
                offerSecret = offer.secret.copyOf(),
                self = self,
            ).also {
                it.peerDeviceId = deviceId
                it.peerDeviceOnion = deviceOnion
                it.phase = CallPhase.INCOMING
                current = it
                publish(it)
                // While it rings, open the way back to the caller, so picking up reaches them over
                // a connection that's already there instead of waiting for Tor to build one.
                holdLocked(it, listOf(deviceOnion))
            }
        }
        call.jobs += scope.launch(Dispatchers.IO) {
            delay(INCOMING_RING_MILLIS)
            if (phaseOf(call) == CallPhase.INCOMING) {
                finish(call, CallEnded.MISSED, null)
                _missed.tryEmit(account)
            }
        }
        if (autoAnswer) answer()
    }

    internal fun onAnswer(account: String, deviceId: Int, answer: WireMessage.CallAnswer) {
        val call = synchronized(lock) {
            val call = current ?: return
            if (call.ended || !call.outgoing || call.peer != account || call.id != answer.callId) return
            if (call.phase != CallPhase.OUTGOING) {
                // Picked up on two of their devices at once. The first answer won.
                if (deviceId != call.peerDeviceId) {
                    sendEnd(account, setOf(deviceId), call.id, CallEndReasons.ANSWERED_ELSEWHERE)
                }
                return
            }
            val onion = host.deviceOnion(account, deviceId) ?: return
            val keys = try {
                CallKeys.derive(call.offerSecret, answer.secret, call.id, callerAccount = call.self, calleeAccount = account)
            } catch (e: Exception) {
                DiagnosticLog.record("call", "an answer's key didn't work (${e::class.java.simpleName})")
                return
            }
            call.answerSecret = answer.secret.copyOf()
            call.keys = keys
            call.peerDeviceId = deviceId
            call.peerDeviceOnion = onion
            call.phase = CallPhase.CONNECTING
            publish(call)
            call
        }
        runCatching { host.peerAllowsCalls(account, true) }
        // Their other devices stop ringing.
        val others = runCatching { host.deviceIds(account) }.getOrDefault(emptySet()) - deviceId
        if (others.isNotEmpty()) sendEnd(account, others, call.id, CallEndReasons.ANSWERED_ELSEWHERE)
        call.jobs += scope.launch(Dispatchers.IO) { dialMedia(call) }
        call.jobs += scope.launch(Dispatchers.IO) {
            delay(CONNECT_TIMEOUT_MILLIS)
            if (phaseOf(call) == CallPhase.CONNECTING) {
                DiagnosticLog.record("call", "couldn't open the audio connection in time")
                finish(call, CallEnded.UNREACHABLE, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            }
        }
    }

    internal fun onEnd(account: String, deviceId: Int, end: WireMessage.CallEnd) {
        var missedFrom: String? = null
        synchronized(lock) {
            val call = current ?: return
            if (call.ended || call.peer != account || call.id != end.callId) return
            if (call.outgoing) {
                when (call.phase) {
                    CallPhase.OUTGOING -> {
                        if (end.reason == CallEndReasons.DECLINED) {
                            // A person said no, on whichever of their devices. The rest stop ringing.
                            val others = runCatching { host.deviceIds(account) }.getOrDefault(emptySet()) - deviceId
                            finish(call, CallEnded.DECLINED, EndNotice(CallEndReasons.HANGUP, others))
                        } else {
                            // One device couldn't take it. Others may still be ringing.
                            call.refusals[deviceId] = end.reason
                            endIfEveryDeviceRefused(call)
                        }
                    }
                    CallPhase.CONNECTING, CallPhase.ACTIVE -> if (deviceId == call.peerDeviceId) {
                        finish(call, if (end.reason == CallEndReasons.NOT_PERMITTED) CallEnded.NOT_PERMITTED else CallEnded.REMOTE_HUNG_UP, null)
                    }
                    CallPhase.INCOMING, CallPhase.IDLE, CallPhase.ENDED -> Unit
                }
            } else {
                if (deviceId != call.peerDeviceId) return
                when (call.phase) {
                    CallPhase.INCOMING -> if (end.reason == CallEndReasons.ANSWERED_ELSEWHERE) {
                        finish(call, CallEnded.ANSWERED_ELSEWHERE, null)
                    } else {
                        finish(call, CallEnded.MISSED, null)
                        missedFrom = account
                    }
                    CallPhase.CONNECTING, CallPhase.ACTIVE -> finish(
                        call,
                        if (end.reason == CallEndReasons.ANSWERED_ELSEWHERE) CallEnded.ANSWERED_ELSEWHERE else CallEnded.REMOTE_HUNG_UP,
                        null,
                    )
                    CallPhase.OUTGOING, CallPhase.IDLE, CallPhase.ENDED -> Unit
                }
            }
        }
        missedFrom?.let { _missed.tryEmit(it) }
    }

    /**
     * The callee's end of the audio connection. Called with the frame's sender already read; reads
     * the rest of the opening frame, and if it's the caller of the call being picked up here, runs
     * the call on [connection] until it ends. Anything else returns at once, and the connection is
     * closed without a word.
     */
    internal suspend fun serveMedia(
        sender: String,
        input: DataInputStream,
        connection: DuplexConnection,
        liftDeadline: () -> Unit,
    ) {
        val idLength = input.readInt()
        if (idLength !in 1..MAX_CALL_ID_BYTES) return
        val callId = String(ByteArray(idLength).also(input::readFully), Charsets.UTF_8)
        val proofLength = input.readInt()
        if (proofLength != CallKeys.PROOF_BYTES) return
        val proof = ByteArray(proofLength).also(input::readFully)

        val (call, keys) = synchronized(lock) {
            val call = current ?: return
            val keys = call.keys ?: return
            val genuine = !call.ended && !call.outgoing && call.phase == CallPhase.CONNECTING &&
                call.id == callId && call.peerDeviceOnion == sender && call.connection == null &&
                MessageDigest.isEqual(proof, keys.callerProof)
            if (!genuine) {
                DiagnosticLog.record("call", "refused an audio connection that doesn't belong to the call")
                return
            }
            call.connection = connection
            call to keys
        }
        liftDeadline()
        try {
            DataOutputStream(connection.output).run {
                writeByte(FRAME_CALL_ACCEPT)
                write(keys.calleeProof)
                flush()
            }
        } catch (e: IOException) {
            finish(call, CallEnded.CONNECTION_LOST, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            return
        }
        if (startMedia(call, connection, sendKey = keys.calleeToCaller, receiveKey = keys.callerToCallee)) {
            // The connection belongs to the call until it ends; returning would close it.
            call.done.await()
        }
    }

    // ---- Inside ----

    private suspend fun ring(call: Call) {
        val permitted = runCatching { host.canCall(call.peer) }.getOrDefault(false)
        if (!permitted) {
            finish(call, CallEnded.NOT_PERMITTED, null)
            return
        }
        val offer = WireMessage.CallOffer(call.id, call.offerSecret.copyOf(), host.wallClockMillis())
        val ringing = try {
            host.sendToDevices(call.peer, offer, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        synchronized(lock) {
            if (call.ended) return
            call.ringing = ringing
            // Keeps the circuits to the ringing devices up, so the audio connection to whichever
            // answers opens at once.
            holdLocked(call, ringing.mapNotNull { runCatching { host.deviceOnion(call.peer, it) }.getOrNull() })
            if (ringing.isEmpty()) {
                DiagnosticLog.record("call", "none of the contact's devices could be reached")
                // A device may have taken it without its acknowledgement getting back.
                finish(call, CallEnded.UNREACHABLE, EndNotice(CallEndReasons.HANGUP, null))
                return
            }
            endIfEveryDeviceRefused(call)
        }
    }

    /** Guarded by [lock]. Ends an outgoing call once every device that rang has refused it. */
    private fun endIfEveryDeviceRefused(call: Call) {
        val ringing = call.ringing ?: return
        if (call.phase != CallPhase.OUTGOING || !call.refusals.keys.containsAll(ringing)) return
        val reasons = call.refusals.values
        val ended = when {
            CallEndReasons.BUSY in reasons -> CallEnded.BUSY
            CallEndReasons.NOT_PERMITTED in reasons -> CallEnded.NOT_PERMITTED
            else -> CallEnded.UNREACHABLE
        }
        // Every device said they don't allow it: believe them over an older message saying they did.
        if (reasons.all { it == CallEndReasons.NOT_PERMITTED }) runCatching { host.peerAllowsCalls(call.peer, false) }
        finish(call, ended, EndNotice(CallEndReasons.HANGUP, null))
    }

    /** The caller's end of the audio connection. */
    private suspend fun dialMedia(call: Call) {
        val (onion, keys) = synchronized(lock) {
            if (call.ended) return
            (call.peerDeviceOnion ?: return) to (call.keys ?: return)
        }
        val connection = try {
            host.connect(onion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record("call", "couldn't reach the device that answered (${e::class.java.simpleName})")
            finish(call, CallEnded.UNREACHABLE, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            return
        }
        synchronized(lock) {
            if (call.ended) {
                runCatching { connection.close() }
                return
            }
            call.connection = connection
        }
        val accepted = try {
            val sender = host.selfDevice().toByteArray(Charsets.UTF_8)
            val id = call.id.toByteArray(Charsets.UTF_8)
            DataOutputStream(connection.output).run {
                writeByte(FRAME_CALL)
                writeInt(sender.size)
                write(sender)
                writeInt(id.size)
                write(id)
                writeInt(keys.callerProof.size)
                write(keys.callerProof)
                flush()
            }
            val input = DataInputStream(connection.input)
            val type = input.readUnsignedByte()
            val proof = ByteArray(CallKeys.PROOF_BYTES).also(input::readFully)
            type == FRAME_CALL_ACCEPT && MessageDigest.isEqual(proof, keys.calleeProof)
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            DiagnosticLog.record("call", "the answering device didn't accept the audio connection")
            finish(call, CallEnded.FAILED, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            return
        }
        startMedia(call, connection, sendKey = keys.callerToCallee, receiveKey = keys.calleeToCaller)
    }

    /** Opens the phone's audio and starts the call's traffic. False if the call is over instead. */
    private fun startMedia(call: Call, connection: DuplexConnection, sendKey: ByteArray, receiveKey: ByteArray): Boolean {
        val session = try {
            (audio ?: throw IllegalStateException("No audio on this device")).open()
        } catch (e: Exception) {
            DiagnosticLog.record("call", "couldn't open the microphone or speaker (${e::class.java.simpleName})")
            // Tell them on the connection too, so they don't wait for it to time out.
            val cipher = runCatching { MediaCipher(sendKey) }.getOrNull()
            if (cipher != null) runCatching { writeRecord(connection, cipher, MediaRecord.hangup(CallCodecs.OPUS)) }
            finish(call, CallEnded.AUDIO_FAILED, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
            return false
        }
        synchronized(lock) {
            if (call.ended) {
                runCatching { session.close() }
                return false
            }
            val media = MediaSession(call, connection, MediaCipher(sendKey), MediaCipher(receiveKey), session)
            call.media = media
            call.phase = CallPhase.ACTIVE
            call.connectedAtNanos = System.nanoTime()
            runCatching { session.setSpeaker(call.speaker) }
            if (call.muted) runCatching { session.setMuted(true) }
            publish(call)
            media.start()
        }
        DiagnosticLog.record("call", "call connected")
        return true
    }

    /**
     * Ends [call]: records why, cancels what's waiting on it, and in the background stops its
     * audio, closes its connection, forgets its keys and tells the other side if [notice] says to.
     * Safe to call more than once and from anywhere, holding [lock] or not; only the first counts.
     */
    private fun finish(
        call: Call,
        reason: CallEnded,
        notice: EndNotice?,
        hangupInBand: Boolean = false,
        publishState: Boolean = true,
    ) {
        val jobs: List<Job>
        val held: List<String>
        synchronized(lock) {
            if (call.ended) return
            call.ended = true
            jobs = call.jobs.toList()
            held = call.held.toList()
            call.held.clear()
            if (current === call && publishState) {
                _state.value = CallState(
                    phase = CallPhase.ENDED,
                    peer = call.peer,
                    outgoing = call.outgoing,
                    ended = reason,
                )
            }
        }
        DiagnosticLog.record("call", "call ended: ${reason.name.lowercase()}")
        // The held connections are what the notice goes out on, so they're let go only after it
        // has gone. Released first, the connection could be closed under it, and a decline then
        // had to wait for a new one or was lost.
        val release: () -> Unit = {
            if (held.isNotEmpty()) runCatching { host.releaseConnections(held) }
        }
        if (notice != null) {
            sendEnd(call.peer, notice.devices, call.id, notice.reason, afterwards = release)
        } else {
            release()
        }
        scope.launch(Dispatchers.IO) {
            try {
                call.media?.stop(hangupInBand)
                runCatching { call.connection?.close() }
            } finally {
                call.wipe()
                call.done.complete(Unit)
            }
        }
        // Not before the teardown is launched: one of these may be the coroutine running this.
        jobs.forEach { it.cancel() }
        scope.launch {
            delay(ENDED_LINGER_MILLIS)
            synchronized(lock) {
                if (current === call) {
                    current = null
                    _state.value = CallState()
                }
            }
        }
    }

    /** Guarded by [lock]. Holds connections for [call] until it ends; see CallHost.holdConnections. */
    private fun holdLocked(call: Call, onions: List<String>) {
        if (call.ended || onions.isEmpty()) return
        call.held += onions
        runCatching { host.holdConnections(onions) }
    }

    /** Guarded by [lock]. */
    private fun publish(call: Call) {
        if (current !== call || call.ended) return
        _state.value = CallState(
            phase = call.phase,
            peer = call.peer,
            outgoing = call.outgoing,
            muted = call.muted,
            speaker = call.speaker,
            connectedAtNanos = call.connectedAtNanos,
            safetyCode = call.keys?.safetyCode,
            quality = call.quality,
        )
    }

    private fun phaseOf(call: Call): CallPhase = synchronized(lock) { if (call.ended) CallPhase.ENDED else call.phase }

    private fun isMuted(call: Call): Boolean = synchronized(lock) { call.muted }

    /**
     * Tells [account]'s [devices] (all of them when null) that [callId] is over, trying again until
     * each has it or [END_RETRY_MILLIS] have passed. Sent once, a decline lost on a bad circuit left
     * the caller ringing for a minute and unable to call again meanwhile. A copy that arrives twice
     * is harmless: an ended call ignores it.
     */
    private fun sendEnd(account: String, devices: Set<Int>?, callId: String, reason: Int, afterwards: (() -> Unit)? = null) {
        if (devices != null && devices.isEmpty()) {
            afterwards?.invoke()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                deliver(account, WireMessage.CallEnd(callId, reason), devices, END_RETRY_MILLIS) { true }
            } finally {
                afterwards?.invoke()
            }
        }
    }

    /**
     * Sends [wire] directly to [devices] of [account] (all of them when null), and again to those
     * that haven't acknowledged it, with growing pauses, until every one has, [windowMillis] have
     * passed, or [stillWanted] says it no longer matters. Returns the devices that have it.
     */
    private suspend fun deliver(
        account: String,
        wire: WireMessage,
        devices: Set<Int>?,
        windowMillis: Long,
        stillWanted: () -> Boolean,
    ): Set<Int> {
        val deadline = System.nanoTime() + windowMillis * 1_000_000
        val delivered = HashSet<Int>()
        var targets: Set<Int>? = devices
        var pause = RETRY_FIRST_PAUSE_MILLIS
        while (true) {
            val acknowledged = try {
                host.sendToDevices(account, wire, targets)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptySet()
            }
            delivered += acknowledged
            val owed = (targets ?: runCatching { host.deviceIds(account) }.getOrDefault(emptySet())) - delivered
            if (owed.isEmpty() || !stillWanted() || System.nanoTime() + pause * 1_000_000 > deadline) return delivered
            targets = owed
            delay(pause)
            if (!stillWanted()) return delivered
            pause = (pause * 2).coerceAtMost(RETRY_MAX_PAUSE_MILLIS)
        }
    }

    private fun randomSecret(): ByteArray = ByteArray(WireCodec.CALL_SECRET_BYTES).also(random::nextBytes)

    private fun writeRecord(connection: DuplexConnection, cipher: MediaCipher, plaintext: ByteArray) {
        val sealed = cipher.seal(plaintext)
        val framed = ByteArray(2 + sealed.size)
        framed[0] = (sealed.size ushr 8).toByte()
        framed[1] = sealed.size.toByte()
        System.arraycopy(sealed, 0, framed, 2, sealed.size)
        connection.output.write(framed)
        connection.output.flush()
    }

    private class EndNotice(val reason: Int, val devices: Set<Int>?)

    private class Call(
        val id: String,
        val peer: String,
        val outgoing: Boolean,
        val offerSecret: ByteArray,
        /** Our account, read when the call began. */
        val self: String,
    ) {
        var phase = CallPhase.IDLE
        var ended = false
        var muted = false
        var speaker = false
        var connectedAtNanos: Long? = null
        var quality = CallQuality.GOOD
        var answerSecret: ByteArray? = null
        var keys: CallKeys? = null

        /** Incoming: the device that rang us. Outgoing: the device that answered. */
        var peerDeviceId = -1
        var peerDeviceOnion: String? = null

        /** Outgoing: the devices the offer reached, once known, and those that turned it down. */
        var ringing: Set<Int>? = null
        val refusals = HashMap<Int, Int>()

        /** Device addresses whose connections are held for this call, released when it ends. */
        val held = ArrayList<String>()

        var connection: DuplexConnection? = null
        var media: MediaSession? = null
        val jobs: MutableList<Job> = java.util.Collections.synchronizedList(ArrayList())
        val done = CompletableDeferred<Unit>()

        fun wipe() {
            offerSecret.fill(0)
            answerSecret?.fill(0)
            keys?.wipe()
        }
    }

    /**
     * The traffic of a call that's connected: one thread captures and encodes, one seals and sends,
     * one receives and opens. Every record is the same size and they go out every 60 ms, muted or
     * not; see MediaRecord.
     */
    private inner class MediaSession(
        private val call: Call,
        private val connection: DuplexConnection,
        private val sendCipher: MediaCipher,
        private val receiveCipher: MediaCipher,
        val audio: CallAudioSession,
    ) {
        @Volatile private var running = true
        @Volatile private var hangupRequested = false
        @Volatile private var lastReceivedNanos = System.nanoTime()
        private val received = java.util.concurrent.atomic.AtomicLong()
        private val outgoing = ArrayBlockingQueue<Any>(SEND_QUEUE_RECORDS)
        private val codec = audio.codec
        private val threads = ArrayList<Thread>()
        private var watchdog: Job? = null

        fun start() {
            threads += thread("niix-call-capture") {
                while (running) {
                    val packets = audio.capture()
                    val item: Any = if (isMuted(call)) EMPTY_PACKETS else packets
                    // Behind on sending (a slow moment on the circuit): the oldest audio goes, so
                    // the delay doesn't keep growing.
                    while (!outgoing.offer(item)) outgoing.poll()
                }
            }
            threads += thread("niix-call-send") {
                while (true) {
                    val item = outgoing.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    if (item === HANGUP) {
                        writeRecord(connection, sendCipher, MediaRecord.hangup(codec))
                        break
                    }
                    // Stopping: wait for the hangup if one is coming, otherwise stop now.
                    if (!running) {
                        if (hangupRequested) continue else break
                    }
                    if (item == null) continue
                    @Suppress("UNCHECKED_CAST")
                    writeRecord(connection, sendCipher, MediaRecord.audio(codec, item as List<ByteArray>))
                }
            }
            threads += thread("niix-call-receive") {
                val input = DataInputStream(connection.input)
                while (running) {
                    val length = input.readUnsignedShort()
                    if (length !in MediaRecord.SEALED_SIZES) throw IOException("Unexpected record size")
                    val sealed = ByteArray(length).also(input::readFully)
                    val record = MediaRecord.decode(receiveCipher.open(sealed))
                    lastReceivedNanos = System.nanoTime()
                    received.incrementAndGet()
                    when (record.type) {
                        MediaRecord.TYPE_HANGUP -> {
                            finish(call, CallEnded.REMOTE_HUNG_UP, null)
                            return@thread
                        }
                        else -> if (record.packets.isNotEmpty()) audio.play(record.codec, record.packets)
                    }
                }
            }
            watchdog = scope.launch(Dispatchers.IO) {
                val meter = QualityMeter()
                val startedNanos = System.nanoTime()
                while (running) {
                    delay(WATCHDOG_INTERVAL_MILLIS)
                    val now = System.nanoTime()
                    if (now - lastReceivedNanos > SILENCE_TIMEOUT_MILLIS * 1_000_000) {
                        DiagnosticLog.record("call", "nothing heard from the other side for a while")
                        finish(call, CallEnded.CONNECTION_LOST, EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)))
                        return@launch
                    }
                    val quality = meter.sample(
                        receivedTotal = received.get(),
                        sinceLastMillis = (now - lastReceivedNanos) / 1_000_000,
                        elapsedMillis = (now - startedNanos) / 1_000_000,
                    )
                    synchronized(lock) {
                        if (!call.ended && call.quality != quality) {
                            call.quality = quality
                            publish(call)
                        }
                    }
                }
            }
        }

        /** Stops everything. With [hangup], tells the other side on the connection first. */
        fun stop(hangup: Boolean) {
            if (hangup) hangupRequested = true
            running = false
            watchdog?.cancel()
            // The capture thread first, so nothing it captures lands behind the hangup.
            threads.firstOrNull()?.join(STOP_WAIT_MILLIS)
            if (hangup) {
                outgoing.clear()
                outgoing.offer(HANGUP)
                threads.getOrNull(1)?.join(STOP_WAIT_MILLIS)
            }
            runCatching { connection.close() }
            threads.forEach { it.interrupt() }
            runCatching { audio.close() }
        }

        /** A thread whose failure ends the call, unless it's already ending. */
        private fun thread(name: String, body: () -> Unit): Thread =
            Thread {
                try {
                    body()
                } catch (e: Throwable) {
                    if (running) {
                        val audioFault = name == "niix-call-capture" && e !is IOException
                        DiagnosticLog.record("call", "$name stopped (${e::class.java.simpleName})")
                        finish(
                            call,
                            if (audioFault) CallEnded.AUDIO_FAILED else CallEnded.CONNECTION_LOST,
                            EndNotice(CallEndReasons.FAILED, setOf(call.peerDeviceId)),
                            hangupInBand = audioFault,
                        )
                    }
                }
            }.apply {
                isDaemon = true
                this.name = name
                start()
            }
    }

    internal companion object {
        /** Opens a call's audio connection, from the caller to the device that answered. */
        const val FRAME_CALL = 19

        /** The callee's reply, with its proof, before any audio. */
        const val FRAME_CALL_ACCEPT = 20

        private const val MAX_CALL_ID_BYTES = 64

        /**
         * How long a call rings before giving up. Generous, because reaching a phone over Tor can
         * take a good part of a minute on a bad circuit.
         */
        const val RING_TIMEOUT_MILLIS = 60_000L

        /** A little longer than the caller's, so the caller's own timeout normally decides. */
        const val INCOMING_RING_MILLIS = 70_000L

        /** From picking up to audio flowing. Opening a new connection to an onion can be slow. */
        const val CONNECT_TIMEOUT_MILLIS = 60_000L

        /**
         * An offer older than this, or this far in the future, by the caller's clock, isn't rung.
         * Room for clocks that disagree a little; not for an offer that waited in the locked inbox.
         */
        const val OFFER_MAX_AGE_MILLIS = 120_000L

        /** How long a call's ending keeps being retried to a device that hasn't taken it. */
        const val END_RETRY_MILLIS = 45_000L
        private const val RETRY_FIRST_PAUSE_MILLIS = 1_000L
        private const val RETRY_MAX_PAUSE_MILLIS = 8_000L

        /** Records arrive every 60 ms; this much silence means the connection is gone. */
        const val SILENCE_TIMEOUT_MILLIS = 20_000L
        internal const val WATCHDOG_INTERVAL_MILLIS = 500L

        /** About half a second of audio waiting to go out; more is dropped, oldest first. */
        private const val SEND_QUEUE_RECORDS = 8
        private const val POLL_MILLIS = 200L
        private const val STOP_WAIT_MILLIS = 700L

        /** How long an ended call's state is shown before going back to idle. */
        private const val ENDED_LINGER_MILLIS = 4_000L
        private const val MAX_SEEN_OFFERS = 64

        private val EMPTY_PACKETS: List<ByteArray> = emptyList()
        private val HANGUP = Any()
    }
}
