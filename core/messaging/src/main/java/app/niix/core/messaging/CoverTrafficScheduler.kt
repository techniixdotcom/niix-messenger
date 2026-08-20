package app.niix.core.messaging

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Calls [sendDummy] (in practice, [ConversationManager.sendCoverTraffic]) at a jittered interval
 * around [meanIntervalMillis], for as long as [start] has been called and [stop] hasn't. This is
 * the timing half of cover traffic; the sizing/indistinguishability half is
 * [ConversationManager.sendCoverTraffic] itself going through the exact same encode/pad/encrypt
 * path a real message does.
 *
 * The goal is that, to anyone who can only observe Tor circuit traffic shape and timing (not
 * decrypt it), periods where the person is actively messaging and periods where they aren't look
 * the same. A *fixed* interval would itself be a fingerprint (a metronome-regular connection is
 * obviously not a human typing), so every delay is independently randomized in [0.5x, 1.5x] of
 * the mean -- this isn't a true Poisson process, but it's enough to remove the one glaring
 * giveaway a naive fixed-period scheme would have, without the complexity of modeling real
 * human messaging cadence.
 *
 * Off by default -- see `SettingsStore.KEY_COVER_TRAFFIC_ENABLED` -- since this trades battery
 * and a small amount of bandwidth for the privacy property, and that trade should be the
 * person's choice, not forced on everyone.
 */
class CoverTrafficScheduler(
    private val meanIntervalMillis: Long,
    private val sendDummy: suspend () -> Unit,
) {

    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(nextDelayMillis())
                runCatching { sendDummy() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = job?.isActive == true

    private fun nextDelayMillis(): Long {
        val jitterFactor = MIN_JITTER_FACTOR + Random.nextDouble() * (MAX_JITTER_FACTOR - MIN_JITTER_FACTOR)
        return (meanIntervalMillis * jitterFactor).toLong().coerceAtLeast(MIN_DELAY_MILLIS)
    }

    companion object {
        private const val MIN_JITTER_FACTOR = 0.5
        private const val MAX_JITTER_FACTOR = 1.5
        private const val MIN_DELAY_MILLIS = 1_000L
    }
}
