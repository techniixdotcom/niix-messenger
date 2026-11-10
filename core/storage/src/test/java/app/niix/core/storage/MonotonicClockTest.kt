package app.niix.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MonotonicClock]: expiry can't be put off by changing the device clock. These run
 * against the real system clock, so they check the relationship between what's stored and what's
 * reported rather than absolute times.
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
        // A mark far in the future, as if the clock had been wound back.
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
        // Only going backwards is resisted. A real forward correction has to be accepted.
        val settings = FakeSettings()
        val past = System.currentTimeMillis() - 60_000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, past.toString())

        val clock = MonotonicClock(settings)
        assertTrue(clock.now() > past)
    }

    @Test
    fun `does not write on every call`() {
        // Expiry checks call now() constantly; a database write each time was most of their cost.
        val settings = FakeSettings()
        val clock = MonotonicClock(settings)
        clock.now()
        val first = settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)
        Thread.sleep(5)
        clock.now()
        assertTrue("the mark was rewritten within the same minute", settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER) == first)
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

    @Test
    fun `a mark far in the future is dropped once the wall clock is known to be right`() {
        val settings = FakeSettings()
        val future = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, future.toString())

        val clock = MonotonicClock(settings)
        clock.dropFutureMark()
        assertTrue("the clock stayed a year ahead", clock.now() < future)
        assertTrue("the dropped mark wasn't saved", settings.getString(SettingsStore.KEY_CLOCK_HIGH_WATER)!!.toLong() < future)
    }

    @Test
    fun `a mark only a little ahead is kept, so winding the clock back a day still doesn't work`() {
        val settings = FakeSettings()
        val aDayAhead = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        settings.setString(SettingsStore.KEY_CLOCK_HIGH_WATER, aDayAhead.toString())

        val clock = MonotonicClock(settings)
        clock.dropFutureMark()
        assertTrue(clock.now() >= aDayAhead)
    }
}
