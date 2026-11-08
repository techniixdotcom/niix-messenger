package app.niix

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.TransportState
import app.niix.core.messaging.CallPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ConnectivityService : Service() {

    private var notifyJob: Job? = null
    private var callJob: Job? = null
    private var missedCallJob: Job? = null
    private var retryJob: Job? = null
    private var keyRotationJob: Job? = null
    private var nostrAnnounceJob: Job? = null
    private var bootstrapProgressJob: Job? = null
    private var healthJob: Job? = null
    private var networkJob: Job? = null

    @Volatile
    private var startJob: Job? = null

    /** Set while [startJob] is waiting to try again rather than starting. */
    @Volatile
    private var waitingToRetry = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as NiixApp).container
        // While locked the setting can't be read, and the private notification is the safe guess.
        val privacy = runCatching {
            container.storage.settings.getBool(SettingsStore.KEY_NOTIFICATION_PRIVACY, true)
        }.getOrDefault(true)
        startForegroundCompat(NotificationHelper.foregroundNotification(this, privacy))
        startBootstrapProgressUpdates(container, privacy)
        startNetworking(container)
        return START_STICKY
    }

    // Swiping the app away from recents shouldn't kill message delivery, so the service schedules
    // its own restart. Nice try, Android.
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
        callJob?.cancel()
        callJob = null
        missedCallJob?.cancel()
        missedCallJob = null
        NotificationHelper.cancelIncomingCall(this)
        retryJob?.cancel()
        retryJob = null
        keyRotationJob?.cancel()
        keyRotationJob = null
        nostrAnnounceJob?.cancel()
        nostrAnnounceJob = null
        bootstrapProgressJob?.cancel()
        bootstrapProgressJob = null
        startJob?.cancel()
        startJob = null
        healthJob?.cancel()
        healthJob = null
        networkJob?.cancel()
        networkJob = null
        container.receiver.stop()
        container.nostr.stop()
        container.coverTraffic.stop()
        container.appScope.launch { runCatching { container.transport.stop() } }
        super.onDestroy()
    }

    private fun startRetryLoop(container: AppContainer) {
        retryJob?.cancel()
        retryJob = container.appScope.launch {
            // Everything waiting goes as soon as the connection is up, not twenty seconds later.
            runCatching { container.conversations.retryPending(urgent = true) }
            runCatching { container.conversations.announceOnline() }
            var lastAliveMark = 0L
            while (isActive) {
                // Evidence for noticing later that Android stopped this service: see
                // AppContainer.takeBackgroundStopGap.
                val t = android.os.SystemClock.elapsedRealtime()
                if (lastAliveMark == 0L || t - lastAliveMark > ALIVE_MARK_INTERVAL_MILLIS) {
                    container.recordServiceAlive()
                    lastAliveMark = t
                }
                delay(RETRY_INTERVAL_MILLIS)
                runCatching { container.conversations.retryPending() }
            }
        }
    }

    private fun startKeyRotationLoop(container: AppContainer) {
        keyRotationJob?.cancel()
        keyRotationJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.crypto.rotateKeysIfDue() }
                delay(KEY_ROTATION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * Re-sends our Nostr inbox details to any contact who hasn't acknowledged the current ones.
     * Incoming messages don't need polling; the subscription pushes them.
     */
    private fun startNostrAnnounceLoop(container: AppContainer) {
        nostrAnnounceJob?.cancel()
        nostrAnnounceJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.conversations.refreshNostrAnnouncements() }
                delay(NOSTR_ANNOUNCE_INTERVAL_MILLIS)
            }
        }
    }

    private fun startBootstrapProgressUpdates(container: AppContainer, privacy: Boolean) {
        bootstrapProgressJob?.cancel()
        // In privacy mode the notification text never changes, so there's nothing to update.
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

    /**
     * Brings everything up, and keeps trying if that fails: Tor can take longer than its two
     * minutes to connect on a bad network, and before, one failure left the service up with nothing
     * running until the app was next opened. Opening the app or a new network tries again at once.
     */
    private fun startNetworking(container: AppContainer) {
        if (startJob?.isActive == true) {
            if (!waitingToRetry) return
            startJob?.cancel()
        }
        waitingToRetry = false
        startJob = container.appScope.launch {
            var wait = START_RETRY_MIN_MILLIS
            while (isActive) {
                try {
                    startLock.withLock { bringUp(container) }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLog.record("network", "could not start (${e::class.java.simpleName}), trying again")
                }
                waitingToRetry = true
                withTimeoutOrNull(wait) { container.network.changes.first() }
                waitingToRetry = false
                wait = (wait * 2).coerceAtMost(START_RETRY_MAX_MILLIS)
            }
        }
    }

    /**
     * Safe to run again while running: every step is either skipped or replaces what it started.
     *
     * Also runs while locked, when Android has restarted the service or the phone has rebooted, if
     * there's a receive beacon (see ReceiveBeacon) to start from. Then only receiving starts:
     * messages wait in the locked inbox, and the rest follows on the next run after unlocking.
     */
    private suspend fun bringUp(container: AppContainer) {
        val unlocked = container.storage.appLock.isUnlocked()
        if (!unlocked && !container.storage.receiveBeacon.exists()) return
        if (unlocked) {
            container.crypto.ensureKeysInitialized()
            container.crypto.rotateKeysIfDue()
        }
        container.transport.start()
        val onion = container.transport.publishOnionService(AppContainer.SERVICE_PORT)
        container.selfOnion = onion.value
        container.receiver.start()
        container.conversations.startNostr(container.appScope)
        startMessageNotifications(container)
        startCallNotifications(container)
        if (healthJob?.isActive != true) startHealthChecks(container)
        if (networkJob?.isActive != true) followNetwork(container)
        if (!unlocked) return
        // Tor is connected, so the device clock is roughly right; see MonotonicClock.
        runCatching { container.storage.clock.dropFutureMark() }
        container.refreshAccountOnion()
        container.conversations.refreshWipeBeacon()
        container.expirySweeper.start(container.appScope)
        startRetryLoop(container)
        startKeyRotationLoop(container)
        startNostrAnnounceLoop(container)
        if (container.storage.settings.getBool(SettingsStore.KEY_COVER_TRAFFIC_ENABLED, false)) {
            container.coverTraffic.start(container.appScope)
        }
    }

    /**
     * Every so often, and shortly after the network changes, checks that our address still answers.
     * If it doesn't twice in a row while there's a network, Tor is restarted, which is what force
     * stopping the app used to be needed for.
     */
    private fun startHealthChecks(container: AppContainer) {
        healthJob = container.appScope.launch {
            var failures = 0
            while (isActive) {
                val networkChanged = withTimeoutOrNull(HEALTH_CHECK_INTERVAL_MILLIS) { container.network.changes.first() } != null
                // Give Tor time to build new circuits on the new network first.
                if (networkChanged) delay(AFTER_NETWORK_CHANGE_MILLIS)
                if (!container.network.isNetworkConnected()) continue
                // Not up (yet, or on purpose while locked): starting is someone else's job.
                if (container.selfOnion == null || container.transport.state.value != TransportState.RUNNING) continue
                if (container.transport.checkOnionService()) {
                    failures = 0
                    continue
                }
                if (++failures < FAILURES_BEFORE_RESTART) continue
                // Publishing again needs the onion key: the database, or the beacon while locked.
                if (!container.storage.appLock.isUnlocked() && !container.storage.receiveBeacon.exists()) continue
                failures = 0
                DiagnosticLog.record("network", "own address unreachable, restarting Tor")
                try {
                    startLock.withLock {
                        container.transport.stop()
                        bringUp(container)
                    }
                    container.nostr.networkChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    startNetworking(container)
                }
            }
        }
    }

    /** Relay connections over a network that's gone are dead, so they're redialled at once. */
    private fun followNetwork(container: AppContainer) {
        networkJob = container.appScope.launch {
            container.network.changes.collectLatest {
                // Long enough for Tor to have switched networks itself (see NetworkMonitor).
                delay(NETWORK_SETTLE_MILLIS)
                DiagnosticLog.record("network", "network changed, reconnecting to relays")
                container.nostr.networkChanged()
                // Anything that failed while the network was gone goes now rather than on the
                // retry schedule, which may be minutes away by then.
                runCatching { container.conversations.retryPending(urgent = true) }
                runCatching { container.conversations.announceOnline() }
            }
        }
    }

    private fun startMessageNotifications(container: AppContainer) {
        notifyJob?.cancel()
        notifyJob = container.appScope.launch {
            container.conversations.incoming.collect { notice ->
                // Muting hides the notification, not the message. Read defensively: this also runs
                // while locked, when the database is closed, and a failed read shouldn't lose the
                // notification.
                val muted = runCatching {
                    container.storage.settings.getBool(
                        SettingsStore.mutedKey(notice.conversationId),
                        false,
                    )
                }.getOrDefault(false)
                if (muted) return@collect

                NotificationHelper.postMessage(
                    this@ConnectivityService,
                    getString(R.string.notif_new_message_generic),
                )
            }
        }
    }

    /**
     * Rings for incoming calls and reports missed ones. With NiiX on screen the call screen opens
     * straight away; otherwise the notification rings, and opens it full screen where Android
     * allows that.
     */
    private fun startCallNotifications(container: AppContainer) {
        val calls = container.conversations.calls
        if (callJob?.isActive != true) {
            callJob = container.appScope.launch {
                // collectLatest: a change of state cancels the re-posting loop below at once.
                calls.state.collectLatest { state ->
                    if (state.phase != CallPhase.INCOMING) {
                        NotificationHelper.cancelIncomingCall(this@ConnectivityService)
                        return@collectLatest
                    }
                    NotificationHelper.postIncomingCall(this@ConnectivityService)
                    withContext(Dispatchers.Main) {
                        if (container.lock.isInForeground()) {
                            runCatching {
                                startActivity(
                                    Intent(this@ConnectivityService, app.niix.ui.CallActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                )
                            }
                        }
                    }
                    // Without full-screen notifications Android hides the ringing banner after a
                    // few seconds. Posting it again brings it back, for as long as the call rings.
                    if (!app.niix.ui.CallReadiness.canRingFullScreen(this@ConnectivityService)) {
                        while (true) {
                            delay(RING_REPOST_MILLIS)
                            NotificationHelper.postIncomingCall(this@ConnectivityService)
                        }
                    }
                }
            }
        }
        if (missedCallJob?.isActive != true) {
            missedCallJob = container.appScope.launch {
                calls.missed.collect { account ->
                    // Muting a chat silences its missed calls too. Read defensively, as above.
                    val muted = runCatching {
                        container.storage.settings.getBool(SettingsStore.mutedKey(account), false)
                    }.getOrDefault(false)
                    if (!muted) NotificationHelper.postMissedCall(this@ConnectivityService)
                }
            }
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // Before 14 the type only has to match the manifest, and the manifest's specialUse
            // doesn't exist there. Passing DATA_SYNC, which the manifest doesn't declare, throws on
            // Android 10 to 13; the untyped call takes whatever the manifest says.
            startForeground(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MILLIS = 20_000L
        private const val RING_REPOST_MILLIS = 8_000L
        private const val ALIVE_MARK_INTERVAL_MILLIS = 5L * 60 * 1000
        private const val KEY_ROTATION_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
        private const val NOSTR_ANNOUNCE_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        private const val START_RETRY_MIN_MILLIS = 15_000L
        private const val START_RETRY_MAX_MILLIS = 5L * 60 * 1000
        private const val HEALTH_CHECK_INTERVAL_MILLIS = 10L * 60 * 1000
        private const val AFTER_NETWORK_CHANGE_MILLIS = 60_000L
        private const val FAILURES_BEFORE_RESTART = 2
        private const val NETWORK_SETTLE_MILLIS = 3_000L

        /**
         * One startup at a time, for the whole process. The service is started every time a screen
         * opens, and two startups overlapping is what used to break incoming messages.
         */
        private val startLock = Mutex()

        fun start(context: Context) {
            val intent = Intent(context, ConnectivityService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectivityService::class.java))
        }
    }
}
