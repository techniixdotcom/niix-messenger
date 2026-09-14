package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex

class RelayRateLimiter(
    private val maxPerWindow: Int = RelayProtocol.DEFAULT_MAX_STORES_PER_SENDER_PER_HOUR,
    private val windowMillis: Long = RelayProtocol.RATE_LIMIT_WINDOW_MILLIS,
) {

    private val eventsByKey = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(senderIdKey: ByteArray, nowMillis: Long = System.currentTimeMillis()): Boolean {
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
    fun pruneStale(nowMillis: Long = System.currentTimeMillis()) {
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
