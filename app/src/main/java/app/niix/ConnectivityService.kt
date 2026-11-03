package app.niix

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.niix.core.storage.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
            val pending = android.app.PendingIntent.getForegroundService(this, 1, restart, flags)
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
        networkCatchUpJob?.cancel()
        networkCallback?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
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

    /** Checks relays, sends anything pending and, if turned on, tells recent contacts we are back. */
    private suspend fun onConnectionReady(container: AppContainer) {
        runCatching { container.conversations.refreshNow() }
        runCatching { container.conversations.announceOnline() }
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkCatchUpJob: Job? = null

    /**
     * Catches up after the phone's network changes: Wi-Fi to mobile, back from airplane mode,
     * out of a tunnel. Tor recovers on its own, but nothing told the app to check for messages
     * or retry until the next timer. Waits a little for Tor first, and a new change cancels the
     * pending wait, so a flickering connection triggers one catch-up rather than many.
     */
    private fun registerNetworkCallback(container: AppContainer) {
        if (networkCallback != null) return
        val manager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                networkCatchUpJob?.cancel()
                networkCatchUpJob = container.appScope.launch {
                    kotlinx.coroutines.delay(NETWORK_SETTLE_MILLIS)
                    onConnectionReady(container)
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun startRetryLoop(container: AppContainer) {
        retryJob?.cancel()
        registerNetworkCallback(container)
        retryJob = container.appScope.launch {
            var lastAliveMark = 0L
            while (isActive) {
                kotlinx.coroutines.delay(RETRY_INTERVAL_MILLIS)
                runCatching { container.conversations.retryPending() }
                // Evidence for noticing later that Android stopped this service: if the gap
                // since the last mark is long and the phone was not restarted in between, the
                // service was killed while the phone stayed on.
                val t = android.os.SystemClock.elapsedRealtime()
                if (t - lastAliveMark > ALIVE_MARK_INTERVAL_MILLIS) {
                    container.recordServiceAlive()
                    lastAliveMark = t
                }
            }
        }
    }

    private fun startKeyRotationLoop(container: AppContainer) {
        keyRotationJob?.cancel()
        keyRotationJob = container.appScope.launch {
            while (isActive) {
                // Covers a phone left switched on but never opened: the lock screens only check
                // when someone looks. appScope runs on IO, so the blocking wipe is fine here.
                if (container.wipeIfInactive()) {
                    stopSelf()
                    break
                }
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
 // NotificationHelper), so there's nothing to update it for, skip entirely rather
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
                // The number that decides whether polling is viable: a cold bootstrap costing
                // tens of seconds makes a 30-minute cycle mostly connection setup, while a few
                // seconds makes frequent short wakeups cheap.
                val bootstrapStart = android.os.SystemClock.elapsedRealtime()
                container.transport.start()
                val onion = container.transport.publishOnionService(AppContainer.SERVICE_PORT)
                container.selfOnion = onion.value
                // Connected: catch up now rather than at the next timer (up to 5 minutes for
                // relays, longer for messages already in backoff).
                container.appScope.launch { onConnectionReady(container) }
 // Safety numbers are pairwise, so deriving one needs this device's own stable
 // identifier as well as the peer's.
                container.crypto.localOnionForFingerprint = onion.value
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
 // A muted conversation still delivers and still appears in the list, muting
 // suppresses the notification, not the message. Read defensively because this
 // runs while the database may be closed (a message arriving while locked emits
 // here too), and failing to read the setting should not lose the notification.
                val muted = runCatching {
                    container.storage.settings.getBool(
                        SettingsStore.mutedKey(notice.conversationId),
                        false,
                    )
                }.getOrDefault(false)
                if (muted) return@collect

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
 // The service type argument is required from API 29, which is the app's minimum, so
 // the untyped call below it was unreachable. The 34 branch above stays: SPECIAL_USE
 // is only accepted there, and DATA_SYNC is what 29 to 33 expect.
            else ->
                startForeground(
                    NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MILLIS = 20_000L

        private const val KEY_ROTATION_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
        private const val NETWORK_SETTLE_MILLIS = 15_000L
        private const val ALIVE_MARK_INTERVAL_MILLIS = 5L * 60 * 1000

        private const val RELAY_FETCH_INTERVAL_MILLIS = 5L * 60 * 1000
        private const val RELAY_GRANT_REFRESH_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        fun start(context: Context) {
            val intent = Intent(context, ConnectivityService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectivityService::class.java))
        }
    }
}
