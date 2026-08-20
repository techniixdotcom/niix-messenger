package app.niix.core.relay

import app.niix.core.relay.RelayProtocol.toHex

/**
 * Allows at most [maxPerWindow] relay-store requests within any rolling [windowMillis] window,
 * *per validated sender identity key* -- never per connection or IP, since Tor has no stable IP
 * anyway (build spec item 11.5). Mirrors the shape of
 * [app.niix.core.messaging.SlidingWindowLimiter] but keyed, since that one only tracks a single
 * global counter.
 *
 * Thread-safe: [RelayConnectionHandler] processes each inbound connection on its own coroutine.
 */
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

    /** Drops tracking for keys that have had no activity in a while, so this map doesn't grow
     * unbounded over a long-running relay's lifetime. Safe to call periodically alongside
     * [RelaySweeper]. */
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
