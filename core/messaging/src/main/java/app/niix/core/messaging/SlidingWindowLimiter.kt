package app.niix.core.messaging

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

    /**
     * True when nothing falls inside the window, meaning the full budget is available.
     *
     * A limiter in that state behaves exactly like a brand-new one, which is what makes it safe to
     * discard and recreate later. Uses the same expiry rule as allow(), so a clock that has moved
     * backwards leaves old events in place and the limiter is not reported idle.
     */
    @Synchronized
    fun isIdle(nowMillis: Long = System.currentTimeMillis()): Boolean {
        while (events.isNotEmpty() && nowMillis - events.first().atMillis > windowMillis) {
            currentWeight -= events.removeFirst().weight
        }
        return events.isEmpty()
    }
}
