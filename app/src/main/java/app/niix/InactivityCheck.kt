package app.niix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Keeps the inactivity wipe counting while nothing else of NiiX runs.
 *
 * Its clock moves on with Android's time since boot, which a reboot resets, so how far it got has
 * to be saved now and then (see InactivityClock). The background service doesn't always run, with
 * "Disconnect when locked" for one, so a system alarm does it every quarter of an hour while the
 * wipe is on: otherwise someone could keep the phone on for days, wind the clock back and reboot,
 * and all those days would be gone. Each alarm also wipes if the time is up. It's inexact and
 * doesn't wake the phone, so it costs next to nothing; while the phone sleeps the counter runs on
 * anyway and the next alarm catches up.
 */
class InactivityCheck : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) = checkFrom(this, context) {}

    companion object {
        /**
         * Checks, and wipes if the time is up, on a thread of its own: a wipe waits for Tor to
         * stop, which isn't for the main thread. [receiver]'s broadcast is kept alive until it's
         * done. [ifNotWiped] runs afterwards when nothing was wiped.
         */
        fun checkFrom(receiver: BroadcastReceiver, context: Context, ifNotWiped: () -> Unit) {
            val container = (context.applicationContext as NiixApp).container
            val done = receiver.goAsync()
            thread(name = "inactivity-check") {
                try {
                    if (container.wipeIfInactive()) {
                        context.stopService(Intent(context, ConnectivityService::class.java))
                        schedule(context)
                    } else {
                        ifNotWiped()
                    }
                } finally {
                    done.finish()
                }
            }
        }

        private const val INTERVAL_MILLIS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

        /** Starts the alarm if the wipe is on, stops it if not. Safe to call any number of times. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, InactivityCheck::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val on = runCatching {
                (context.applicationContext as NiixApp).container.storage.appLock.inactivityWipeHours() > 0
            }.getOrDefault(false)
            if (on) {
                alarms.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MILLIS,
                    INTERVAL_MILLIS,
                    pending,
                )
            } else {
                alarms.cancel(pending)
            }
        }
    }
}
