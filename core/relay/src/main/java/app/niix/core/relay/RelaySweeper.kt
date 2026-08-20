package app.niix.core.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Purges [RelayStore] entries past their TTL on an interval, same pattern as
 * [app.niix.core.messaging.ExpirySweeper] purges disappearing messages. This is what makes "gone
 * after TTL expiry even with no fetch/delete-receipt" true in practice rather than only in
 * theory -- without it, an unfetched envelope would sit in the map forever since nothing else
 * ever revisits it.
 */
class RelaySweeper(
    private val store: RelayStore,
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
        runCatching { store.purgeExpired(System.currentTimeMillis()) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS = 5L * 60 * 1000
    }
}
