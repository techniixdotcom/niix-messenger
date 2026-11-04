package app.niix.core.storage

/**
 * A wall clock that never goes backwards.
 *
 * Disappearing messages expire by comparing a timestamp to the current time, so someone holding the
 * phone could set the clock back a year and keep them. This remembers the latest time it has seen
 * and never reports anything earlier. Moving the clock forward still works.
 *
 * elapsedRealtime won't do because expiry times are stored and have to survive a reboot. Timestamps
 * from other devices don't go through this; a sender's time has to be taken as sent.
 */
class MonotonicClock internal constructor(private val settings: KeyValueStore) {

    @Volatile
    private var highWater: Long? = null

    @Volatile
    private var persisted = 0L

    /**
     * The current time, never earlier than the latest already seen. Kept in memory and written to
     * the database at most once a minute, so this isn't a write on every call. A crash loses at
     * most that minute, which only matters to someone rolling the clock back by less than that.
     */
    fun now(): Long {
        val seen = highWater ?: load()
        val wall = System.currentTimeMillis()
        if (wall <= seen) return seen
        highWater = wall
        if (wall - persisted >= PERSIST_STEP_MILLIS) {
            runCatching { settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, wall.toString()) }
                .onSuccess { persisted = wall }
        }
        return wall
    }

    /**
     * Drops a high-water mark far ahead of the wall clock, once the caller knows the wall clock is
     * about right. A clock that once ran years fast (a dead clock battery, a mistyped date) would
     * otherwise leave every timestamp and expiry stuck in that future for good. Tor only connects
     * with a clock within about a day of the real time, so a working Tor connection is the check;
     * winding the clock back further than [MAX_AHEAD_MILLIS] to keep messages breaks Tor, and then
     * this is never called.
     */
    fun dropFutureMark() {
        val wall = System.currentTimeMillis()
        val seen = highWater ?: load()
        if (seen - wall <= MAX_AHEAD_MILLIS) return
        highWater = wall
        runCatching { settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, wall.toString()) }.onSuccess { persisted = wall }
    }

    /** Whether the device clock reads earlier than a time we've already seen. */
    fun looksRolledBack(): Boolean = System.currentTimeMillis() < (highWater ?: load())

    private fun load(): Long {
        val stored = settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)?.toLongOrNull() ?: 0L
        persisted = stored
        highWater = maxOf(highWater ?: 0L, stored)
        return highWater ?: stored
    }

    private companion object {
        const val PERSIST_STEP_MILLIS = 60_000L
        const val MAX_AHEAD_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
