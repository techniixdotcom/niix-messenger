package app.niix.call

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.niix.NiixApp
import app.niix.NotificationHelper
import app.niix.core.messaging.CallPhase
import app.niix.core.model.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the microphone open while a call is connected and the app is in the background. Android
 * silences the microphone of an app that isn't in front unless a foreground service of the
 * microphone type is running, so this runs from picking up until the call ends, then stops itself.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (application as NiixApp).container.conversations.calls
        // startForeground first, always: a service started this way that stops without calling
        // it makes Android end the app.
        val notification = NotificationHelper.ongoingCall(this)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NotificationHelper.CALL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NotificationHelper.CALL_NOTIFICATION_ID, notification)
            }
        }
        if (started.isFailure) {
            // Without it the call still works while NiiX is on screen.
            DiagnosticLog.record("call", "couldn't keep the microphone for the background (${started.exceptionOrNull()?.javaClass?.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!calls.isInCall()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                calls.state.collect { state ->
                    if (state.phase == CallPhase.IDLE || state.phase == CallPhase.ENDED) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        // Not restarted by the system: a call that died with the process is over.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CallService::class.java)) }
                .onFailure { DiagnosticLog.record("call", "call service didn't start (${it::class.java.simpleName})") }
        }
    }
}

/** Decline and hang up from a notification, without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as NiixApp).container.conversations.calls
        when (intent.action) {
            ACTION_DECLINE -> calls.decline()
            ACTION_HANG_UP -> calls.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.niix.call.DECLINE"
        const val ACTION_HANG_UP = "app.niix.call.HANG_UP"
    }
}
