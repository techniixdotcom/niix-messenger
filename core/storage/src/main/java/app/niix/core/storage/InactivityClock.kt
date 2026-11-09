package app.niix.core.storage

/**
 * Time for the inactivity wipe that winding the phone's clock back doesn't stop.
 *
 * The wall clock is whatever whoever holds the phone says it is. Android's time since boot isn't:
 * nobody can set it, and it keeps counting through deep sleep. So this keeps a [Mark], a time known
 * to have passed plus where the since-boot counter stood then, and moves it forward by what the
 * counter has done since. A clock set back simply stops counting; the counter carries on and the
 * wipe still comes. A clock set forward is believed, since that only brings the wipe sooner.
 *
 * Across a reboot the counter starts from zero, but everything this boot has counted happened
 * after the last mark, so it's still added. What can't be counted is time the phone spent switched
 * off while its clock was also wound back. Nothing on an offline phone knows how long that was.
 */
class InactivityClock(
    private val bootCount: () -> Int,
    private val sinceBoot: () -> Long,
    private val wall: () -> Long = System::currentTimeMillis,
) {

    /** A time that had certainly passed, taken at [sinceBoot] into boot number [boot] (-1 if unknown). */
    data class Mark(val time: Long, val boot: Int, val sinceBoot: Long)

    /** The mark moved forward to now. With no earlier mark, now is the wall clock's word. */
    fun advance(previous: Mark?): Mark {
        val boot = bootCount()
        val counter = sinceBoot()
        val counted = when {
            previous == null -> wall()
            boot >= 0 && boot == previous.boot -> previous.time + (counter - previous.sinceBoot).coerceAtLeast(0)
            // A later boot: all of this one happened after the mark.
            boot >= 0 && previous.boot >= 0 && boot > previous.boot -> previous.time + counter
            // Can't tell how the mark relates to this boot (an old record, or no boot count), so
            // add nothing rather than guess. The next mark has a boot to go on.
            else -> previous.time
        }
        return Mark(maxOf(counted, wall()), boot, counter)
    }
}
