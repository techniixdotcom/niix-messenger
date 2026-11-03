package app.niix.core.messaging

import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        // SecureRandom, not kotlin.random: the point of jitter is that an observer cannot predict
        // when the next decoy goes out, and a predictable generator gives that away.
        val jitterFactor = MIN_JITTER_FACTOR + SECURE_RANDOM.nextDouble() * (MAX_JITTER_FACTOR - MIN_JITTER_FACTOR)
        return (meanIntervalMillis * jitterFactor).toLong().coerceAtLeast(MIN_DELAY_MILLIS)
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val MIN_JITTER_FACTOR = 0.5
        private const val MAX_JITTER_FACTOR = 1.5
        private const val MIN_DELAY_MILLIS = 1_000L
    }
}
