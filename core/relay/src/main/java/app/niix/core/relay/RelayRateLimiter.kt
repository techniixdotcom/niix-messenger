package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex

class RelayRateLimiter(
    private val maxPerWindow: Int = RelayProtocol.DEFAULT_MAX_STORES_PER_SENDER_PER_HOUR,
    private val windowMillis: Long = RelayProtocol.RATE_LIMIT_WINDOW_MILLIS,
) {

    private val eventsByKey = HashMap<String, ArrayDeque<Long>>()

    /**
     * Whether this sender is within its rate.
     *
     * The default clock is elapsedRealtime, not the wall clock. This measures how long ago
     * something happened on this device, and the wall clock can jump: a time sync moving it
     * forward would age out every recorded event at once and hand a flooding sender a fresh
     * allowance. Callers pass an explicit value in tests.
     */
    @Synchronized
    fun allow(senderIdKey: ByteArray, nowMillis: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        val key = senderIdKey.toHex()
        val events = eventsByKey.getOrPut(key) { ArrayDeque() }
        while (events.isNotEmpty() && nowMillis - events.first() > windowMillis) {
            events.removeFirst()
        }
        if (events.size >= maxPerWindow) return false
        events.addLast(nowMillis)
        return true
    }

    @Synchronized
    fun pruneStale(nowMillis: Long = android.os.SystemClock.elapsedRealtime()) {
        val it = eventsByKey.entries.iterator()
        while (it.hasNext()) {
            val events = it.next().value
            while (events.isNotEmpty() && nowMillis - events.first() > windowMillis) {
                events.removeFirst()
            }
            if (events.isEmpty()) it.remove()
        }
    }
}
