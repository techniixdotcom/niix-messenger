package app.niix.desktop.ui

import app.niix.desktop.DesktopContainer
import org.gnome.adw.ApplicationWindow
import org.gnome.glib.GLib

/**
 * Locks NiiX by itself when it has a passcode: a minute after its window is closed to the
 * background, and five minutes after the window was last in front. Locking closes the database
 * and drops its key from memory; messages that arrive meanwhile wait, still encrypted, for the
 * next unlock.
 *
 * As on the phone, a message just sent that hasn't reached anyone yet holds the lock back for a
 * few minutes, since nothing is sent while locked, and a call in progress holds it for as long as
 * it lasts.
 */
class AutoLock(
    private val container: DesktopContainer,
    private val window: () -> ApplicationWindow?,
    private val lock: () -> Unit,
) {

    private var hiddenSince = 0L
    private var inactiveSince = 0L
    private var started = false

    fun attach(window: ApplicationWindow) {
        if (started) return
        started = true
        GLib.timeoutAddSeconds(GLib.PRIORITY_DEFAULT, CHECK_SECONDS) {
            runCatching { check() }
            GLib.SOURCE_CONTINUE
        }
        window.onNotify("is-active") { if (window.isActive) recordUse() }
    }

    private fun recordUse() {
        inactiveSince = 0L
        hiddenSince = 0L
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    private fun check() {
        val appLock = container.storage.appLock
        if (!appLock.isPasscodeEnabled() || !appLock.isUnlocked()) {
            recordUse()
            return
        }
        val w = window() ?: return
        val t = now()
        if (!w.visible) {
            if (hiddenSince == 0L) hiddenSince = t
        } else {
            hiddenSince = 0L
        }
        if (!w.isActive) {
            if (inactiveSince == 0L) inactiveSince = t
        } else {
            inactiveSince = 0L
            return
        }
        val due = (hiddenSince != 0L && t - hiddenSince >= HIDDEN_TIMEOUT_MILLIS) ||
            (inactiveSince != 0L && t - inactiveSince >= INACTIVE_TIMEOUT_MILLIS)
        if (!due) return
        if (runCatching { container.conversations.calls.isInCall() }.getOrDefault(false)) return
        val overdue = (hiddenSince != 0L && t - hiddenSince >= HIDDEN_TIMEOUT_MILLIS + MAX_HOLD_MILLIS) ||
            (inactiveSince != 0L && t - inactiveSince >= INACTIVE_TIMEOUT_MILLIS + MAX_HOLD_MILLIS)
        if (!overdue && runCatching { container.conversations.hasFreshUnsent() }.getOrDefault(false)) return
        recordUse()
        lock()
    }

    private companion object {
        const val CHECK_SECONDS = 10
        const val HIDDEN_TIMEOUT_MILLIS = 60_000L
        const val INACTIVE_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAX_HOLD_MILLIS = 5 * 60_000L
    }
}
