package app.niix

import android.os.SystemClock

class LockController(var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {

    @Volatile
    private var backgroundedAt: Long = 0L

    fun onBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun shouldLock(): Boolean =
        backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMillis

    fun reset() {
        backgroundedAt = 0L
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
