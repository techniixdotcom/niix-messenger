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
}
