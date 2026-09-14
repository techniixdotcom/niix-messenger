package app.niix.core.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RelaySweeper(
    private val store: RelayStore,
    /** Optional so existing callers and tests are unaffected; when supplied, dead routing
     * entries are pruned on the same schedule as expired envelopes. */
    private val routingTable: RoutingTable? = null,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {

    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sweepOnce()
                delay(intervalMillis)
            }
        }
    }

    fun sweepOnce() {
        val now = System.currentTimeMillis()
        runCatching { store.purgeExpired(now) }
 // Routing entries expire too. A bucket that filled early otherwise keeps nodes that
 // stopped existing long ago while refusing the ones still running.
        runCatching { routingTable?.pruneStale(now) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS = 5L * 60 * 1000
    }
}
