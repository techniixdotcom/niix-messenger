package app.niix.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class InactivityClockTest {

    private var wall = 1_000_000_000_000L
    private var sinceBoot = 60_000L
    private var boot = 3

    private val clock = InactivityClock(bootCount = { boot }, sinceBoot = { sinceBoot }, wall = { wall })

    private fun pass(millis: Long) {
        wall += millis
        sinceBoot += millis
    }

    @Test
    fun `the first mark is the wall clock`() {
        assertEquals(wall, clock.advance(null).time)
    }

    @Test
    fun `winding the clock back doesn't stop time counting`() {
        val start = clock.advance(null)
        pass(DAY)
        wall -= 10 * DAY
        assertEquals(start.time + DAY, clock.advance(start).time)
    }

    @Test
    fun `a clock set forward is believed`() {
        val start = clock.advance(null)
        wall += 5 * DAY
        assertEquals(start.time + 5 * DAY, clock.advance(start).time)
    }

    @Test
    fun `time counted before a reboot is kept, and the new boot adds its own`() {
        val start = clock.advance(null)
        pass(2 * DAY)
        val saved = clock.advance(start)
        // Reboot, with the clock wound back a month while it was off.
        boot++
        sinceBoot = 0
        wall -= 30 * DAY
        sinceBoot += DAY
        wall += DAY
        assertEquals(start.time + 3 * DAY, clock.advance(saved).time)
    }

    @Test
    fun `an old mark with no boot to go on adds nothing until it gets one`() {
        val old = InactivityClock.Mark(wall, -1, 0)
        pass(DAY)
        wall -= 10 * DAY
        val now = clock.advance(old)
        assertEquals(old.time, now.time)
        pass(DAY)
        assertEquals(old.time + DAY, clock.advance(now).time)
    }

    @Test
    fun `without a boot count the counter isn't trusted across marks`() {
        boot = -1
        val start = clock.advance(null)
        pass(DAY)
        wall -= 10 * DAY
        assertEquals(start.time, clock.advance(start).time)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
