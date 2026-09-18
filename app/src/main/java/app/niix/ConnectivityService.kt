package app.niix

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.niix.core.storage.SettingsStore
import app.niix.core.model.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var idleJob: Job? = null
    private var pollShutdownJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

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

        // No alarm needed while the service is up; it is rescheduled when we stop.
        SleepPoller.cancel(this)
        registerScreenWatcher(container)

        battery(container, if (intent?.action == ACTION_POLL_ONCE) "service started: scheduled wakeup" else "service started: foreground")

        // A wakeup from the sleep poller starts the service purely to collect what the relays
        // are holding. Once that is done it stops again, which is what keeps the notification
        // off the status bar for the rest of the window.
        if (intent?.action == ACTION_POLL_ONCE) {
            schedulePollShutdown(container)
        }
        return START_STICKY
    }

    /**
     * Stops the service once a polling wakeup has had time to connect and drain.
     *
     * Two minutes because Tor needs to bootstrap first, which is seconds to tens of seconds on a
     * cold start. Cutting it shorter risks stopping mid-fetch and leaving messages on the relay
     * for another full window.
     */
    private fun schedulePollShutdown(container: AppContainer) {
        pollShutdownJob?.cancel()
        pollShutdownJob = CoroutineScope(Dispatchers.Default).launch {
            delay(POLL_WINDOW_MILLIS)
            if (shouldSleep(container)) {
                DiagnosticLog.record("sleep", "poll window over: disconnecting until the next wakeup")
                battery(container, "service stopping: poll window over")
                stopSelf()
            }
        }
    }

    /**
     * Watches the screen so the connection follows whether anyone is looking.
     *
     * Screen on is the signal that the user is present and wants messages now. Screen off is when
     * the notification is visible for no benefit and the radio is burning battery for messages
     * nobody is reading yet.
     */
    private fun registerScreenWatcher(container: AppContainer) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Recorded before the decision, so a log with no sleeping in it says
                        // which half failed: no "screen off" line at all means the broadcast
                        // never arrived, while "screen off, but staying connected" means the
                        // broadcast worked and something declined.
                        battery(container, "screen off")
                        if (!shouldSleep(container)) {
                            battery(container, "screen off but staying connected: ${whyNotSleeping(container)}")
                            return
                        }
                        idleJob?.cancel()
                        idleJob = CoroutineScope(Dispatchers.Default).launch {
                            // A grace period: the screen goes off constantly during normal use,
                            // and tearing down Tor every time would cost more than it saves.
                            delay(IDLE_GRACE_MILLIS)
                            DiagnosticLog.record("sleep", "screen off: disconnecting, will poll periodically")
                            battery(container, "service stopping: screen off")
                            stopSelf()
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        battery(container, "screen on")
                        idleJob?.cancel()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        }
    }

    /**
     * Whether to drop the connection while the screen is off.
     *
     * Never while relay hosting is on. Hosting means other people's messages arrive here and wait
     * to be collected, and that only works while this device is reachable. Sleeping would take
     * the relay offline for most of the day without saying so, and the people depending on it
     * would see their messages silently fail to deliver. Someone who turned hosting on accepted
     * the battery cost; quietly undoing that is not a saving, it is a broken promise to the rest
     * of the network.
     */
    /** Records a battery event when measurement is on, and does nothing otherwise. */
    private fun battery(container: AppContainer, event: String) {
        runCatching { container.storage.batteryLog.record(event) }
    }

    /** The reason sleeping was declined, for the battery log. */
    private fun whyNotSleeping(container: AppContainer): String = runCatching {
        when {
            container.relay.isHostingEnabled() -> "relay hosting is on"
            !container.storage.settings.getBool(
                app.niix.core.storage.SettingsStore.KEY_SLEEP_WHEN_IDLE,
                true,
            ) -> "the setting is off"
            else -> "unknown"
        }
    }.getOrDefault("could not read settings")

    private fun shouldSleep(container: AppContainer): Boolean = runCatching {
        if (container.relay.isHostingEnabled()) return false
        container.storage.settings.getBool(
            app.niix.core.storage.SettingsStore.KEY_SLEEP_WHEN_IDLE,
            true,
        )
    }.getOrDefault(true)

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
        keyRotationJob?.cancel()
        keyRotationJob = null
        relayFetchJob?.cancel()
        relayFetchJob = null
        relayGrantRefreshJob?.cancel()
        relayGrantRefreshJob = null
        runCatching { container.storage.batteryLog.record("disconnected") }
        bootstrapProgressJob?.cancel()
        bootstrapProgressJob = null
        idleJob?.cancel()
        idleJob = null
        pollShutdownJob?.cancel()
        pollShutdownJob = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null

        // Schedule the next wakeup on the way out. Doing it here covers every route to stopping:
        // the screen going off, a poll window ending, or Android killing the service.
        if (shouldSleep(container)) SleepPoller.schedule(this)
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
                battery(
                    container,
                    "connected: bootstrap took ${(android.os.SystemClock.elapsedRealtime() - bootstrapStart) / 1000}s",
                )
                container.selfOnion = onion.value
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

        private const val RELAY_FETCH_INTERVAL_MILLIS = 5L * 60 * 1000
        private const val RELAY_GRANT_REFRESH_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        /** Marks a start as a polling wakeup, so the service knows to stop again afterwards. */
        const val ACTION_POLL_ONCE = "app.niix.action.POLL_ONCE"

        /** How long a polling wakeup stays connected. Tor bootstrap alone can take tens of
         * seconds on a cold start, so cutting this shorter risks stopping mid-fetch. */
        private const val POLL_WINDOW_MILLIS = 2L * 60 * 1000

        /** Grace period after the screen goes off. The screen goes off constantly during normal
         * use and tearing down Tor each time would cost more than it saves. */
        private const val IDLE_GRACE_MILLIS = 60L * 1000

        fun startForPoll(context: Context) {
            val intent = Intent(context, ConnectivityService::class.java).setAction(ACTION_POLL_ONCE)
            context.startForegroundService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, ConnectivityService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectivityService::class.java))
        }
    }
}
