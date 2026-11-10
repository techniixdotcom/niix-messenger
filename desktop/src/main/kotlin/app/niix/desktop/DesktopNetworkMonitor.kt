package app.niix.desktop

import io.matthewnelson.kmp.tor.runtime.NetworkObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.gnome.gio.NetworkMonitor

/**
 * Follows the computer's network through GLib's network monitor (the network portal inside a
 * Flatpak). Tor is told when the network goes and comes back, and [changes] tells the rest of the
 * app when it comes back or changes.
 *
 * GLib reports a change whenever routing does, which on a desktop is often and usually harmless,
 * so Tor is only told when the network actually goes or comes back.
 */
class DesktopNetworkMonitor(@Suppress("unused") private val scope: CoroutineScope) : NetworkObserver() {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val monitor: NetworkMonitor? = runCatching { NetworkMonitor.getDefault() }.getOrNull()

    @Volatile
    private var available: Boolean = monitor?.networkAvailable ?: true

    @Volatile
    private var lastChangeAt = 0L

    init {
        monitor?.onNetworkChanged { nowAvailable -> onChanged(nowAvailable) }
    }

    private fun onChanged(nowAvailable: Boolean) {
        val was = available
        available = nowAvailable
        when {
            !was && nowAvailable -> {
                notify(Connectivity.Connected)
                _changes.tryEmit(Unit)
            }
            was && !nowAvailable -> notify(Connectivity.Disconnected)
            nowAvailable -> {
                // Something about the network changed while it stayed up. Tor is left alone: on a
                // desktop this fires for all sorts of harmless reasons (a VPN, a container, a new
                // address), and cutting Tor's connections each time broke calls and file transfers
                // in the middle. The rest of the app is told, at most once a minute, so relays are
                // redialled and anything unsent is tried again.
                val now = System.nanoTime() / 1_000_000
                if (now - lastChangeAt < MIN_CHANGE_INTERVAL_MILLIS) return
                lastChangeAt = now
                _changes.tryEmit(Unit)
            }
        }
    }

    override fun isNetworkConnected(): Boolean = available

    fun isAvailable(): Boolean = available

    private companion object {
        const val MIN_CHANGE_INTERVAL_MILLIS = 60_000L
    }
}
