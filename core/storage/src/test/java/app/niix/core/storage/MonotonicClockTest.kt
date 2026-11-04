package app.niix.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MonotonicClock].
 *
 * The property being checked is that expiry cannot be postponed by changing the device clock.
 * Absolute times are not asserted, since the tests run against the real system clock; what
 * matters is the relationship between what is stored and what is reported.
 */
class MonotonicClockTest {

    @Test
    fun `records a high-water mark on first use`() {
        val settings = FakeSettings()
        val clock = MonotonicClock(settings)
        val reported = clock.now()
        val stored = settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)?.toLongOrNull()
        assertTrue("nothing was recorded", stored != null)
        assertTrue("reported time is below the mark it just set", reported >= stored!!)
    }

    @Test
    fun `never reports earlier than a time already seen`() {
        val settings = FakeSettings()
        // A mark far in the future, as if the clock had been wound back after being correct.
        val future = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, future.toString())

        val clock = MonotonicClock(settings)
        assertTrue(
            "a wound-back clock was reported as the current time, so expiry could be postponed",
            clock.now() >= future,
        )
    }

    @Test
    fun `moving the clock forward is honoured`() {
        // Only backwards movement is resisted. A genuine correction forward has to be accepted,
        // or a device with a slow clock would never expire anything again.
        val settings = FakeSettings()
        val past = System.currentTimeMillis() - 60_000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, past.toString())

        val clock = MonotonicClock(settings)
        assertTrue(clock.now() > past)
    }

    @Test
    fun `detects a rolled back clock`() {
        val settings = FakeSettings()
        val future = System.currentTimeMillis() + 60L * 60 * 1000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, future.toString())
        assertTrue(MonotonicClock(settings).looksRolledBack())
    }

    @Test
    fun `does not report a rollback on a normal clock`() {
        val settings = FakeSettings()
        val clock = MonotonicClock(settings)
        clock.now()
        assertFalse(clock.looksRolledBack())
    }

    @Test
    fun `reports no rollback before anything has been recorded`() {
        assertFalse(MonotonicClock(FakeSettings()).looksRolledBack())
    }
}
