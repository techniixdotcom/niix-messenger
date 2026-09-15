package app.niix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.SettingsStore

/**
 * Wakes the app periodically while the connection is down.
 *
 * The foreground service holds Tor open and delivers messages as they arrive, but Android makes
 * it show a notification for as long as it runs. Stopping the service while the phone is asleep
 * removes the notification; this is what stops that from meaning messages never arrive.
 *
 * Each wakeup starts the service, which connects, drains anything the relays are holding, and
 * goes back to sleep. Relays keep envelopes for up to six hours, so a missed window costs delay,
 * not messages.
 *
 * setExactAndAllowWhileIdle is the only scheduling that fires in Doze at all, and Android
 * rate-limits it to roughly once every nine minutes per app. Thirty minutes is comfortably
 * within that and leaves the radio off most of the time, which is where the battery saving
 * actually comes from.
 *
 * Phone makers can and do ignore alarms from apps they have decided are unimportant. Xiaomi,
 * Huawei and Samsung are the usual ones. Nothing in the app can prevent that, so the settings
 * text says so rather than promising an interval the OS may not honour.
 */
object SleepPoller {

    private const val REQUEST_CODE = 4517
    private const val ACTION_POLL = "app.niix.action.SLEEP_POLL"

    val DEFAULT_INTERVAL_MINUTES = 30L

    fun intervalMillis(context: Context): Long {
        val minutes = runCatching {
            (context.applicationContext as NiixApp).container.storage.settings
                .getLong(SettingsStore.KEY_SLEEP_POLL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        }.getOrDefault(DEFAULT_INTERVAL_MINUTES)
        // Floor of 15 minutes: below that the Tor bootstrap on each wakeup costs more battery
        // than leaving the connection up would have, so a shorter interval is worse on both
        // counts.
        return minutes.coerceIn(15L, 24L * 60L) * 60_000L
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + intervalMillis(context)
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                at,
                pendingIntent(context),
            )
        }.onFailure {
            // Exact alarms can be refused on Android 12+ without the permission. An inexact one
            // still fires, just later, which is a better outcome than no polling at all.
            runCatching {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
            }
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarm.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, Receiver::class.java).setAction(ACTION_POLL).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Starts the service on each wakeup, then schedules the next one.
     *
     * Rescheduling here rather than using a repeating alarm because setExactAndAllowWhileIdle has
     * no repeating form, and because a one-shot that reschedules itself survives the app being
     * killed and restarted in a way a repeating alarm does not.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_POLL) return
            DiagnosticLog.record("sleep", "periodic wakeup: connecting to collect messages")
            runCatching {
                ConnectivityService.startForPoll(context.applicationContext)
            }
            schedule(context.applicationContext)
        }
    }
}
