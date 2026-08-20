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
        keyRotationJob?.cancel()
        keyRotationJob = null
        relayFetchJob?.cancel()
        relayFetchJob = null
        relayGrantRefreshJob?.cancel()
        relayGrantRefreshJob = null
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

    /**
     * Checks the signed-prekey / last-resort-Kyber-prekey rotation schedule on a slow timer so
     * a device that's left running for weeks without a restart still rotates on time. The check
     * itself is a no-op unless a key has actually aged past its rotation interval (see
     * [app.niix.core.crypto.PreKeyManager.rotateKeysIfDue]), so an interval this long costs
     * nothing extra -- it just bounds how late a rotation could ever be.
     */
    private fun startKeyRotationLoop(container: AppContainer) {
        keyRotationJob?.cancel()
        keyRotationJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.crypto.rotateKeysIfDue() }
                kotlinx.coroutines.delay(KEY_ROTATION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * Recipient side of the offline-mailbox/relay feature (build spec item 11.6/11.7): checks
     * opt-in relay nodes for anything left for this device while it was offline. Runs
     * unconditionally alongside the ordinary retry loop -- whether *this* device also hosts for
     * others is a separate, independent setting (see [app.niix.core.relay.RelayManager]'s class
     * doc) that only affects [RelayConnectionHandler] inbound handling, not this.
     */
    private fun startRelayFetchLoop(container: AppContainer) {
        relayFetchJob?.cancel()
        relayFetchJob = container.appScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(RELAY_FETCH_INTERVAL_MILLIS)
                runCatching { container.conversations.fetchRelayedMessages() }
            }
        }
    }

    /** Background top-up for RelayGrant issuance/reissue (item 11.1) -- see
     * [app.niix.core.messaging.ConversationManager.refreshRelayGrants]'s doc comment for why an
     * interval this coarse is fine (grants are valid for 30 days, reissued once inside 7 days of
     * expiring). */
    private fun startRelayGrantRefreshLoop(container: AppContainer) {
        relayGrantRefreshJob?.cancel()
        relayGrantRefreshJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.conversations.refreshRelayGrants() }
                kotlinx.coroutines.delay(RELAY_GRANT_REFRESH_INTERVAL_MILLIS)
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
        // Just how often to re-check whether a rotation is due, not the rotation cadence
        // itself (see CryptoConstants.SIGNED_PREKEY_ROTATION_INTERVAL_MILLIS /
        // KYBER_LAST_RESORT_ROTATION_INTERVAL_MILLIS for that) -- the check is a cheap no-op
        // whenever nothing is actually due yet, so this can safely be coarse.
        private const val KEY_ROTATION_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000 // 6 hours
        // Item 11.7 of the relay build spec ("periodic + push-triggered wake"): this is the
        // periodic half. A push-triggered wake, if/when this app adds push notification support,
        // would call container.conversations.fetchRelayedMessages() directly instead of waiting
        // for this timer.
        private const val RELAY_FETCH_INTERVAL_MILLIS = 5L * 60 * 1000 // 5 minutes
        private const val RELAY_GRANT_REFRESH_INTERVAL_MILLIS = 12L * 60 * 60 * 1000 // 12 hours
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
