package app.niix.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.niix.R

/**
 * Whether this phone can actually ring for a call, and the way to fix it if not.
 *
 * Two things stand in the way. Notifications turned off for NiiX, which silences calls entirely.
 * And, from Android 14, the "full-screen notifications" permission: without it the system shows an
 * incoming call as an ordinary banner and tucks it away after a few seconds, so the call seems to
 * vanish while it is in fact still ringing in the notification shade. Android 14 grants it to
 * calling apps by default, but some phones, and Google Play, don't, and the user can take it back.
 */
object CallReadiness {

    /** Asked at most once per run of the app, so it's a reminder rather than nagging. */
    @Volatile private var askedThisRun = false

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True where a ringing call can stay on screen until it's answered or declined. */
    fun canRingFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }.getOrDefault(false)
    }

    /**
     * Offers to fix whatever would stop calls ringing properly. [force] asks even if it was asked
     * already this run, for when the user has just turned calls on.
     */
    fun promptIfNeeded(activity: Activity, force: Boolean = false) {
        if (!force && askedThisRun) return
        when {
            !notificationsAllowed(activity) -> {
                askedThisRun = true
                niixDialog(activity)
                    .setTitle(R.string.call_ring_title)
                    .setMessage(R.string.call_ring_notifications_off)
                    .setPositiveButton(R.string.call_ring_open_settings) { _, _ -> openNotificationSettings(activity) }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
            !canRingFullScreen(activity) -> {
                askedThisRun = true
                niixDialog(activity)
                    .setTitle(R.string.call_ring_title)
                    .setMessage(R.string.call_ring_full_screen)
                    .setPositiveButton(R.string.call_ring_open_settings) { _, _ -> openFullScreenSettings(activity) }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
        }
    }

    private fun openNotificationSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        runCatching { activity.startActivity(intent) }.onFailure { openAppDetails(activity) }
    }

    private fun openFullScreenSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(intent) }.onFailure { openAppDetails(activity) }
    }

    private fun openAppDetails(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
            )
        }
    }
}
