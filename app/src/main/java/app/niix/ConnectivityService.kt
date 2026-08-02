package app.niix

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectivityService : Service() {

    private var notifyJob: Job? = null
    private var retryJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as NiixApp).container
        val privacy = runCatching {
            container.storage.settings.getBool(
                app.niix.core.storage.SettingsStore.KEY_NOTIFICATION_PRIVACY,
                true,
            )
        }.getOrDefault(true)
        startForegroundCompat(NotificationHelper.foregroundNotification(this, privacy))
        startNetworking(container)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running when the app is swiped from recents: schedule a near-immediate
        // restart in case the system tears the service down with the task.
        runCatching {
            val restart = Intent(applicationContext, ConnectivityService::class.java)
                .setPackage(packageName)
            val flags = android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.PendingIntent.getForegroundService(this, 1, restart, flags)
            } else {
                android.app.PendingIntent.getService(this, 1, restart, flags)
            }
            val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarm.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pending,
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val container = (application as NiixApp).container
        notifyJob?.cancel()
        notifyJob = null
        retryJob?.cancel()
        retryJob = null
        container.receiver.stop()
        container.appScope.launch { runCatching { container.transport.stop() } }
        super.onDestroy()
    }

    private fun startRetryLoop(container: AppContainer) {
        retryJob?.cancel()
        retryJob = container.appScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(RETRY_INTERVAL_MILLIS)
                runCatching { container.conversations.retryPending() }
            }
        }
    }

    private fun startMessageNotifications(container: AppContainer) {
        notifyJob?.cancel()
        notifyJob = container.appScope.launch {
            // Notification content is never shown, by design: the only string the OS (and its
            // own Notification History log) ever sees is this fixed, generic one -- there is no
            // setting or code path that can put a sender name or message text into a notification.
            container.conversations.incoming.collect { notice ->
                NotificationHelper.postMessage(
                    this@ConnectivityService,
                    getString(R.string.notif_new_message_title),
                    getString(R.string.notif_new_message_generic),
                    notice.conversationId,
                )
            }
        }
    }

    private fun startNetworking(container: AppContainer) {
        container.appScope.launch {
            runCatching {
                if (!container.storage.appLock.isUnlocked()) return@launch
                container.crypto.ensureKeysInitialized()
                container.transport.start()
                val onion = container.transport.publishOnionService(AppContainer.SERVICE_PORT)
                container.selfOnion = onion.value
                container.receiver.start()
                container.expirySweeper.start(container.appScope)
                startMessageNotifications(container)
                startRetryLoop(container)
            }
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(
                    NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(
                    NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            else ->
                startForeground(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MILLIS = 20_000L
        fun start(context: Context) {
            val intent = Intent(context, ConnectivityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectivityService::class.java))
        }
    }
}
