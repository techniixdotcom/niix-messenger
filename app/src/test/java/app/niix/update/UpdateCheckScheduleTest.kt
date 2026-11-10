package app.niix.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class UpdateCheckScheduleTest {

    @Test
    fun `the next check is 20 to 28 hours away`() {
        val random = Random(1)
        repeat(1_000) {
            val hours = UpdateChecker.nextCheckDelayMillis(random) / 3_600_000.0
            assertTrue("$hours hours", hours >= 20.0 && hours < 28.0)
        }
    }

    @Test
    fun `the time moves around instead of repeating`() {
        val random = Random(2)
        val delays = List(50) { UpdateChecker.nextCheckDelayMillis(random) }.toSet()
        assertTrue(delays.size > 45)
    }
}
