package app.niix.core.storage

/**
 * A wall clock that never appears to go backwards.
 *
 * Disappearing messages are a security feature, and their expiry is a timestamp compared against
 * the current time. Someone holding the device can set the clock back a year, and messages that
 * should have been destroyed simply are not — which is exactly the seized-device case the feature
 * exists for. The passcode throttle already resisted this; data lifetime did not.
 *
 * The highest time ever observed is remembered, and the current time is never reported as lower
 * than that. Moving the clock forward still works, because a legitimate correction or a genuinely
 * later time should be honoured; moving it back gains nothing.
 *
 * elapsedRealtime is not an option here: expiry timestamps are persisted and have to survive a
 * reboot, and elapsedRealtime resets to zero when the device restarts.
 *
 * Deliberately not used for timestamps that cross devices. A message's sent time comes from the
 * sender and has to be compared as it is; clamping it here would misrepresent what the sender
 * said.
 */
class MonotonicClock internal constructor(private val settings: KeyValueStore) {

    /**
     * The current time, never earlier than the highest already seen.
     *
     * The high-water mark is only written when it moves, so this does not put a database write in
     * front of every expiry check.
     */
    fun now(): Long {
        val wall = System.currentTimeMillis()
        val seen = settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)?.toLongOrNull() ?: 0L
        if (wall > seen) {
            runCatching { settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, wall.toString()) }
            return wall
        }
        return seen
    }

    /** Whether the device clock currently reads earlier than a time already observed. */
    fun looksRolledBack(): Boolean {
        val seen = settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() < seen
    }
}
