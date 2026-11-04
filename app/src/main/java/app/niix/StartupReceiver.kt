package app.niix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts receiving after the phone restarts or the app is updated, without waiting for the app to
 * be opened. Only when there's a receive beacon to start from: with "Disconnect when locked" on,
 * or before setup, there isn't, and nothing starts until the app is unlocked.
 *
 * Right after a reboot it also checks the inactivity wipe, the first chance to mark where the new
 * boot's clock starts (see InactivityCheck).
 *
 * Both are protected broadcasts that only the system can send; the action is checked anyway.
 */
class StartupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as NiixApp).container
        InactivityCheck.checkFrom(this, context) {
            if (container.storage.receiveBeacon.exists()) runCatching { ConnectivityService.start(context) }
        }
    }
}
