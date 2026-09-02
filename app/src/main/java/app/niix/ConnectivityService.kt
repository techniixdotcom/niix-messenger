package app.niix

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.niix.core.storage.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectivityService : Service() {

    private var notifyJob: Job? = null
    private var retryJob: Job? = null
    private var keyRotationJob: Job? = null
    private var relayFetchJob: Job? = null
    private var relayGrantRefreshJob: Job? = null
    private var bootstrapProgressJob: Job? = null

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
        startBootstrapProgressUpdates(container, privacy)
        startNetworking(container)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {

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
        keyRotationJob?.cancel()
        keyRotationJob = null
        relayFetchJob?.cancel()
        relayFetchJob = null
        relayGrantRefreshJob?.cancel()
        relayGrantRefreshJob = null
        bootstrapProgressJob?.cancel()
        bootstrapProgressJob = null
        container.receiver.stop()
        container.relay.stop()
        container.coverTraffic.stop()
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

    private fun startKeyRotationLoop(container: AppContainer) {
        keyRotationJob?.cancel()
        keyRotationJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.crypto.rotateKeysIfDue() }
                kotlinx.coroutines.delay(KEY_ROTATION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    private fun startRelayFetchLoop(container: AppContainer) {
        relayFetchJob?.cancel()
        relayFetchJob = container.appScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(RELAY_FETCH_INTERVAL_MILLIS)
                runCatching { container.conversations.fetchRelayedMessages() }
            }
        }
    }

    private fun startRelayGrantRefreshLoop(container: AppContainer) {
        relayGrantRefreshJob?.cancel()
        relayGrantRefreshJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.conversations.refreshRelayGrants() }
                kotlinx.coroutines.delay(RELAY_GRANT_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private fun startBootstrapProgressUpdates(container: AppContainer, privacy: Boolean) {
        bootstrapProgressJob?.cancel()
        // In privacy mode the notification text never changes regardless of progress (see
        // NotificationHelper), so there's nothing to update it for -- skip entirely rather
        // than repeatedly rebuilding an identical notification.
        if (privacy) return
        bootstrapProgressJob = container.appScope.launch {
            container.transport.bootstrapProgress.collect { percent ->
                runCatching {
                    startForegroundCompat(
                        NotificationHelper.foregroundNotification(this@ConnectivityService, privacy, percent),
                    )
                }
            }
        }
    }

    private fun startNetworking(container: AppContainer) {
        container.appScope.launch {
            runCatching {
                if (!container.storage.appLock.isUnlocked()) return@launch
                container.crypto.ensureKeysInitialized()
                container.crypto.rotateKeysIfDue()
                container.transport.start()
                val onion = container.transport.publishOnionService(AppContainer.SERVICE_PORT)
                container.selfOnion = onion.value
                container.receiver.start()
                container.expirySweeper.start(container.appScope)
                container.relay.start(container.appScope)
                startMessageNotifications(container)
                startRetryLoop(container)
                startKeyRotationLoop(container)
                startRelayFetchLoop(container)
                startRelayGrantRefreshLoop(container)
                if (container.storage.settings.getBool(SettingsStore.KEY_COVER_TRAFFIC_ENABLED, false)) {
                    container.coverTraffic.start(container.appScope)
                }
            }
        }
    }

    private fun startMessageNotifications(container: AppContainer) {
        notifyJob?.cancel()
        notifyJob = container.appScope.launch {

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

        private const val KEY_ROTATION_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        private const val RELAY_FETCH_INTERVAL_MILLIS = 5L * 60 * 1000
        private const val RELAY_GRANT_REFRESH_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
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
