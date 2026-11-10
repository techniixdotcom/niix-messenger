package app.niix.desktop

import app.niix.core.messaging.CallState
import app.niix.core.messaging.IncomingNotice
import app.niix.core.model.DiagnosticLog
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the phone app's ConnectivityService does, for the desktop: brings Tor, the onion service,
 * the receiver and the Nostr mailbox up, keeps retrying when that fails, checks the onion address
 * still answers, follows the network, and passes new messages and calls on to [listener].
 *
 * It runs while the app runs, window open or not, so messages keep arriving in the background.
 */
class Connectivity(private val container: DesktopContainer) {

    interface Listener {
        fun onMessage(notice: IncomingNotice)
        fun onCallState(state: CallState)
        fun onMissedCall(account: String)
    }

    @Volatile
    var listener: Listener? = null

    private var notifyJob: Job? = null
    private var callJob: Job? = null
    private var missedCallJob: Job? = null
    private var retryJob: Job? = null
    private var keyRotationJob: Job? = null
    private var nostrAnnounceJob: Job? = null
    private var healthJob: Job? = null
    private var networkJob: Job? = null

    @Volatile
    private var startJob: Job? = null

    @Volatile
    private var waitingToRetry = false

    private val startLock = Mutex()

    /** Starts everything, or retries a start that's waiting. Safe to call at any time. */
    fun start() {
        if (startJob?.isActive == true) {
            if (!waitingToRetry) return
            startJob?.cancel()
        }
        waitingToRetry = false
        startJob = container.appScope.launch {
            var wait = START_RETRY_MIN_MILLIS
            while (isActive) {
                try {
                    startLock.withLock { bringUp() }
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

    /** Stops everything and waits for Tor to stop. */
    suspend fun stopAndWait() {
        listOf(notifyJob, callJob, missedCallJob, retryJob, keyRotationJob, nostrAnnounceJob, startJob, healthJob, networkJob)
            .forEach { it?.cancel() }
        notifyJob = null
        callJob = null
        missedCallJob = null
        retryJob = null
        keyRotationJob = null
        nostrAnnounceJob = null
        startJob = null
        healthJob = null
        networkJob = null
        runCatching { container.receiver.stop() }
        runCatching { container.nostr.stop() }
        runCatching { container.coverTraffic.stop() }
        runCatching { container.expirySweeper.stop() }
        runCatching { container.transport.stop() }
        container.selfOnion = null
    }

    /**
     * Safe to run again while running. Also runs while locked, if there's a receive beacon to start
     * from: then only receiving starts, and the rest follows on the next run after unlocking.
     */
    private suspend fun bringUp() {
        val unlocked = container.storage.appLock.isUnlocked()
        if (!unlocked && !container.storage.receiveBeacon.exists()) return
        if (unlocked) {
            container.crypto.ensureKeysInitialized()
            container.crypto.rotateKeysIfDue()
        }
        container.transport.start()
        val onion = container.transport.publishOnionService(DesktopContainer.SERVICE_PORT)
        container.selfOnion = onion.value
        container.receiver.start()
        container.conversations.startNostr(container.appScope)
        startMessageNotifications()
        startCallNotifications()
        if (healthJob?.isActive != true) startHealthChecks()
        if (networkJob?.isActive != true) followNetwork()
        if (!unlocked) return
        runCatching { container.storage.clock.dropFutureMark() }
        container.refreshAccountOnion()
        container.conversations.refreshWipeBeacon()
        container.expirySweeper.start(container.appScope)
        startRetryLoop()
        startKeyRotationLoop()
        startNostrAnnounceLoop()
        if (container.storage.settings.getBool(SettingsStore.KEY_COVER_TRAFFIC_ENABLED, false)) {
            container.coverTraffic.start(container.appScope)
        } else {
            container.coverTraffic.stop()
        }
    }

    private fun startRetryLoop() {
        retryJob?.cancel()
        retryJob = container.appScope.launch {
            runCatching { container.conversations.retryPending(urgent = true) }
            runCatching { container.conversations.announceOnline() }
            while (isActive) {
                delay(RETRY_INTERVAL_MILLIS)
                runCatching { container.conversations.retryPending() }
            }
        }
    }

    private fun startKeyRotationLoop() {
        keyRotationJob?.cancel()
        keyRotationJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.crypto.rotateKeysIfDue() }
                delay(KEY_ROTATION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    private fun startNostrAnnounceLoop() {
        nostrAnnounceJob?.cancel()
        nostrAnnounceJob = container.appScope.launch {
            while (isActive) {
                runCatching { container.conversations.refreshNostrAnnouncements() }
                delay(NOSTR_ANNOUNCE_INTERVAL_MILLIS)
            }
        }
    }

    private fun startHealthChecks() {
        healthJob = container.appScope.launch {
            var failures = 0
            while (isActive) {
                val networkChanged = withTimeoutOrNull(HEALTH_CHECK_INTERVAL_MILLIS) { container.network.changes.first() } != null
                if (networkChanged) delay(AFTER_NETWORK_CHANGE_MILLIS)
                if (!container.network.isAvailable()) continue
                if (container.selfOnion == null || container.transport.state.value != TransportState.RUNNING) continue
                if (container.transport.checkOnionService()) {
                    failures = 0
                    continue
                }
                if (++failures < FAILURES_BEFORE_RESTART) continue
                if (!container.storage.appLock.isUnlocked() && !container.storage.receiveBeacon.exists()) continue
                failures = 0
                DiagnosticLog.record("network", "own address unreachable, restarting Tor")
                try {
                    startLock.withLock {
                        container.transport.stop()
                        bringUp()
                    }
                    container.nostr.networkChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    start()
                }
            }
        }
    }

    private fun followNetwork() {
        networkJob = container.appScope.launch {
            container.network.changes.collectLatest {
                delay(NETWORK_SETTLE_MILLIS)
                DiagnosticLog.record("network", "network changed, reconnecting to relays")
                container.nostr.networkChanged()
                runCatching { container.conversations.retryPending(urgent = true) }
                runCatching { container.conversations.announceOnline() }
            }
        }
    }

    private fun startMessageNotifications() {
        notifyJob?.cancel()
        notifyJob = container.appScope.launch {
            container.conversations.incoming.collect { notice ->
                val muted = runCatching {
                    container.storage.settings.getBool(SettingsStore.mutedKey(notice.conversationId), false)
                }.getOrDefault(false)
                if (!muted) listener?.onMessage(notice)
            }
        }
    }

    private fun startCallNotifications() {
        val calls = container.conversations.calls
        if (callJob?.isActive != true) {
            callJob = container.appScope.launch {
                calls.state.collect { state -> listener?.onCallState(state) }
            }
        }
        if (missedCallJob?.isActive != true) {
            missedCallJob = container.appScope.launch {
                calls.missed.collect { account ->
                    val muted = runCatching {
                        container.storage.settings.getBool(SettingsStore.mutedKey(account), false)
                    }.getOrDefault(false)
                    if (!muted) listener?.onMissedCall(account)
                }
            }
        }
    }

    private companion object {
        const val RETRY_INTERVAL_MILLIS = 20_000L
        const val KEY_ROTATION_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
        const val NOSTR_ANNOUNCE_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        const val START_RETRY_MIN_MILLIS = 15_000L
        const val START_RETRY_MAX_MILLIS = 5L * 60 * 1000
        const val HEALTH_CHECK_INTERVAL_MILLIS = 10L * 60 * 1000
        const val AFTER_NETWORK_CHANGE_MILLIS = 60_000L
        const val FAILURES_BEFORE_RESTART = 2
        const val NETWORK_SETTLE_MILLIS = 3_000L
    }
}
