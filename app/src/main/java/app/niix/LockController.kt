package app.niix

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Locks the app once it has been in the background for a minute, without waiting for it to be
 * opened again. Locking closes the database and drops its key from memory, so a memory dump of an
 * unlocked phone no longer finds it. Messages that arrive while locked wait, still encrypted, in
 * the locked inbox until the next unlock.
 *
 * Counts started screens rather than reacting to one screen stopping: moving from one screen to
 * the next stops the first while the app is still in front.
 *
 * Nothing is sent while locked, since unsent messages are in the database. So when [holdLock] says
 * a message just sent is still with nobody, locking waits for it, for at most [MAX_HOLD_MILLIS]
 * more.
 */
class LockController(
    private val lockApp: () -> Unit,
    private val holdLock: () -> Boolean,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { lockOrHold() }
    private var startedScreens = 0

    @Volatile
    private var backgroundedAt: Long = 0L

    @Volatile
    private var holding = false

    private fun lockOrHold() {
        val within = SystemClock.elapsedRealtime() - backgroundedAt < TIMEOUT_MILLIS + MAX_HOLD_MILLIS
        if (within && runCatching(holdLock).getOrDefault(false)) {
            holding = true
            handler.postDelayed(lockNow, HOLD_RECHECK_MILLIS)
        } else {
            lockApp()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedScreens++ == 0) handler.removeCallbacks(lockNow)
    }

    override fun onActivityStopped(activity: Activity) {
        if (--startedScreens == 0 && !activity.isChangingConfigurations) {
            backgroundedAt = SystemClock.elapsedRealtime()
            holding = false
            handler.postDelayed(lockNow, TIMEOUT_MILLIS)
        }
    }

    /**
     * Checked when a screen comes back, for when the timer didn't get to run: its clock stops
     * while the phone is asleep. Coming back during a hold doesn't lock, as the timer wouldn't have.
     */
    fun shouldLock(): Boolean =
        backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt >= TIMEOUT_MILLIS + (if (holding) MAX_HOLD_MILLIS else 0L)

    fun reset() {
        backgroundedAt = 0L
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val MAX_HOLD_MILLIS = 5 * 60_000L
        const val HOLD_RECHECK_MILLIS = 15_000L
    }
}
