package app.niix.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import app.niix.NiixApp
import app.niix.R
import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.SettingsStore

/**
 * Whether this phone can actually ring for a call, and the way to fix it if not.
 *
 * Two things stand in the way. Notifications turned off for NiiX, which silences calls entirely.
 * And, from Android 14, the "full-screen notifications" permission: without it the system shows an
 * incoming call as an ordinary banner and tucks it away after a few seconds, so the call seems to
 * vanish while it is in fact still ringing in the notification shade (NiiX brings the banner back
 * every few seconds, so a call can still be answered without it).
 *
 * Asked about at most once per run of the app, and never again once the person says so, except
 * right after they turn calls on for someone. After sending them to the setting, the result is
 * checked when they come back, so a setting that didn't take is noticed instead of being asked
 * about again on the next start as if for the first time.
 */
object CallReadiness {

    /** Asked at most once per run of the app, so it's a reminder rather than nagging. */
    @Volatile private var askedThisRun = false

    /** Sent to a settings screen and not back yet; checked on return. */
    @Volatile private var awaitingSettings: Problem? = null

    private enum class Problem { NOTIFICATIONS_OFF, FULL_SCREEN_OFF }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True where a ringing call can stay on screen until it's answered or declined. */
    fun canRingFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }.getOrDefault(false)
    }

    private fun problem(context: Context): Problem? = when {
        !notificationsAllowed(context) -> Problem.NOTIFICATIONS_OFF
        !canRingFullScreen(context) -> Problem.FULL_SCREEN_OFF
        else -> null
    }

    /**
     * Offers to fix whatever would stop calls ringing properly. [force] asks even if it was asked
     * already this run or the person chose not to be asked, for when they've just turned calls on.
     */
    fun promptIfNeeded(activity: Activity, force: Boolean = false) {
        val problem = problem(activity) ?: return
        if (!force && (askedThisRun || dismissed(activity))) return
        askedThisRun = true
        logState(activity)
        val message = when (problem) {
            Problem.NOTIFICATIONS_OFF -> R.string.call_ring_notifications_off
            Problem.FULL_SCREEN_OFF -> R.string.call_ring_full_screen
        }
        niixDialog(activity)
            .setTitle(R.string.call_ring_title)
            .setMessage(message)
            .setPositiveButton(R.string.call_ring_open_settings) { _, _ -> openSettings(activity, problem) }
            .setNeutralButton(R.string.call_ring_dont_ask) { _, _ -> setDismissed(activity) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Called when a screen that may have sent the person to settings comes back. Says whether it
     * worked; if it didn't, says exactly where the switch is, since phone makers move it.
     */
    fun onReturn(activity: Activity) {
        val sentFor = awaitingSettings ?: return
        awaitingSettings = null
        val problem = problem(activity)
        logState(activity)
        if (problem == null) {
            Toast.makeText(activity, R.string.call_ring_now_on, Toast.LENGTH_LONG).show()
            return
        }
        if (problem != sentFor) {
            // Fixed one thing, the other is still off: offer that one.
            promptIfNeeded(activity, force = true)
            return
        }
        niixDialog(activity)
            .setTitle(R.string.call_ring_title)
            .setMessage(
                when (problem) {
                    Problem.NOTIFICATIONS_OFF -> R.string.call_ring_notifications_still_off
                    Problem.FULL_SCREEN_OFF -> R.string.call_ring_full_screen_still_off
                },
            )
            .setPositiveButton(R.string.call_ring_try_again) { _, _ -> openSettings(activity, problem, appDetails = true) }
            .setNeutralButton(R.string.call_ring_dont_ask) { _, _ -> setDismissed(activity) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Records in the diagnostics what Android reports, and who installed this copy of NiiX. An
     * update installed by anything but NiiX itself (a file manager, adb) gets the full-screen
     * permission reset by Android, and that's the usual reason it keeps coming back.
     */
    private fun logState(context: Context) {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        val installedBy = when (installer) {
            null -> "unknown"
            context.packageName -> "NiiX's own updater"
            else -> "another app"
        }
        DiagnosticLog.record(
            "call",
            "ringing: notifications ${if (notificationsAllowed(context)) "on" else "off"}, " +
                "full-screen ${if (canRingFullScreen(context)) "allowed" else "not allowed"}, " +
                "Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER}, installed by $installedBy",
        )
    }

    private fun dismissed(context: Context): Boolean = runCatching {
        (context.applicationContext as NiixApp).container.storage.settings
            .getBool(SettingsStore.KEY_CALL_RING_PROMPT_DISMISSED, false)
    }.getOrDefault(false)

    private fun setDismissed(context: Context) {
        runCatching {
            (context.applicationContext as NiixApp).container.storage.settings
                .setBool(SettingsStore.KEY_CALL_RING_PROMPT_DISMISSED, true)
        }
    }

    private fun openSettings(activity: Activity, problem: Problem, appDetails: Boolean = false) {
        awaitingSettings = problem
        val opened = when {
            appDetails -> false
            problem == Problem.NOTIFICATIONS_OFF -> start(
                activity,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
            Build.VERSION.SDK_INT >= 34 -> start(
                activity,
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}")),
            )
            else -> false
        }
        // Some phones don't have the direct page; NiiX's own app page is everywhere, and the
        // switch is somewhere under it.
        if (!opened && !start(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))) {
            awaitingSettings = null
        }
    }

    private fun start(activity: Activity, intent: Intent): Boolean =
        runCatching { activity.startActivity(intent) }.isSuccess
}
