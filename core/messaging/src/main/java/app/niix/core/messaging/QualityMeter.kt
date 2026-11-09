package app.niix.core.messaging

/**
 * Turns how the other side's records are arriving into a [CallQuality], sampled every
 * [CallManager.WATCHDOG_INTERVAL_MILLIS].
 *
 * Records come every [MediaRecord.RECORD_MILLIS] whatever is said, muted included, so how many
 * arrived lately is a direct measure of the circuit, with no guessing about speech. It gets worse
 * at once and better only after a while of arriving on time, so the indicator doesn't flicker on
 * a circuit that's borderline.
 */
internal class QualityMeter {

    /** Total records received at each of the last [WINDOW_SAMPLES] samples, oldest first. */
    private val totals = ArrayDeque<Long>()
    private var current = CallQuality.GOOD
    private var goodSince = 0L

    fun sample(receivedTotal: Long, sinceLastMillis: Long, elapsedMillis: Long): CallQuality {
        totals.addLast(receivedTotal)
        while (totals.size > WINDOW_SAMPLES + 1) totals.removeFirst()

        // Over the window, or since the call started if that's shorter.
        val windowMillis = minOf(elapsedMillis, (totals.size - 1) * CallManager.WATCHDOG_INTERVAL_MILLIS)
        val arrived = receivedTotal - totals.first()
        val expected = windowMillis / MediaRecord.RECORD_MILLIS
        val share = if (expected > 0) arrived.toDouble() / expected else 1.0

        val measured = when {
            sinceLastMillis >= STALLED_AFTER_MILLIS -> CallQuality.STALLED
            sinceLastMillis >= LATE_AFTER_MILLIS || (windowMillis >= MIN_WINDOW_MILLIS && share < POOR_SHARE) ->
                CallQuality.POOR
            else -> CallQuality.GOOD
        }
        current = when {
            measured.ordinal >= current.ordinal -> {
                goodSince = elapsedMillis
                measured
            }
            // Better than shown: only once it has stayed better for a while.
            elapsedMillis - goodSince >= RECOVER_AFTER_MILLIS -> measured
            else -> current
        }
        return current
    }

    companion object {
        /** Three seconds of samples. */
        private const val WINDOW_SAMPLES = 6
        private const val MIN_WINDOW_MILLIS = 1_500L

        /** Less than this share of the records due arrived: patchy. */
        private const val POOR_SHARE = 0.8

        /** Nothing for this long: late. Six records in a row didn't come. */
        private const val LATE_AFTER_MILLIS = 400L

        /** Nothing for this long: stalled. */
        const val STALLED_AFTER_MILLIS = 3_000L

        /** How long it must be better before the indicator says so. */
        private const val RECOVER_AFTER_MILLIS = 2_000L
    }
}
