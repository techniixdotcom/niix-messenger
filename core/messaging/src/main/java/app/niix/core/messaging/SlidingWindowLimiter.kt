package app.niix.core.messaging

/**
 * Allows at most [maxWeight] total weight within any rolling [windowMillis] window; [allow]
 * returns false once that cap is hit until old events age out of the window. Each call to
 * [allow] defaults to a weight of 1, so it doubles as a plain event-count limiter (e.g. "at
 * most N connections per window") or, with an explicit weight, a volume limiter (e.g. "at most
 * N attachment bytes accepted per window").
 *
 * Thread-safe: handleConnection() in ConversationManager runs each inbound connection on its
 * own coroutine, so more than one caller can hit this concurrently.
 */
class SlidingWindowLimiter(private val maxWeight: Long, private val windowMillis: Long) {

    private data class Event(val atMillis: Long, val weight: Long)

    private val events = ArrayDeque<Event>()
    private var currentWeight = 0L

    @Synchronized
    fun allow(weight: Long = 1, nowMillis: Long = System.currentTimeMillis()): Boolean {
        while (events.isNotEmpty() && nowMillis - events.first().atMillis > windowMillis) {
            currentWeight -= events.removeFirst().weight
        }
        if (currentWeight + weight > maxWeight) return false
        events.addLast(Event(nowMillis, weight))
        currentWeight += weight
        return true
    }
}
