package app.niix.core.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityMeterTest {

    private val tick = CallManager.WATCHDOG_INTERVAL_MILLIS
    private val perTick = tick / MediaRecord.RECORD_MILLIS

    /** Feeds [ticks] samples where [perTickArrived] records arrive per tick; returns the last result. */
    private fun QualityMeter.run(
        ticks: Int,
        perTickArrived: Long,
        startTick: Int,
        startTotal: Long,
        sinceLast: (Int) -> Long = { 60 },
    ): Triple<CallQuality, Int, Long> {
        var total = startTotal
        var result = CallQuality.GOOD
        for (i in startTick until startTick + ticks) {
            total += perTickArrived
            result = sample(total, sinceLast(i), (i + 1) * tick)
        }
        return Triple(result, startTick + ticks, total)
    }

    @Test
    fun `steady records are good`() {
        val (q, _, _) = QualityMeter().run(20, perTick, 0, 0)
        assertEquals(CallQuality.GOOD, q)
    }

    @Test
    fun `half the records arriving is poor`() {
        val (q, _, _) = QualityMeter().run(20, perTick / 2, 0, 0)
        assertEquals(CallQuality.POOR, q)
    }

    @Test
    fun `a long silence is a stall, at once`() {
        val meter = QualityMeter()
        val (_, t, total) = meter.run(10, perTick, 0, 0)
        assertEquals(CallQuality.STALLED, meter.sample(total, QualityMeter.STALLED_AFTER_MILLIS, (t + 1) * tick))
    }

    @Test
    fun `recovery is only shown once it has lasted`() {
        val meter = QualityMeter()
        val (_, t1, total1) = meter.run(10, perTick / 3, 0, 0)
        // Good again: still poor for a moment, then good.
        val first = meter.sample(total1 + perTick, 60, (t1 + 1) * tick)
        assertEquals(CallQuality.POOR, first)
        val (later, _, _) = meter.run(12, perTick, t1 + 1, total1 + perTick)
        assertEquals(CallQuality.GOOD, later)
    }

    @Test
    fun `the start of a call isn't judged on a window it hasn't had yet`() {
        val meter = QualityMeter()
        assertEquals(CallQuality.GOOD, meter.sample(1, 60, tick))
    }
}
